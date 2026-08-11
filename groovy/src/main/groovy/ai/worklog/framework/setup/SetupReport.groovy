package ai.worklog.framework.setup

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.core.Status

class SetupReport {
    private static Map manifestSummary(Map manifest) {
        Map summary = [skill_count: manifest ? ((List) manifest.skills).size() : 0]
        if (manifest) {
            summary.version = manifest.version
            summary.synced_at = manifest.synced_at
        }
        summary
    }

    static Map buildShowReport(
        File workspace,
        String workspaceName,
        boolean registered,
        boolean isDefault
    ) {
        List runtimeSelection = SetupResolver.resolveRuntimeSelection()
        List vaultResolution = SetupResolver.resolveAiVaultRoot(workspace)
        File vaultRoot = vaultResolution[0] as File
        String vaultSource = vaultResolution[1]?.toString()
        boolean vaultValid = false
        if (vaultRoot) {
            vaultValid = SetupVault.validateVaultRoot(vaultRoot)[0]
        }

        Map manifest = SetupManifest.loadManifest(workspace)
        List<String> ides = []
        if (workspaceName) {
            Map config = GlobalConfig.load()
            Map entry = config.workspaces[workspaceName]
            if (entry) {
                ides = ((List) ((Map) entry).ides)*.toString()
            }
        }
        if (!ides && manifest) {
            ides = ((List) manifest.ides)*.toString()
        }

        List<Map> ideProfiles = []
        List<Map> conflicts = []
        ides.each { ide ->
            Map profile = SetupResolver.ideMaterialization(ide)
            int managedCount = 0
            if (manifest) {
                managedCount = ((List) manifest.skills).count { ((Map) it).ide?.toString() == ide }
            }
            ideProfiles << [
                id: ide,
                materialization: profile.materialization,
                managed_count: managedCount,
                conflict_count: 0
            ]
        }

        int pending = 0
        if (vaultRoot && ides) {
            List vaultValidation = SetupVault.validateVaultRoot(vaultRoot)
            if (vaultValidation[0]) {
                List planned = SetupMaterialize.planSkillMaterialization(
                    workspace,
                    vaultRoot,
                    (Map) vaultValidation[2],
                    ides,
                    manifest,
                    false
                )
                conflicts = (List) planned[1]
                pending = conflicts.size()
                ideProfiles.each { Map profile ->
                    Map ideProfile = SetupResolver.ideMaterialization(profile.id.toString())
                    String destinationPrefix = new File(workspace, ideProfile.destination).path
                    profile.conflict_count = conflicts.count { it.path?.toString()?.startsWith(destinationPrefix) }
                }
            }
        }

        [
            operation: 'show',
            status: Status.READY.value,
            message: 'Setup summary',
            workspace: [
                name: workspaceName,
                path: workspace.canonicalFile.path,
                default: isDefault,
                registered: registered,
                available: workspace.isDirectory()
            ],
            runtime: [
                value: runtimeSelection[0],
                source: runtimeSelection[1],
                available: runtimeSelection[2]
            ],
            ai_vault: [
                path: vaultRoot?.path,
                source: vaultSource,
                valid: vaultValid
            ],
            ides: ideProfiles,
            conflicts: conflicts,
            pending_actions: pending,
            manifest: manifestSummary(manifest)
        ]
    }

    static Map buildCheckReport(
        File frameworkRoot,
        File workspace,
        String workspaceName,
        boolean registered,
        boolean isDefault
    ) {
        List<Map> checks = SetupChecks.runSetupChecks(
            frameworkRoot,
            workspace,
            workspaceName,
            true
        )
        Status status = SetupChecks.aggregateCheckStatus(checks)
        List runtimeSelection = SetupResolver.resolveRuntimeSelection()
        List vaultResolution = SetupResolver.resolveAiVaultRoot(workspace)
        File vaultRoot = vaultResolution[0] as File
        String vaultSource = vaultResolution[1]?.toString()
        boolean vaultValid = false
        if (vaultRoot) {
            vaultValid = SetupVault.validateVaultRoot(vaultRoot)[0]
        }

        Map manifest = SetupManifest.loadManifest(workspace)
        List<String> ides = []
        if (workspaceName) {
            Map config = GlobalConfig.load()
            Map entry = config.workspaces[workspaceName]
            if (entry) {
                ides = ((List) ((Map) entry).ides)*.toString()
            }
        }

        [
            operation: 'check',
            status: status.value,
            message: "Setup check: ${status.value}",
            workspace: [
                name: workspaceName,
                path: workspace.canonicalFile.path,
                default: isDefault,
                registered: registered,
                available: workspace.isDirectory()
            ],
            runtime: [
                value: runtimeSelection[0],
                source: runtimeSelection[1],
                available: runtimeSelection[2]
            ],
            ai_vault: [
                path: vaultRoot?.path,
                source: vaultSource,
                valid: vaultValid
            ],
            ides: ides.collect { ide ->
                Map profile = SetupResolver.ideMaterialization(ide)
                [
                    id: ide,
                    destination: profile.destination,
                    materialization: profile.materialization,
                    managed_count: 0,
                    conflict_count: 0
                ]
            },
            checks: checks,
            conflicts: [],
            pending_actions: 0,
            manifest: manifestSummary(manifest)
        ]
    }

