package ai.worklog.framework.commands

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.workspace.WorkspacePlanner

class WorkspaceCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        String explicitPath = null,
        String explicitName = null
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        if (!action) {
            usage()
            return exitCodes.userError
        }
        if (action in ['init', 'revert']) {
            return runPlanner(action, args, frameworkRoot, exitCodes)
        }
        List<String> remaining = new ArrayList<>(args)
        boolean json = remaining.remove('--json')
        try {
            Map payload
            switch (action) {
                case 'add':
                    payload = runAdd(remaining)
                    break
                case 'list':
                    rejectExtraArgs(remaining, action)
                    payload = GlobalConfig.listWorkspaces()
                    break
                case 'show':
                    payload = GlobalConfig.showWorkspace(requireArg(remaining, action))
                    rejectExtraArgs(remaining, action)
                    break
                case 'default':
                    payload = remaining ?
                        GlobalConfig.setDefaultWorkspace(requireArg(remaining, action)) :
                        GlobalConfig.showDefaultWorkspace()
                    rejectExtraArgs(remaining, action)
                    break
                case 'current':
                    rejectExtraArgs(remaining, action)
                    payload = GlobalConfig.currentWorkspace(explicitPath, explicitName, frameworkRoot)
                    break
                case 'remove':
                    payload = GlobalConfig.removeWorkspace(requireArg(remaining, action))
                    rejectExtraArgs(remaining, action)
                    break
                default:
                    usage()
                    return exitCodes.userError
            }
            return render(payload, json, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (json) {
                GlobalConfig.printJson([
                    operation: action,
                    status: 'error',
                    message: exception.message
                ])
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runPlanner(
        String action,
        List<String> args,
        File frameworkRoot,
        ExitCodes exitCodes
    ) {
        if (!args) {
            usage()
            return exitCodes.userError
        }
        List<String> remaining = new ArrayList<>(args)
        boolean applying = remaining.remove('--apply')
        File workspace = GlobalConfig.canonicalWorkspacePath(remaining[0])
        if (remaining.size() > 1) {
            throw new IllegalArgumentException('Unexpected arguments for workspace operation')
        }
        if (!workspace.isDirectory()) {
            println "Workspace not found: ${remaining[0]}"
            return exitCodes.userError
        }
        WorkspacePlanner planner = new WorkspacePlanner(frameworkRoot)
        List<Map> actions = action == 'init' ?
            planner.planInit(workspace) : planner.planRevert(workspace)
        actions.each { println WorkspacePlanner.format(it, applying) }
        if (!applying) {
            println 'Dry run only. Re-run with --apply to make changes.'
            return exitCodes.success
        }
        try {
            WorkspacePlanner.apply(actions)
        } catch (IOException exception) {
            println "Workspace operation failed: ${exception.message}"
            return exitCodes.systemError
        }
        println 'Workspace operation complete.'
        exitCodes.success
    }

    private static Map runAdd(List<String> remaining) {
        if (remaining.size() < 2) {
            throw new IllegalArgumentException('Usage: ai-worklog workspace add <name> <path> [--default]')
        }
        String name = remaining.remove(0)
        String path = remaining.remove(0)
        boolean makeDefault = remaining.remove('--default')
        if (remaining) {
            throw new IllegalArgumentException('Unexpected arguments for workspace add')
        }
        return GlobalConfig.addWorkspace(name, path, makeDefault)
    }

    private static int render(Map payload, boolean json, ExitCodes exitCodes) {
        if (json) {
            GlobalConfig.printJson(payload)
        } else {
            renderHuman(payload)
        }
        exitCodes.success
    }

    private static void renderHuman(Map payload) {
        switch (payload.operation) {
            case 'add':
                println "Registered workspace ${payload.name}: ${payload.path}"
                if (payload.default) {
                    println "Default workspace: ${payload.name}"
                }
                if (payload.unchanged) {
                    println 'No changes required.'
                }
                break
            case 'list':
                List workspaces = (List) payload.workspaces
                println "Registered workspaces (${workspaces.size()}):"
                if (!workspaces) {
                    println '  none'
                } else {
                    workspaces.each { Map entry ->
                        println "  ${entry.name}  ${entry.path}${availabilitySuffix(entry)}${defaultSuffix(entry)}"
                    }
                }
                break
            case 'show':
                println "Workspace ${payload.name}: ${payload.path}${availabilitySuffix(payload)}${defaultSuffix(payload)}"
                break
            case 'default':
                println payload.name ?
                    "Default workspace: ${payload.name}" :
                    'Default workspace: none'
                break
            case 'current':
                println "Workspace: ${payload.path}"
                println "Source: ${payload.source}"
                if (payload.name) {
                    println "Name: ${payload.name}"
                }
                break
            case 'remove':
                println "Removed workspace registration: ${payload.name}"
                break
        }
    }

    private static String requireArg(List<String> remaining, String action) {
        if (!remaining) {
            throw new IllegalArgumentException("Missing value for workspace ${action}")
        }
        remaining.remove(0)
    }

    private static void rejectExtraArgs(List<String> remaining, String action) {
        if (remaining) {
            throw new IllegalArgumentException("Unexpected arguments for workspace ${action}")
        }
    }

    private static String availabilitySuffix(Map entry) {
        entry.available ? ' [available]' : ' [missing]'
    }

    private static String defaultSuffix(Map entry) {
        entry.default ? ' [default]' : ''
    }

    private static void usage() {
        println 'Usage: ai-worklog workspace {init|revert|add|list|show|default|current|remove} ...'
    }
}
