package ai.worklog.framework.commands

import ai.worklog.framework.adapters.PreflightScope
import ai.worklog.framework.core.CheckResult
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status

class PreflightCommands {
    static int run(
        File frameworkRoot,
        FrameworkPaths paths,
        Map config,
        List<String> args
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        String ticket = option(args, '--ticket')
        List<String> services = multiOption(args, '--service')
        PreflightScope scope = PreflightScope.resolve(
            frameworkRoot, paths, ticket, services
        )
        ResultSet results = new ResultSet()
        if (selected(scope, 'workspace')) checkWorkspace(results, paths)
        if (scope.checks == null) checkBinaries(results, config)
        if (selected(scope, 'jira')) checkJira(results, paths)
        if (selected(scope, 'git')) {
            checkCommand(results, 'git', ['git', 'config', 'user.email'], 'No user.email configured', false)
        }
        if (selected(scope, 'github')) {
            checkCommand(results, 'github', ['gh', 'auth', 'status'], 'Not authenticated', false)
        }
        if (selected(scope, 'aws')) checkAws(results)
        if (selected(scope, 'kubectl')) checkKubectl(results)
        if (selected(scope, 'servicenow')) checkServiceNow(results, paths)
        if (selected(scope, 'jenkins')) {
            checkServiceFile(results, paths, 'jenkins', 'jenkins.properties')
        }
        if (selected(scope, 'argocd')) checkBinary(results, 'argocd')
        if (selected(scope, 'newrelic')) checkServiceDirectory(results, paths, 'newrelic')
        if (selected(scope, 'datadog')) checkServiceDirectory(results, paths, 'datadog')
        if (selected(scope, 'repositories')) checkRepositories(results, paths, scope)
        if (selected(scope, 'catalog_binaries')) {
            checkCatalogBinaries(results, frameworkRoot, scope)
        }
        if (selected(scope, 'toolchain')) checkToolchain(results, frameworkRoot, config)

        println results.summary()
        println()
        Status overall = results.overallStatus()
        if (overall == Status.READY) {
            println 'Preflight: READY'
            return exitCodes.success
        }
        println "Preflight: ${overall.value.toUpperCase()} (${results.actionable().size()} issue(s))"
        overall == Status.BLOCKED ? exitCodes.blocked : exitCodes.userError
    }

    static boolean selected(PreflightScope scope, String check) {
        scope.checks == null || scope.checks.contains(check)
    }

    static void checkWorkspace(ResultSet results, FrameworkPaths paths) {
        List<String> missing = []
        if (!paths.worklog.isDirectory()) missing << 'worklog/'
        if (!paths.promptLog.exists()) missing << 'prompt.log'
        results.add(new CheckResult(
            status: missing ? Status.DEGRADED : Status.READY,
            source: 'workspace',
            message: missing ? "Missing: ${missing.join(', ')}" : 'Structure valid'
        ))
    }

    static void checkBinaries(ResultSet results, Map config) {
        Map preflight = config.preflight instanceof Map ? (Map) config.preflight : [:]
        ((List) (preflight.required_binaries ?: [])).each { binary ->
            boolean found = available(binary.toString())
            results.add(new CheckResult(
                status: found ? Status.READY : Status.BLOCKED,
                source: "bin:${binary}",
                message: found ? 'Found' : 'Not found'
            ))
        }
        ((List) (preflight.optional_binaries ?: [])).each { binary ->
            boolean found = available(binary.toString())
            results.add(new CheckResult(
                status: found ? Status.READY : Status.DEGRADED,
                source: "bin:${binary}",
                message: found ? 'Found' : 'Not found (optional)'
            ))
        }
    }

    static void checkJira(ResultSet results, FrameworkPaths paths) {
        File directory = paths.serviceDir('jira')
        File properties = new File(directory, 'jira.properties')
        if (!directory.isDirectory()) {
            results.add(new CheckResult(status: Status.BLOCKED, source: 'jira', message: 'Directory not found'))
        } else if (!properties.isFile()) {
            results.add(new CheckResult(status: Status.BLOCKED, source: 'jira', message: 'jira.properties missing'))
        } else {
            results.add(new CheckResult(status: Status.READY, source: 'jira', message: 'Properties file present'))
        }
    }

    static void checkBinary(ResultSet results, String binary) {
        boolean found = available(binary)
        results.add(new CheckResult(
            status: found ? Status.READY : Status.BLOCKED,
            source: "bin:${binary}",
            message: found ? 'Found' : 'Not found'
        ))
    }

    static void checkServiceDirectory(
        ResultSet results,
        FrameworkPaths paths,
        String service
    ) {
        boolean present = paths.serviceDir(service).isDirectory()
        results.add(new CheckResult(
            status: present ? Status.READY : Status.BLOCKED,
            source: service,
            message: present ? 'Directory present' : 'Directory not found'
        ))
    }

    static void checkServiceFile(
        ResultSet results,
        FrameworkPaths paths,
        String service,
        String filename
    ) {
        boolean present = new File(paths.serviceDir(service), filename).isFile()
        results.add(new CheckResult(
            status: present ? Status.READY : Status.BLOCKED,
            source: service,
            message: present ? "${filename} present" : "${filename} missing"
        ))
    }

