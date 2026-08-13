package ai.worklog.framework.setup

import ai.worklog.framework.workspace.WorkspacePlanner

class SetupPlanner {
    private static final List<String> ACTION_PLAN_KEYS = [
        'workspace_actions', 'service_actions', 'skill_actions'
    ]
    private static final int LABEL_WIDTH = 17

    static List<Map> collectPlanActions(Map plan) {
        List<Map> actions = []
        ACTION_PLAN_KEYS.each { key ->
            ((List) (plan[key] ?: [])).each { actionValue ->
                actions << (Map) actionValue
            }
        }
        actions
    }

    static boolean setupUseColor() {
        if (System.getenv('NO_COLOR')) {
            return false
        }
        if (System.getProperty('os.name')?.toLowerCase()?.contains('win')) {
            String term = System.getenv('TERM') ?: ''
            String wt = System.getenv('WT_SESSION') ?: ''
            String ansicon = System.getenv('ANSICON') ?: ''
            String conemu = System.getenv('ConEmuANSI') ?: ''
            if (!wt && !ansicon && !conemu && (term == '' || term == 'dumb')) {
                return false
            }
        }
        return (System.getenv('TERM') ?: '') != 'dumb'
    }

    static boolean isUtf8Console() {
        String encoding = java.nio.charset.Charset.defaultCharset().name()
        if (encoding != null && encoding.equalsIgnoreCase('UTF-8')) {
            return true
        }
        String fileEnc = System.getProperty('file.encoding')
        if (fileEnc != null && fileEnc.equalsIgnoreCase('UTF-8')) {
            return true
        }
        return (System.getenv('LANG') ?: '').toUpperCase().contains('UTF-8')
    }

    static void setupPrintRow(String label, String detail, boolean ok = true) {
        boolean useColor = setupUseColor()
        boolean isUtf8 = isUtf8Console()
        String markSymbol = ok ? (isUtf8 ? '✓' : '[OK]') : (isUtf8 ? '✗' : '[FAIL]')
        String mark = ok ? (useColor ? "\u001B[32m${markSymbol}\u001B[0m" : markSymbol) :
            (useColor ? "\u001B[31m${markSymbol}\u001B[0m" : markSymbol)
        String dim = useColor ? '\u001B[2m' : ''
        String reset = useColor ? '\u001B[0m' : ''
        println "  ${mark} ${label.padRight(LABEL_WIDTH)} ${dim}${detail}${reset}"
    }

    static String formatActionDetail(Map action) {
        String kind = action.kind?.toString()
        Object target = action.target
        Object source = action.source
        String reason = action.reason?.toString()
        String suffix = reason ? " (${reason})" : ''
        String detail
        switch (kind) {
            case 'mkdir':
                detail = "mkdir ${target}"
                break
            case 'copy':
                detail = "copy ${source} -> ${target}"
                break
            case 'symlink':
                Object resolvedSource = source instanceof File ? ((File) source).canonicalFile : source
                detail = "link ${target} -> ${resolvedSource}"
                break
            case 'remove':
                detail = "remove ${target}"
                break
            case 'unlink':
                detail = "unlink ${target}"
                break
            case 'rmdir':
                detail = "rmdir ${target}"
                break
            default:
                detail = "${kind} ${target}"
        }
        "${detail}${suffix}"
    }

    static void printCompactActions(List<Map> actions, boolean apply) {
        List<Map> skipped = actions.findAll { it.skip }
        List<Map> active = actions.findAll { !it.skip }
        if (skipped) {
            String noun = skipped.size() == 1 ? 'action' : 'actions'
            setupPrintRow('Skipped', "${skipped.size()} ${noun}")
        }
        active.each { Map action ->
            println "      ${formatActionDetail(action)}"
        }
        if (active && !apply) {
            String noun = active.size() == 1 ? 'action' : 'actions'
            String message = "${active.size()} pending ${noun}. Re-run with --apply to make changes."
            if (setupUseColor()) {
                println "\n  \u001B[2m${message}\u001B[0m"
            } else {
                println "\n  ${message}"
            }
        }
    }

    static void printCompactActionPlan(Map plan, boolean apply) {
        printCompactActions(collectPlanActions(plan), apply)
    }

    static void printActionConflicts(List<Map> conflicts) {
        conflicts.each { Map conflict ->
            setupPrintRow('Conflict', "${conflict.path} (${conflict.reason})", false)
        }
    }

    static Map planSetupInit(
        File workspace,
        File vaultRoot,
        Map vaultManifest,
        List<String> ides,
        boolean adopt,
        File frameworkRoot
    ) {
        WorkspacePlanner planner = new WorkspacePlanner(frameworkRoot)
        Map existingManifest = SetupManifest.loadManifest(workspace)
        List planned = SetupMaterialize.planSkillMaterialization(
            workspace,
            vaultRoot,
            vaultManifest,
            ides,
            existingManifest,
            adopt
        )
        Map initPlan = planner.planInit(workspace)
        [
            workspace_actions: initPlan.actions,
            skill_actions: planned[0],
            conflicts: ((List) planned[1]) + ((List) (initPlan.conflicts ?: [])),
            skill_records: planned[2],
            existing_manifest: existingManifest
        ]
    }

    static Map planSetupRepair(
        File workspace,
        File vaultRoot,
        Map vaultManifest,
        List<String> ides,
        boolean adopt,
        File frameworkRoot
    ) {
        planSetupInit(workspace, vaultRoot, vaultManifest, ides, adopt, frameworkRoot)
    }

