package ai.worklog.framework.commands

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.setup.SetupChecks
import ai.worklog.framework.setup.SetupPlanner
import ai.worklog.framework.setup.SetupReport
import ai.worklog.framework.setup.SetupResolver
import ai.worklog.framework.setup.SetupVault

class WorkspaceCommands {

    private static final Map<String, String> RETIRED_ACTIONS = [
        'init': 'workspace apply',
        'add': 'workspace apply',
        'repair': 'workspace apply',
        'remove': 'workspace revert'
    ]

    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        Map options
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        if (!action) {
            usage()
            return exitCodes.userError
        }
        if (RETIRED_ACTIONS.containsKey(action)) {
            println "workspace ${action} has been removed. " +
                "Use 'ai-worklog ${RETIRED_ACTIONS[action]}' instead."
            return exitCodes.userError
        }
        switch (action) {
            case 'apply':
                return runApply(args, frameworkRoot, options, exitCodes)
            case 'check':
                return runCheck(frameworkRoot, options, args, exitCodes)
            case 'show':
                return runShow(frameworkRoot, options, args, exitCodes)
            case 'revert':
                return runRevert(frameworkRoot, options, args, exitCodes)
            case 'ides':
                return runIdes(frameworkRoot, options, args, exitCodes)
        }
        return runRegistry(action, args, frameworkRoot, options, exitCodes)
    }

    private static int runRegistry(
        String action,
        List<String> args,
        File frameworkRoot,
        Map options,
        ExitCodes exitCodes
    ) {
        List<String> remaining = new ArrayList<>(args)
        boolean json = remaining.remove('--json')
        try {
            Map payload
            switch (action) {
                case 'list':
                    rejectExtraArgs(remaining, action)
                    payload = GlobalConfig.listWorkspaces()
                    break
                case 'default':
                    payload = remaining ?
                        GlobalConfig.setDefaultWorkspace(requireArg(remaining, action)) :
                        GlobalConfig.showDefaultWorkspace()
                    rejectExtraArgs(remaining, action)
                    break
                case 'current':
                    rejectExtraArgs(remaining, action)
                    payload = GlobalConfig.currentWorkspace(
                        options.workspace,
                        options.workspaceName,
                        frameworkRoot
                    )
                    break
                case 'unregister':
                    String name = requireArg(remaining, action)
                    rejectExtraArgs(remaining, action)
                    payload = GlobalConfig.removeWorkspace(name)
                    payload.operation = 'unregister'
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

    private static int runApply(List<String> args, File frameworkRoot, Map options, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        boolean apply = !remaining.remove('--dry-run')
        boolean makeDefault = remaining.remove('--default')
        boolean adopt = remaining.remove('--adopt')
        List<String> ideValues = takeRepeatedOption(remaining, '--ide')
        String explicitRuntime = takeOption(remaining, '--runtime')
        String aiVault = takeOption(remaining, '--ai-vault')

        try {
            Map config = GlobalConfig.load()
            Map workspaces = (Map) (config.workspaces ?: [:])
            String name = null
            String path = null

            if (remaining.size() == 2) {
                name = remaining.remove(0)
                path = remaining.remove(0)
            } else if (remaining.size() == 1) {
                String arg = remaining.remove(0)
                if (workspaces.containsKey(arg)) {
                    name = arg
                    path = ((Map) workspaces[arg]).path
                } else {
                    path = arg
                    name = workspaces.find { k, v -> ((Map) v).path == path }?.key ?: 'workspace'
                }
            } else if (remaining.size() == 0) {
                String explicitName = options?.workspaceName
                String explicitPath = options?.workspace
                if (explicitName && workspaces.containsKey(explicitName)) {
                    name = explicitName
                    path = ((Map) workspaces[explicitName]).path
                } else if (explicitPath) {
                    path = explicitPath
                    name = explicitName ?:
                        SetupChecks.findWorkspaceRegistration(new File(explicitPath)) ?:
                        'workspace'
                } else {
                    File cwd = new File('.').canonicalFile
                    path = cwd.path
                    name = SetupChecks.findWorkspaceRegistration(cwd) ?: cwd.name
                }
            }

            rejectExtraArgs(remaining, 'apply')

            if (!name || !path) {
                throw new IllegalArgumentException('Usage: ai-worklog workspace apply [<name>] [<path>] [-w workspace] [--ide IDE] [--runtime groovy|python] [--ai-vault PATH] [--default] [--json] [--dry-run]')
            }

            GlobalConfig.validateWorkspaceName(name)
            File workspace = GlobalConfig.canonicalWorkspacePath(path)
            if (!workspace.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${path}")
            }
            GlobalConfig.ensureWorkspacePathAvailable(name, workspace.path)

            List vaultResolution = resolveVaultOrError(workspace, aiVault)
            File vaultRoot = vaultResolution[0] as File
            String vaultSource = vaultResolution[1]?.toString()
            Map vaultManifest = vaultResolution[2] as Map

            if (explicitRuntime && !SetupResolver.validateRuntime(explicitRuntime)) {
                throw new IllegalArgumentException("Runtime unavailable: ${explicitRuntime}")
            }
            List runtimeSelection = SetupResolver.resolveRuntimeSelection(explicitRuntime)
            String runtime = explicitRuntime ?: runtimeSelection[0]
            String runtimeSource = explicitRuntime ? 'explicit' : runtimeSelection[1]

            List<String> existingIdes = config.workspaces[name] ?
                ((List) ((Map) config.workspaces[name]).ides)*.toString() :
                []
            List<String> ides = SetupResolver.normalizeIdeSelection(
                SetupResolver.parseIdeArgs(ideValues),
                existingIdes,
                workspace
            )

            Map plan = SetupPlanner.planSetupInit(
                workspace,
                vaultRoot,
                vaultManifest,
                ides,
                adopt,
                frameworkRoot
            )

            Map report = SetupReport.buildActionReport(
                'apply',
                workspace,
                name,
                plan,
                runtime,
                runtimeSource,
                vaultRoot,
                vaultSource,
                ides,
                apply
            )

            if (apply) {
                if (plan.conflicts) {
                    if (!jsonOutput) {
                        renderHumanActionPlan(plan, false, 'apply')
                    }
                    SetupReport.renderReport(report, jsonOutput)
                    return exitCodes.blocked
                }
                try {
                    SetupPlanner.applyInitOrRepairPlan(workspace, name, vaultRoot, ides, plan)
                    config = GlobalConfig.load()
                    boolean defaultWorkspace = makeDefault || !config.default_workspace
                    GlobalConfig.addWorkspace(name, workspace.path, defaultWorkspace)
                    GlobalConfig.setWorkspaceIdes(name, ides)
                    if (explicitRuntime) {
                        GlobalConfig.setRuntime(explicitRuntime)
                    }
                    GlobalConfig.setAiVaultRoot(vaultRoot.path)
                } catch (IOException exception) {
                    if (jsonOutput) {
                        SetupReport.renderReport(report + [status: 'error', message: exception.message], true)
                    } else {
                        println "Workspace operation failed: ${exception.message}"
                    }
                    return exitCodes.systemError
                }
                report.status = 'ready'
                report.message = 'Workspace init complete'
                SetupReport.finalizeAppliedActionReport(report)
            }

            if (!jsonOutput) {
                renderHumanActionPlan(plan, apply, 'apply')
            }

            SetupReport.renderReport(report, jsonOutput, !jsonOutput)
            return SetupReport.exitCodeForReport(report, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'apply', status: 'error', message: exception.message], true)
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runCheck(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        rejectExtraArgs(remaining, 'check')
        try {
            List context = workspaceContext(frameworkRoot, options)
            Map report = SetupReport.buildCheckReport(
                frameworkRoot,
                context[0] as File,
                context[1] as String,
                context[2] as boolean,
                context[3] as boolean
            )
            SetupReport.renderReport(report, jsonOutput)
            return SetupReport.exitCodeForReport(report, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'check', status: 'error', message: exception.message], true)
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runShow(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        String target = takePositional(remaining)
        rejectExtraArgs(remaining, 'show')
        try {
            List context = workspaceContext(frameworkRoot, options, target)
            Map report = SetupReport.buildShowReport(
                context[0] as File,
                context[1] as String,
                context[2] as boolean,
                context[3] as boolean
            )
            SetupReport.renderReport(report, jsonOutput)
            return exitCodes.success
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'show', status: 'error', message: exception.message], true)
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runRevert(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        boolean apply = !remaining.remove('--dry-run')
        try {
            List<String> filterIdes = SetupResolver.parseIdeArgs(takeRepeatedOption(remaining, '--ide'))
            String target = takePositional(remaining)
            rejectExtraArgs(remaining, 'revert')
            if (filterIdes && filterIdes.contains('auto')) {
                throw new IllegalArgumentException('--ide auto cannot be used with revert')
            }

            List context = workspaceContext(frameworkRoot, options, target)
            File workspace = context[0] as File
            String name = context[1] as String
            if (!context[2]) {
                throw unregisteredError(context[0] as File, context[4] as String)
            }
            List<String> aliases = GlobalConfig.workspaceNamesForPath(
                GlobalConfig.load(),
                workspace.path
            ).findAll { it != name }
            if (aliases) {
                throw new IllegalArgumentException(
                    "Workspace path is also registered as ${aliases.join(', ')}; " +
                    "unregister duplicate names before reverting ${name}"
                )
            }

            List vaultResolution = SetupResolver.resolveAiVaultRoot(workspace)
            File vaultRoot = vaultResolution[0] as File
            String vaultSource = vaultResolution[1]?.toString()
            List runtimeSelection = SetupResolver.resolveRuntimeSelection()
            Map plan = SetupPlanner.planSetupRevert(workspace, filterIdes, frameworkRoot)
            Map config = GlobalConfig.load()
            List<String> ides = ((List) ((Map) config.workspaces[name]).ides)*.toString()

            Map report = SetupReport.buildActionReport(
                'revert',
                workspace,
                name,
                plan,
                runtimeSelection[0],
                runtimeSelection[1],
                vaultRoot,
                vaultSource,
                ides,
                apply
            )

            if (apply) {
                try {
                    SetupPlanner.applyRevertPlan(workspace, name, vaultRoot, plan)
                    GlobalConfig.setWorkspaceIdes(name, (List) (plan.remaining_ides ?: []))
                    if (!filterIdes) {
                        GlobalConfig.removeWorkspace(name)
                    }
                } catch (IOException exception) {
                    if (jsonOutput) {
                        SetupReport.renderReport(report + [status: 'error', message: exception.message], true)
                    } else {
                        println "Workspace operation failed: ${exception.message}"
                    }
                    return exitCodes.systemError
                }
                report.status = 'ready'
                report.message = filterIdes ?
                    'Workspace revert complete' :
                    'Workspace revert complete; registration removed'
                SetupReport.finalizeAppliedActionReport(report)
            }

            if (!jsonOutput) {
                renderHumanActionPlan(plan, apply, 'revert')
            }

            SetupReport.renderReport(report, jsonOutput, !jsonOutput)
            return SetupReport.exitCodeForReport(report, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'revert', status: 'error', message: exception.message], true)
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runIdes(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        try {
            List context = workspaceContext(frameworkRoot, options)
            File workspace = context[0] as File
            String name = context[1] as String
            if (!context[2]) {
                throw unregisteredError(context[0] as File, context[4] as String)
            }

            if (!remaining) {
                Map config = GlobalConfig.load()
                List<String> current = ((List) ((Map) config.workspaces[name]).ides)*.toString()
                Map payload = [
                    operation: 'ides',
                    status: 'ok',
                    name: name,
                    ides: current
                ]
                if (jsonOutput) {
                    GlobalConfig.printJson(payload)
                } else {
                    renderIdes(payload, false)
                }
                return exitCodes.success
            }

            List<String> requested = new ArrayList<>(remaining)
            remaining.clear()
            List<String> ides = SetupResolver.normalizeIdeSelection(requested, [], workspace)
            Map payload = GlobalConfig.setWorkspaceIdes(name, ides)
            if (jsonOutput) {
                GlobalConfig.printJson(payload)
            } else {
                renderIdes(payload, true)
            }
            return exitCodes.success
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                GlobalConfig.printJson([
                    operation: 'ides',
                    status: 'error',
                    message: exception.message
                ])
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static void renderIdes(Map payload, boolean changed) {
        List ides = payload.ides instanceof List ? (List) payload.ides*.toString() : []
        println "IDEs for ${payload.name}: ${ides ? ides.join(', ') : 'none'}"
        if (changed) {
            println "Run 'ai-worklog workspace apply' to materialize."
        }
    }

    private static void renderHumanActionPlan(Map plan, boolean apply, String operation) {
        println "Workspace ${operation}"
        SetupPlanner.printCompactActionPlan(plan, apply)
        SetupPlanner.printActionConflicts((List) (plan.conflicts ?: []))
        println()
    }

    private static List workspaceContext(File frameworkRoot, Map options, String positional = null) {
        File workspace
        String name = null
        String source
        if (positional) {
            Map configData = GlobalConfig.load()
            Map workspaces = (Map) (configData.workspaces ?: [:])
            String target = positional
            source = 'explicit_path'
            if (workspaces.containsKey(positional)) {
                name = positional
                target = ((Map) workspaces[positional]).path
                source = 'workspace_name'
            }
            workspace = GlobalConfig.canonicalWorkspacePath(target)
            if (!workspace.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${positional}")
            }
        } else {
            Map resolved = GlobalConfig.resolveWorkspaceSelection(
                options.workspace,
                options.workspaceName,
                frameworkRoot
            )
            workspace = resolved.path as File
            name = resolved.name?.toString()
            source = resolved.source?.toString()
        }
        if (!name) {
            name = SetupChecks.findWorkspaceRegistration(workspace)
        }
        Map config = GlobalConfig.load()
        boolean registered = name && config.workspaces[name]
        boolean isDefault = registered && config.default_workspace == name
        [workspace, name, registered, isDefault, source]
    }

    private static IllegalArgumentException unregisteredError(File workspace, String source) {
        String message = "Workspace is not registered: ${workspace.path} (source: ${source})"
        if (source == 'cwd_legacy') {
            message += ". Directory is not initialized; run 'workspace init' to initialize it."
        }
        new IllegalArgumentException(message)
    }

    private static List resolveVaultOrError(File workspace, String cliOverride) {
        List resolution = SetupResolver.resolveAiVaultRoot(workspace, cliOverride)
        File vaultRoot = resolution[0] as File
        String vaultSource = resolution[1]?.toString()
        if (!vaultRoot) {
            throw new IllegalArgumentException('AI vault not found')
        }
        List validation = SetupVault.validateVaultRoot(vaultRoot)
        if (!validation[0]) {
            throw new IllegalArgumentException(validation[1]?.toString())
        }
        [vaultRoot, vaultSource ?: 'unknown', validation[2]]
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
            case 'unregister':
                println "Removed workspace registration: ${payload.name}"
                break
        }
    }

    private static String takePositional(List<String> args) {
        if (!args || args[0].startsWith('-')) {
            return null
        }
        args.remove(0)
    }

    private static String requireArg(List<String> remaining, String action) {
        if (!remaining) {
            throw new IllegalArgumentException("Missing value for workspace ${action}")
        }
        remaining.remove(0)
    }

    private static String takeOption(List<String> args, String name) {
        int index = args.indexOf(name)
        if (index < 0) {
            return null
        }
        if (index + 1 >= args.size() || args[index + 1].startsWith('-')) {
            throw new IllegalArgumentException("Missing value for ${name}")
        }
        String value = args[index + 1]
        args.remove(index + 1)
        args.remove(index)
        value
    }

    private static List<String> takeRepeatedOption(List<String> args, String name) {
        List<String> values = []
        while (true) {
            int index = args.indexOf(name)
            if (index < 0) {
                break
            }
            if (index + 1 >= args.size() || args[index + 1].startsWith('-')) {
                throw new IllegalArgumentException("Missing value for ${name}")
            }
            values << args[index + 1]
            args.remove(index + 1)
            args.remove(index)
        }
        values
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
        println 'Usage: ai-worklog workspace {apply|check|show|revert|unregister|ides|list|default|current} ...'
    }
}
