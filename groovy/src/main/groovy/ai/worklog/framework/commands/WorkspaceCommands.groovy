package ai.worklog.framework.commands

import ai.worklog.framework.workspace.WorkspacePlanner

class WorkspaceCommands {
    static int run(String action, List<String> args, File frameworkRoot) {
        if (!(action in ['init', 'revert']) || !args) {
            println 'Usage: ai-worklog workspace {init|revert} <path> [--apply]'
            return 1
        }
        boolean applying = args.remove('--apply')
        File workspace = new File(args[0]).canonicalFile
        if (!workspace.isDirectory()) {
            println "Workspace not found: ${workspace}"
            return 1
        }

        WorkspacePlanner planner = new WorkspacePlanner(frameworkRoot)
        List<Map> actions = action == 'init' ?
            planner.planInit(workspace) : planner.planRevert(workspace)
        actions.each { println WorkspacePlanner.format(it, applying) }
        if (!applying) {
            println 'Dry run only. Re-run with --apply to make changes.'
            return 0
        }
        try {
            WorkspacePlanner.apply(actions)
        } catch (IOException exception) {
            println "Workspace operation failed: ${exception.message}"
            return 2
        }
        println 'Workspace operation complete.'
        0
    }
}
