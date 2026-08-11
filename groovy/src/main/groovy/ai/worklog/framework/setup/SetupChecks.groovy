package ai.worklog.framework.setup

import ai.worklog.framework.adapters.PreflightScope
import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.commands.ToolchainCommands
import ai.worklog.framework.core.CheckResult
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status

class SetupChecks {
    static Map check(String status, String layer, String message) {
        [layer: layer, status: status, message: message]
    }

    static List<Map> runSetupChecks(
        File frameworkRoot,
        File workspace,
        String workspaceName = null,
        boolean includePreflight = true
    ) {
        List<Map> checks = []
        Map config
        try {
            config = GlobalConfig.load()
            checks << check(Status.READY.value, 'global', 'Configuration valid')
        } catch (IllegalArgumentException exception) {
            checks << check(Status.ERROR.value, 'global', exception.message)
            return checks
        }

        boolean registered = false
        List<String> registeredIdes = []
        if (workspaceName && config.workspaces[workspaceName]) {
            registered = true
            Map entry = (Map) config.workspaces[workspaceName]
            registeredIdes = ((List) (entry.ides ?: []))*.toString()
            String entryPath = entry.path?.toString()
            if (entryPath != workspace.canonicalFile.path) {
                checks << check(Status.BLOCKED.value, 'workspace', 'Registered path mismatch')
            } else if (GlobalConfig.workspaceAvailable(workspace.path)) {
                checks << check(Status.READY.value, 'workspace', 'Registered and available')
            } else {
                checks << check(Status.BLOCKED.value, 'workspace', 'Registered path unavailable')
            }
        } else if (GlobalConfig.workspaceAvailable(workspace.path)) {
            checks << check(Status.DEGRADED.value, 'workspace', 'Available but not registered')
        } else {
            checks << check(Status.BLOCKED.value, 'workspace', 'Workspace unavailable')
        }

        FrameworkPaths paths = new FrameworkPaths(workspace)
        List<String> missing = []
        if (!paths.worklog.isDirectory()) {
            missing << 'worklog/'
        }
        if (!paths.configDir.isDirectory()) {
            missing << '.ai-worklog/'
        }
        if (missing) {
            checks << check(Status.BLOCKED.value, 'structure', "Missing: ${missing.join(', ')}")
        } else {
            checks << check(Status.READY.value, 'structure', 'Workspace structure present')
        }

        List runtimeSelection = SetupResolver.resolveRuntimeSelection()
        String runtime = runtimeSelection[0]
        String runtimeSource = runtimeSelection[1]
        boolean runtimeOk = runtimeSelection[2]
        if (runtimeOk) {
            checks << check(Status.READY.value, 'runtime', "${runtime} available (${runtimeSource})")
        } else {
            checks << check(Status.BLOCKED.value, 'runtime', "${runtime} unavailable (${runtimeSource})")
        }

        Map wsConfig = ConfigLoader.load(workspace)
        Status toolchainStatus = toolchainOverallStatus(frameworkRoot, wsConfig)
        if (toolchainStatus == Status.READY) {
            checks << check(Status.READY.value, 'toolchain', 'Compatible')
        } else if (toolchainStatus == Status.BLOCKED) {
            checks << check(Status.BLOCKED.value, 'toolchain', 'Blocked compatibility issues')
        } else {
            checks << check(Status.DEGRADED.value, 'toolchain', 'Degraded compatibility')
        }

        List vaultResolution = SetupResolver.resolveAiVaultRoot(workspace)
        File vaultRoot = vaultResolution[0] as File
        String vaultSource = vaultResolution[1]?.toString()
        Map vaultManifest = [:]
        if (!vaultRoot) {
            checks << check(Status.BLOCKED.value, 'ai_vault', 'AI vault not found')
        } else {
            List vaultValidation = SetupVault.validateVaultRoot(vaultRoot)
            boolean valid = vaultValidation[0]
            String message = vaultValidation[1]?.toString()
            vaultManifest = vaultValidation[2] instanceof Map ? (Map) vaultValidation[2] : [:]
            if (valid) {
                checks << check(Status.READY.value, 'ai_vault', "Valid (${vaultSource})")
                checks << check(Status.READY.value, 'vault_manifest', 'Skill manifest valid')
            } else {
                checks << check(Status.BLOCKED.value, 'ai_vault', message)
            }
        }

        List<String> ides = registeredIdes
        if (!ides) {
            Map setupManifest = SetupManifest.loadManifest(workspace)
            if (setupManifest) {
                ides = ((List) setupManifest.ides)*.toString()
            }
        }
        if (!ides) {
            checks << check(Status.BLOCKED.value, 'ide', 'No IDE profiles registered')
        } else {
            checks << check(Status.READY.value, 'ide', "Profiles: ${ides.join(', ')}")
        }

        Map setupManifest = SetupManifest.loadManifest(workspace)
        if (vaultRoot && vaultManifest && ides) {
            List planned = SetupMaterialize.planSkillMaterialization(
                workspace,
                vaultRoot,
                vaultManifest,
                ides,
                setupManifest,
                false
            )
            List conflicts = (List) planned[1]
            if (conflicts) {
                checks << check(Status.BLOCKED.value, 'materialization', "${conflicts.size()} conflict(s)")
            } else {
                checks << check(Status.READY.value, 'materialization', 'Managed destinations valid')
            }

            List stale = staleEntries(workspace, setupManifest, vaultRoot, vaultManifest, ides)
            if (stale) {
                checks << check(Status.DEGRADED.value, 'freshness', "${stale.size()} stale item(s)")
            } else {
                checks << check(Status.READY.value, 'freshness', 'Up to date')
            }
        }

        if (includePreflight) {
            try {
                ResultSet preflight = executePreflight(frameworkRoot, paths, wsConfig)
                Status status = preflight.overallStatus()
                if (status == Status.UNKNOWN) {
                    status = Status.DEGRADED
                }
                String message = preflight.results ?
                    preflight.summary().readLines()[0] :
                    'No checks'
                checks << check(status.value, 'preflight', message)
            } catch (Exception exception) {
                checks << check(Status.ERROR.value, 'preflight', exception.message ?: exception.class.simpleName)
            }
        }

        checks
    }

