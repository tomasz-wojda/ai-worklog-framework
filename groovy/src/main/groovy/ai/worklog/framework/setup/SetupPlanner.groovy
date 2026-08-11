package ai.worklog.framework.setup

import ai.worklog.framework.workspace.WorkspacePlanner

class SetupPlanner {
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
        [
            workspace_actions: planner.planInit(workspace),
            skill_actions: planned[0],
            conflicts: planned[1],
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
            service_actions: planner.planRevert(workspace),
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
                new File(workspace, profile.destination)
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