    static Map buildActionReport(
        String operation,
        File workspace,
        String workspaceName,
        Map plan,
        String runtime,
        String runtimeSource,
        File vaultRoot,
        String vaultSource,
        List<String> ides,
        boolean apply
    ) {
        List<Map> actions = []
        ['workspace_actions', 'service_actions', 'skill_actions'].each { key ->
            ((List) (plan[key] ?: [])).each { actionValue ->
                Map action = (Map) actionValue
                actions << [
                    kind: action.kind,
                    target: action.target.toString(),
                    source: action.source ? action.source.toString() : null,
                    skip: action.skip == true,
                    reason: action.reason?.toString() ?: ''
                ]
            }
        }

        List conflicts = new ArrayList<>((List) (plan.conflicts ?: []))
        int pending = SetupPlanner.pendingActionCount(plan)
        String status
        if (conflicts && operation in ['init', 'repair']) {
            status = Status.BLOCKED.value
        } else if (pending && !apply) {
            status = Status.DEGRADED.value
        } else if (pending && apply) {
            status = Status.READY.value
        } else {
            status = Status.READY.value
        }

        [
            operation: operation,
            status: status,
            message: "Setup ${operation} ${apply ? 'applied' : 'planned'}",
            workspace: [
                name: workspaceName,
                path: workspace.canonicalFile.path,
                default: false,
                registered: true,
                available: workspace.isDirectory()
            ],
            runtime: [
                value: runtime,
                source: runtimeSource,
                available: true
            ],
            ai_vault: [
                path: vaultRoot?.path,
                source: vaultSource,
                valid: vaultRoot != null
            ],
            ides: ides.collect { ide ->
                Map profile = SetupResolver.ideMaterialization(ide)
                [
                    id: ide,
                    destination: profile.destination,
                    materialization: profile.materialization,
                    managed_count: 0,
                    conflict_count: 0
                ]
            },
            actions: actions,
            conflicts: conflicts,
            pending_actions: pending,
            applied_actions: 0,
            skipped_actions: actions.count { Map action -> action.skip }
        ]
    }

    private static final Set<String> ACTION_OPERATIONS = ['init', 'repair', 'revert'] as Set

    static void finalizeAppliedActionReport(Map report) {
        List actions = (List) (report.actions ?: [])
        int applied = actions.count { Map action -> !action.skip }
        int skipped = actions.count { Map action -> action.skip }
        report.applied_actions = applied
        report.skipped_actions = skipped
        report.pending_actions = 0
    }

    private static void renderActionFooter(Map report) {
        int applied = report.applied_actions instanceof Number ? report.applied_actions as int : 0
        int skipped = report.skipped_actions instanceof Number ?
            report.skipped_actions as int :
            ((List) (report.actions ?: [])).count { Map action -> action.skip }
        String status = report.status?.toString()?.toUpperCase() ?: 'READY'
        boolean useColor = SetupPlanner.setupUseColor()
        String color
        if (useColor) {
            if (status == 'READY') {
                color = '\u001B[32m'
            } else if (status in ['BLOCKED', 'ERROR']) {
                color = '\u001B[31m'
            } else {
                color = '\u001B[33m'
            }
        } else {
            color = ''
        }
        String reset = useColor ? '\u001B[0m' : ''
        println "\n${color}${status}${reset}  ${applied} applied · ${skipped} skipped"
    }

    static void renderReport(Map report, boolean jsonOutput, boolean actionsPrinted = false) {
        if (jsonOutput) {
            GlobalConfig.printJson(report)
            return
        }
        println "Setup ${report.operation}: ${report.status}"
        Map workspace = report.workspace instanceof Map ? (Map) report.workspace : [:]
        if (workspace) {
            println "  Workspace: ${workspace.name ?: workspace.path}"
        }
        Map runtime = report.runtime instanceof Map ? (Map) report.runtime : [:]
        if (runtime) {
            println "  Runtime: ${runtime.value} (${runtime.source})"
        }
        Map vault = report.ai_vault instanceof Map ? (Map) report.ai_vault : [:]
        if (vault.path) {
            println "  AI vault: ${vault.path} (${vault.source})"
        }
        ((List) (report.checks ?: [])).each { checkValue ->
            Map check = (Map) checkValue
            println "  [${check.status.toUpperCase()}] ${check.layer}: ${check.message}"
        }
        String operation = report.operation?.toString()
        if (operation in ACTION_OPERATIONS && actionsPrinted) {
            ((List) (report.conflicts ?: [])).each { conflictValue ->
                Map conflict = (Map) conflictValue
                SetupPlanner.setupPrintRow('Conflict', "${conflict.path} (${conflict.reason})", false)
            }
            renderActionFooter(report)
            return
        }
        if (operation in ACTION_OPERATIONS) {
            SetupPlanner.printCompactActions((List) (report.actions ?: []), false)
            ((List) (report.conflicts ?: [])).each { conflictValue ->
                Map conflict = (Map) conflictValue
                SetupPlanner.setupPrintRow('Conflict', "${conflict.path} (${conflict.reason})", false)
            }
            return
        }
        ((List) (report.conflicts ?: [])).each { conflictValue ->
            Map conflict = (Map) conflictValue
            println "  conflict: ${conflict.path} (${conflict.reason})"
        }
        int pendingActions = report.pending_actions instanceof Number ? report.pending_actions as int : 0
        if (pendingActions) {
            println "  Pending actions: ${pendingActions}"
        }
    }

    static int exitCodeForReport(Map report, ExitCodes exitCodes) {
        String status = report.status?.toString()
        String operation = report.operation?.toString()
        if (status == Status.ERROR.value) {
            return exitCodes.systemError
        }
        if (status == Status.BLOCKED.value) {
            return exitCodes.blocked
        }
        if (status == Status.DEGRADED.value) {
            return operation == 'check' ? exitCodes.userError : exitCodes.success
        }
        exitCodes.success
    }
}