    static Status aggregateCheckStatus(List<Map> checks) {
        List<Status> priority = [Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.READY]
        Status worst = Status.READY
        checks.each { Map item ->
            Status status = Status.values().find { it.value == item.status } ?: Status.UNKNOWN
            if (priority.indexOf(status) < priority.indexOf(worst)) {
                worst = status
            }
        }
        worst
    }

    static String findWorkspaceRegistration(File workspace) {
        Map config = GlobalConfig.load()
        String target = workspace.canonicalFile.path
        config.workspaces.find { name, entryValue ->
            ((Map) entryValue).path?.toString() == target
        }?.key?.toString()
    }

    private static List<String> staleEntries(
        File workspace,
        Map setupManifest,
        File vaultRoot,
        Map vaultManifest,
        List<String> ides
    ) {
        List<String> stale = []
        List planned = SetupMaterialize.planSkillMaterialization(
            workspace,
            vaultRoot,
            vaultManifest,
            ides,
            setupManifest,
            false
        )
        ((List) planned[0]).each { actionValue ->
            Map action = (Map) actionValue
            if (action.disposition == 'update' && !action.skip) {
                stale << action.target.toString()
            }
        }
        stale
    }

    private static Status toolchainOverallStatus(File frameworkRoot, Map wsConfig) {
        Map rules = ai.worklog.framework.core.JsonFiles.read(
            new File(frameworkRoot, 'shared/toolchain-tools.json'),
            [tools: [:], compatibility: [:]]
        )
        Map toolchain = wsConfig.toolchain instanceof Map ? (Map) wsConfig.toolchain : [:]
        ResultSet results = new ResultSet()
        String python = ToolchainCommands.commandVersion(['python3', '--version'])
        results.add(new CheckResult(
            status: python ? Status.READY : Status.BLOCKED,
            source: 'python3',
            message: python ?: 'Not detected'
        ))
        ToolchainCommands.detectJava(toolchain).each { major, runtime ->
            results.add(new CheckResult(status: Status.READY, source: "java:${major}", message: runtime.version))
        }
        ToolchainCommands.detectGroovy(toolchain).each { major, runtime ->
            results.add(new CheckResult(
                status: Status.READY,
                source: "groovy:${major}",
                message: "${runtime.version} @ ${runtime.executable}"
            ))
        }
        ((Map) rules.tools).each { name, ignored ->
            Map resolved = ToolchainCommands.resolve(
                name.toString(),
                rules,
                toolchain,
                ToolchainCommands.detectJava(toolchain),
                ToolchainCommands.detectGroovy(toolchain)
            )
            results.add(new CheckResult(
                status: resolved.ready ? Status.READY : Status.BLOCKED,
                source: "tool:${name}",
                message: resolved.message
            ))
        }
        results.overallStatus()
    }

    private static ResultSet executePreflight(File frameworkRoot, FrameworkPaths paths, Map config) {
        PreflightScope scope = PreflightScope.resolve(frameworkRoot, paths, null, [])
        ResultSet results = new ResultSet()
        if (PreflightCommands.selected(scope, 'workspace')) {
            PreflightCommands.checkWorkspace(results, paths)
        }
        if (scope.checks == null) {
            PreflightCommands.checkBinaries(results, config)
        }
        if (PreflightCommands.selected(scope, 'jira')) {
            PreflightCommands.checkJira(results, paths)
        }
        if (PreflightCommands.selected(scope, 'git')) {
            PreflightCommands.checkCommand(
                results,
                'git',
                ['git', 'config', 'user.email'],
                'No user.email configured',
                false
            )
        }
        if (PreflightCommands.selected(scope, 'github')) {
            PreflightCommands.checkCommand(
                results,
                'github',
                ['gh', 'auth', 'status'],
                'Not authenticated',
                false
            )
        }
        if (PreflightCommands.selected(scope, 'aws')) {
            PreflightCommands.checkAws(results)
        }
        if (PreflightCommands.selected(scope, 'kubectl')) {
            PreflightCommands.checkKubectl(results)
        }
        if (PreflightCommands.selected(scope, 'servicenow')) {
            PreflightCommands.checkServiceNow(results, paths)
        }
        if (PreflightCommands.selected(scope, 'jenkins')) {
            PreflightCommands.checkServiceFile(results, paths, 'jenkins', 'jenkins.properties')
        }
        if (PreflightCommands.selected(scope, 'argocd')) {
            PreflightCommands.checkBinary(results, 'argocd')
        }
        if (PreflightCommands.selected(scope, 'newrelic')) {
            PreflightCommands.checkServiceDirectory(results, paths, 'newrelic')
        }
        if (PreflightCommands.selected(scope, 'datadog')) {
            PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        }
        if (PreflightCommands.selected(scope, 'repositories')) {
            PreflightCommands.checkRepositories(results, paths, scope)
        }
        if (PreflightCommands.selected(scope, 'catalog_binaries')) {
            PreflightCommands.checkCatalogBinaries(results, frameworkRoot, scope)
        }
        if (PreflightCommands.selected(scope, 'toolchain')) {
            PreflightCommands.checkToolchain(results, frameworkRoot, config)
        }
        results
    }
}