    static Map planSetupRevert(File workspace, List<String> ides, File frameworkRoot) {
        Map existingManifest = SetupManifest.loadManifest(workspace)
        WorkspacePlanner planner = new WorkspacePlanner(frameworkRoot)
        List<Map> skillActions = []
        List<Map> conflicts = []
        List<Map> remainingSkills = []
        Set<String> targetIdes = ides ? ides as Set : null

        if (existingManifest) {
            ((List) existingManifest.skills).each { entryValue ->
                Map entry = (Map) entryValue
                if (targetIdes != null && !(entry.ide?.toString() in targetIdes)) {
                    remainingSkills << entry
                    return
                }
                File destination = new File(entry.destination.toString())
                List managed = SetupMaterialize.canRemoveSkillArtifact(destination, entry)
                Map action = [
                    kind: 'remove',
                    ide: entry.ide,
                    skill: entry.name,
                    target: destination,
                    skip: !managed[0],
                    reason: managed[1]?.toString() ?: ''
                ]
                if (!managed[0] && destination.exists()) {
                    conflicts << [path: destination.path, reason: managed[1]?.toString() ?: '']
                }
                skillActions << action
            }
        }

        List<String> remainingIdes = remainingSkills
            .findAll { it.ide }
            .collect { it.ide.toString() }
            .unique()
            .sort()

        [
            service_actions: planner.planRevert(workspace).actions,
            skill_actions: skillActions,
            conflicts: conflicts,
            remaining_skills: remainingSkills,
            remaining_ides: remainingIdes,
            existing_manifest: existingManifest
        ]
    }

    static void applyInitOrRepairPlan(
        File workspace,
        String workspaceName,
        File vaultRoot,
        List<String> ides,
        Map plan
    ) {
        WorkspacePlanner.apply((List) plan.workspace_actions)
        ((List) plan.skill_actions).each { SetupMaterialize.applySkillAction((Map) it) }

        List<Map> skillRecords = new ArrayList<>((List) (plan.skill_records ?: []))
        ((List) plan.skill_actions).each { actionValue ->
            Map action = (Map) actionValue
            String applied = action.applied_checksum?.toString()
            if (!applied || action.skip) {
                return
            }
            String target = ((File) action.target).canonicalFile.path
            skillRecords.each { Map record ->
                if (record.destination == target) {
                    record.applied_checksum = applied
                }
            }
        }

        SetupManifest.saveManifest(
            workspace,
            SetupManifest.composeManifest(workspaceName, vaultRoot, ides, skillRecords)
        )
    }

    static void applyRevertPlan(
        File workspace,
        String workspaceName,
        File vaultRoot,
        Map plan
    ) {
        WorkspacePlanner.apply((List) plan.service_actions)
        ((List) plan.skill_actions).each { actionValue ->
            Map action = (Map) actionValue
            if (action.skip) {
                return
            }
            Map entry = manifestEntry(plan.existing_manifest as Map, action)
            if (!entry) {
                return
            }
            File destination = (File) action.target
            SetupMaterialize.removeSkillArtifact(destination, entry)
            Map profile = SetupResolver.ideMaterialization(action.ide.toString())
            SetupMaterialize.cleanupEmptyParent(
                destination.parentFile,
                workspace
            )
        }

        List<Map> remaining = new ArrayList<>((List) (plan.remaining_skills ?: []))
        File path = SetupManifest.manifestPath(workspace)
        if (remaining && vaultRoot) {
            SetupManifest.saveManifest(
                workspace,
                SetupManifest.composeManifest(
                    workspaceName,
                    vaultRoot,
                    (List) (plan.remaining_ides ?: []),
                    remaining
                )
            )
        } else if (path.isFile()) {
            path.delete()
            SetupMaterialize.cleanupEmptyParent(path.parentFile, workspace)
        }
    }

    static int pendingActionCount(Map plan) {
        int total = 0
        ['workspace_actions', 'service_actions', 'skill_actions'].each { key ->
            ((List) (plan[key] ?: [])).each { actionValue ->
                if (!((Map) actionValue).skip) {
                    total++
                }
            }
        }
        total
    }

    static String formatFilesystemAction(Map action, boolean apply) {
        String prefix
        if (action.skip) {
            prefix = 'skipped:'
        } else if (apply) {
            prefix = 'run:'
        } else {
            prefix = 'would:'
        }
        String kind = action.kind?.toString()
        Object target = action.target
        Object source = action.source
        String reason = action.reason?.toString()
        String suffix = reason ? " (${reason})" : ''
        String detail
        switch (kind) {
            case 'mkdir':
                detail = "mkdir ${target}"
                break
            case 'copy':
                detail = "copy ${source} -> ${target}"
                break
            case 'symlink':
                Object resolvedSource = source instanceof File ? ((File) source).canonicalFile : source
                detail = "link ${target} -> ${resolvedSource}"
                break
            case 'remove':
                detail = "remove ${target}"
                break
            case 'unlink':
                detail = "unlink ${target}"
                break
            default:
                detail = "${kind} ${target}"
        }
        "${prefix} ${detail}${suffix}"
    }

    private static Map manifestEntry(Map manifest, Map action) {
        if (!manifest) {
            return null
        }
        ((List) manifest.skills).find { entryValue ->
            Map entry = (Map) entryValue
            entry.name?.toString() == action.skill?.toString() &&
                entry.ide?.toString() == action.ide?.toString()
        }
    }
}