    static void checkRepositories(
        ResultSet results,
        FrameworkPaths paths,
        PreflightScope scope
    ) {
        Set<String> repositories = [] as Set
        scope.serviceIds.each { id ->
            ((List) (scope.catalog[id]?.repositories ?: [])).each { repository ->
                if (repository.local_dir) repositories << repository.local_dir.toString()
            }
        }
        repositories.sort().each { repository ->
            boolean present = new File(paths.root, "repos/${repository}").isDirectory()
            results.add(new CheckResult(
                status: present ? Status.READY : Status.BLOCKED,
                source: "repo:${repository}",
                message: present ? 'Present' : 'Not cloned'
            ))
        }
    }

    static void checkCatalogBinaries(
        ResultSet results,
        File frameworkRoot,
        PreflightScope scope
    ) {
        Map packs = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/diagnostic-packs.json'),
            [:]
        )
        Set<String> binaries = [] as Set
        scope.serviceIds.each { id ->
            List packIds = (List) (scope.catalog[id]?.monitoring?.diagnostic_packs ?: [])
            packIds.each { pack ->
                binaries.addAll(((List) (packs[pack]?.prerequisites ?: [])).collect {
                    it.toString()
                })
            }
        }
        binaries.sort().each { checkBinary(results, it) }
    }

    static void checkCommand(
        ResultSet results, String source, List<String> command, String failure, boolean blocked
    ) {
        Map result = ToolchainCommands.execute(command)
        String output = (result.out ?: result.err ?: '').trim()
        if (result.code == 0 && output) {
            results.add(new CheckResult(status: Status.READY, source: source, message:
                source == 'git' ? "Identity: ${output.readLines()[0]}" : 'Authenticated'))
        } else {
            results.add(new CheckResult(
                status: blocked ? Status.BLOCKED : Status.DEGRADED,
                source: source,
                message: failure
            ))
        }
    }

    static void checkAws(ResultSet results) {
        Map result = ToolchainCommands.execute(['aws', 'sts', 'get-caller-identity', '--output', 'json'])
        if (result.code == 0 && result.out.trim()) {
            Map identity = (Map) new groovy.json.JsonSlurper().parseText(result.out)
            results.add(new CheckResult(
                status: Status.READY,
                source: 'aws',
                message: "Account: ${identity.Account ?: 'unknown'}"
            ))
        } else {
            results.add(new CheckResult(status: Status.DEGRADED, source: 'aws', message: 'No active session'))
        }
    }

    static void checkKubectl(ResultSet results) {
        Map result = ToolchainCommands.execute(['kubectl', 'config', 'current-context'])
        if (result.code == 0 && result.out.trim()) {
            results.add(new CheckResult(
                status: Status.READY,
                source: 'kubectl',
                message: "Context: ${result.out.trim()}"
            ))
        } else {
            results.add(new CheckResult(status: Status.DEGRADED, source: 'kubectl', message: 'No current context'))
        }
    }

    static void checkServiceNow(ResultSet results, FrameworkPaths paths) {
        File cookie = new File(paths.serviceDir('snow'), 'cookie')
        if (!cookie.isFile()) {
            results.add(new CheckResult(status: Status.DEGRADED, source: 'servicenow', message: 'No cookie file'))
            return
        }
        long hours = ((System.currentTimeMillis() - cookie.lastModified()) / 3_600_000L) as long
        results.add(new CheckResult(
            status: hours > 24 ? Status.DEGRADED : Status.READY,
            source: 'servicenow',
            message: hours > 24 ? "Cookie is ${hours}h old (likely expired)" : "Cookie age: ${hours}h"
        ))
    }

    static void checkToolchain(ResultSet results, File frameworkRoot, Map config) {
        Map rules = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/toolchain-tools.json'), [:])
        Map toolchain = config.toolchain instanceof Map ? (Map) config.toolchain : [:]
        Map javaRuntimes = ToolchainCommands.detectJava(toolchain)
        Map groovyRuntimes = ToolchainCommands.detectGroovy(toolchain)

        String python = ToolchainCommands.commandVersion(['python3', '--version'])
        results.add(new CheckResult(
            status: python ? Status.READY : Status.BLOCKED,
            source: 'python3',
            message: python ?: 'Not detected'
        ))
        javaRuntimes.each { major, runtime ->
            results.add(new CheckResult(status: Status.READY, source: "java:${major}", message: runtime.version))
        }
        groovyRuntimes.each { major, runtime ->
            results.add(new CheckResult(
                status: Status.READY,
                source: "groovy:${major}",
                message: "${runtime.version} @ ${runtime.executable}"
            ))
        }
        ((Map) rules.tools).each { name, ignored ->
            Map resolved = ToolchainCommands.resolve(
                name.toString(), rules, toolchain, javaRuntimes, groovyRuntimes
            )
            results.add(new CheckResult(
                status: resolved.ready ? Status.READY : Status.BLOCKED,
                source: "tool:${name}",
                message: resolved.message
            ))
        }
    }

    static boolean available(String binary) {
        ToolchainCommands.execute(['which', binary]).code == 0
    }

    static String option(List<String> args, String name) {
        int index = args.indexOf(name)
        index >= 0 && index + 1 < args.size() ? args[index + 1] : null
    }

    static List<String> multiOption(List<String> args, String name) {
        int index = args.indexOf(name)
        if (index < 0) return []
        List<String> values = []
        for (int i = index + 1; i < args.size(); i++) {
            if (args[i].startsWith('--')) break
            values << args[i]
        }
        values
    }
}
