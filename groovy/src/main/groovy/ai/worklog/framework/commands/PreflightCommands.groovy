package ai.worklog.framework.commands

import ai.worklog.framework.adapters.PreflightScope
import ai.worklog.framework.core.CheckResult
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JournalValidation
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class PreflightCommands {
    static final int MAX_PARALLEL_CHECKS = 8

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
        List<Closure> checks = []
        if (selected(scope, 'workspace')) checks << { ResultSet r -> checkWorkspace(r, paths, frameworkRoot) }
        if (scope.checks == null) checks << { ResultSet r -> checkBinaries(r, config) }
        if (selected(scope, 'jira')) checks << { ResultSet r -> checkJira(r, paths) }
        if (selected(scope, 'git')) {
            checks << { ResultSet r ->
                checkCommand(r, 'git', ['git', 'config', 'user.email'], 'No user.email configured', false)
            }
        }
        if (selected(scope, 'github')) {
            checks << { ResultSet r ->
                checkCommand(r, 'github', ['gh', 'auth', 'status'], 'Not authenticated', false)
            }
        }
        if (selected(scope, 'aws')) checks << { ResultSet r -> checkAws(r) }
        if (selected(scope, 'kubectl')) checks << { ResultSet r -> checkKubectl(r) }
        if (selected(scope, 'servicenow')) checks << { ResultSet r -> checkServiceNow(r, paths) }
        if (selected(scope, 'jenkins')) {
            checks << { ResultSet r -> checkServiceFile(r, paths, 'jenkins', 'jenkins.properties') }
        }
        if (selected(scope, 'automox')) {
            checks << { ResultSet r -> checkServiceFile(r, paths, 'automox', 'automox.properties') }
        }
        if (selected(scope, 'argocd')) checks << { ResultSet r -> checkBinary(r, 'argocd') }
        if (selected(scope, 'newrelic')) {
            checks << { ResultSet r -> checkServiceFile(r, paths, 'newrelic', 'newrelic.properties') }
        }
        if (selected(scope, 'artifactory')) checks << { ResultSet r -> checkArtifactory(r, paths) }
        if (selected(scope, 'datadog')) checks << { ResultSet r -> checkServiceDirectory(r, paths, 'datadog') }
        if (selected(scope, 'repositories')) checks << { ResultSet r -> checkRepositories(r, paths, scope) }
        if (selected(scope, 'catalog_binaries')) {
            checks << { ResultSet r -> checkCatalogBinaries(r, frameworkRoot, scope) }
        }
        ResultSet results = runChecks(checks)

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

    static ResultSet runChecks(List<Closure> checks) {
        ResultSet merged = new ResultSet()
        if (!checks) {
            return merged
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(checks.size(), MAX_PARALLEL_CHECKS))
        try {
            List<Future<ResultSet>> futures = checks.collect { Closure check ->
                pool.submit({
                    ResultSet own = new ResultSet()
                    check(own)
                    own
                } as Callable<ResultSet>)
            }
            futures.each { Future<ResultSet> future ->
                try {
                    merged.results.addAll(future.get().results)
                } catch (ExecutionException exception) {
                    throw exception.cause ?: exception
                }
            }
        } finally {
            pool.shutdownNow()
        }
        merged
    }

    static boolean selected(PreflightScope scope, String check) {
        scope.checks == null || scope.checks.contains(check)
    }

    static void checkWorkspace(ResultSet results, FrameworkPaths paths, File frameworkRoot) {
        List<String> missing = []
        if (!paths.worklog.isDirectory()) missing << 'worklog/'
        List<Boolean> auditState = JournalValidation.workspaceAuditState(paths.root, frameworkRoot)
        boolean hasPromptLog = auditState[0]
        boolean hasJournal = auditState[1]
        if (!hasPromptLog && !hasJournal) {
            missing << 'prompt.log or repos/ai-memory-ingester/data/journal.db'
        }
        String message
        if (missing) {
            message = "Missing: ${missing.join(', ')}"
        } else if (hasJournal && !hasPromptLog) {
            message = 'Structure valid (journal.db)'
        } else if (hasPromptLog && !hasJournal) {
            message = 'Structure valid (legacy prompt.log)'
        } else {
            message = 'Structure valid'
        }
        results.add(new CheckResult(
            status: missing ? Status.DEGRADED : Status.READY,
            source: 'workspace',
            message: message
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
        if (new File(directory, 'jira.properties').isFile()) {
            results.add(new CheckResult(
                status: Status.READY,
                source: 'jira',
                message: 'Properties file present'
            ))
            return
        }
        def (Status status, String message) = directoryState(directory)
        if (status == Status.READY) {
            status = Status.DEGRADED
            message = 'jira.properties missing'
        }
        results.add(new CheckResult(status: status, source: 'jira', message: message))
    }

    static void checkBinary(ResultSet results, String binary) {
        boolean found = available(binary)
        results.add(new CheckResult(
            status: found ? Status.READY : Status.BLOCKED,
            source: "bin:${binary}",
            message: found ? 'Found' : 'Not found'
        ))
    }

    static List directoryState(File directory) {
        if (!directory.isDirectory()) {
            return [Status.BLOCKED, 'Directory not found']
        }
        File[] entries = directory.listFiles()
        if (entries == null) {
            return [Status.ERROR, 'Directory unreadable']
        }
        if (entries.length == 0) {
            return [Status.NOT_CONFIGURED, 'Not configured']
        }
        [Status.READY, 'Directory present']
    }

    static void checkServiceDirectory(
        ResultSet results,
        FrameworkPaths paths,
        String service
    ) {
        def (Status status, String message) = directoryState(paths.serviceDir(service))
        results.add(new CheckResult(status: status, source: service, message: message))
    }

    static void checkServiceFile(
        ResultSet results,
        FrameworkPaths paths,
        String service,
        String filename
    ) {
        File directory = paths.serviceDir(service)
        if (new File(directory, filename).isFile()) {
            results.add(new CheckResult(
                status: Status.READY,
                source: service,
                message: "${filename} present"
            ))
            return
        }
        def (Status status, String message) = directoryState(directory)
        if (status == Status.READY) {
            status = Status.DEGRADED
            message = "${filename} missing"
        }
        results.add(new CheckResult(status: status, source: service, message: message))
    }

    static void checkArtifactory(
        ResultSet results,
        FrameworkPaths paths,
        Map environment = System.getenv()
    ) {
        File directory = paths.serviceDir('artifactory')
        if (!directory.isDirectory()) {
            results.add(new CheckResult(
                status: Status.BLOCKED,
                source: 'artifactory',
                message: 'Directory not found'
            ))
            return
        }
        boolean environmentPresent =
            !!environment.ARTIFACTORY_URL?.toString()?.trim() &&
            !!environment.ARTIFACTORY_TOKEN?.toString()?.trim()
        boolean filePresent = ['artifactory.properties', 'credentials', 'creds'].any { filename ->
            new File(directory, filename).isFile()
        }
        results.add(new CheckResult(
            status: environmentPresent || filePresent ? Status.READY : Status.NOT_CONFIGURED,
            source: 'artifactory',
            message: environmentPresent || filePresent ?
                "Credential source present (${environmentPresent ? 'environment' : 'file'})" :
                'Not configured'
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

    static boolean available(String binary) {
        boolean isWindows = System.getProperty('os.name')?.toLowerCase()?.contains('win')
        List<String> cmd = isWindows ? ['where.exe', binary] : ['which', binary]
        ToolchainCommands.execute(cmd).code == 0
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
