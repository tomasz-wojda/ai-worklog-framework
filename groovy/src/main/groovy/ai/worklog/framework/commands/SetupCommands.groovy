package ai.worklog.framework.commands

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.setup.SetupChecks
import ai.worklog.framework.setup.SetupPlanner
import ai.worklog.framework.setup.SetupReport
import ai.worklog.framework.setup.SetupResolver
import ai.worklog.framework.setup.SetupVault

class SetupCommands {
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
        switch (action) {
            case 'init':
                return runInit(args, frameworkRoot, options, exitCodes)
            case 'check':
                return runCheck(frameworkRoot, options, args, exitCodes)
            case 'show':
                return runShow(frameworkRoot, options, args, exitCodes)
            case 'repair':
                return runRepair(frameworkRoot, options, args, exitCodes)
            case 'revert':
                return runRevert(frameworkRoot, options, args, exitCodes)
            default:
                usage()
                return exitCodes.userError
        }
    }

    private static int runInit(List<String> args, File frameworkRoot, Map options, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        boolean apply = remaining.remove('--apply')
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
                    name = explicitName ?: 'workspace'
                } else if (config.default_workspace && workspaces.containsKey(config.default_workspace)) {
                    name = config.default_workspace?.toString()
                    path = ((Map) workspaces[name])?.path
                }
            }

            rejectExtraArgs(remaining, 'init')

            if (!name || !path) {
                throw new IllegalArgumentException('Usage: ai-worklog setup init [<name>] [<path>] [-w workspace] [--ide IDE] [--runtime groovy|python] [--ai-vault PATH] [--default] [--json] [--apply]')
            }

            GlobalConfig.validateWorkspaceName(name)
            File workspace = GlobalConfig.canonicalWorkspacePath(path)
            if (!workspace.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${path}")
            }

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
                'init',
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
                        renderHumanActionPlan(plan, false, 'init')
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
                        println "Setup operation failed: ${exception.message}"
                    }
                    return exitCodes.systemError
                }
                report.status = 'ready'
                report.message = 'Setup init complete'
                SetupReport.finalizeAppliedActionReport(report)
            }

            if (!jsonOutput) {
                renderHumanActionPlan(plan, apply, 'init')
            }

            SetupReport.renderReport(report, jsonOutput, !jsonOutput)
            return SetupReport.exitCodeForReport(report, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'init', status: 'error', message: exception.message], true)
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
        rejectExtraArgs(remaining, 'show')
        try {
            List context = workspaceContext(frameworkRoot, options)
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

    private static int runRepair(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        boolean apply = remaining.remove('--apply')
        try {
            List context = workspaceContext(frameworkRoot, options)
            File workspace = context[0] as File
            String name = context[1] as String
            if (!context[2]) {
                throw new IllegalArgumentException('Workspace is not registered')
            }

            Map config = GlobalConfig.load()
            List<String> registeredIdes = ((List) ((Map) config.workspaces[name]).ides)*.toString()
            if (!registeredIdes) {
                throw new IllegalArgumentException('No IDE profiles registered for workspace')
            }

            List<String> filterIdes = SetupResolver.parseIdeArgs(takeRepeatedOption(remaining, '--ide'))
            rejectExtraArgs(remaining, 'repair')
            List<String> ides = registeredIdes
            if (filterIdes) {
                List<String> invalid = filterIdes.findAll { it != 'auto' && !(it in registeredIdes) }
                if (invalid) {
                    throw new IllegalArgumentException("IDE not registered: ${invalid.join(', ')}")
                }
                ides = filterIdes.findAll { it != 'auto' }
            }

            List vaultResolution = resolveVaultOrError(workspace, null)
            File vaultRoot = vaultResolution[0] as File
            String vaultSource = vaultResolution[1]?.toString()
            Map vaultManifest = vaultResolution[2] as Map
            List runtimeSelection = SetupResolver.resolveRuntimeSelection()

            Map plan = SetupPlanner.planSetupRepair(
                workspace,
                vaultRoot,
                vaultManifest,
                ides,
                apply,
                frameworkRoot
            )

            Map report = SetupReport.buildActionReport(
                'repair',
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
                if (plan.conflicts) {
                    if (!jsonOutput) {
                        renderHumanActionPlan(plan, false, 'repair')
                    }
                    SetupReport.renderReport(report, jsonOutput)
                    return exitCodes.blocked
                }
                try {
                    SetupPlanner.applyInitOrRepairPlan(workspace, name, vaultRoot, ides, plan)
                } catch (IOException exception) {
                    if (jsonOutput) {
                        SetupReport.renderReport(report + [status: 'error', message: exception.message], true)
                    } else {
                        println "Setup operation failed: ${exception.message}"
                    }
                    return exitCodes.systemError
                }
                report.status = 'ready'
                report.message = 'Setup repair complete'
                SetupReport.finalizeAppliedActionReport(report)
            }

            if (!jsonOutput) {
                renderHumanActionPlan(plan, apply, 'repair')
            }

            SetupReport.renderReport(report, jsonOutput, !jsonOutput)
            return SetupReport.exitCodeForReport(report, exitCodes)
        } catch (IllegalArgumentException exception) {
            if (jsonOutput) {
                SetupReport.renderReport([operation: 'repair', status: 'error', message: exception.message], true)
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int runRevert(File frameworkRoot, Map options, List<String> args, ExitCodes exitCodes) {
        List<String> remaining = new ArrayList<>(args)
        boolean jsonOutput = remaining.remove('--json')
        boolean apply = remaining.remove('--apply')
        try {
            List context = workspaceContext(frameworkRoot, options)
            File workspace = context[0] as File
            String name = context[1] as String
            if (!context[2]) {
                throw new IllegalArgumentException('Workspace is not registered')
            }

            List<String> filterIdes = SetupResolver.parseIdeArgs(takeRepeatedOption(remaining, '--ide'))
            rejectExtraArgs(remaining, 'revert')
            if (filterIdes && filterIdes.contains('auto')) {
                throw new IllegalArgumentException('--ide auto cannot be used with revert')
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
                } catch (IOException exception) {
                    if (jsonOutput) {
                        SetupReport.renderReport(report + [status: 'error', message: exception.message], true)
                    } else {
                        println "Setup operation failed: ${exception.message}"
                    }
                    return exitCodes.systemError
                }
                report.status = 'ready'
                report.message = 'Setup revert complete'
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

    private static void renderHumanActionPlan(Map plan, boolean apply, String operation) {
        println "Setup ${operation}"
        SetupPlanner.printCompactActionPlan(plan, apply)
        SetupPlanner.printActionConflicts((List) (plan.conflicts ?: []))
        println()
    }

    private static List workspaceContext(File frameworkRoot, Map options) {
        Map resolved = GlobalConfig.resolveWorkspaceSelection(
            options.workspace,
            options.workspaceName,
            frameworkRoot
        )
        File workspace = resolved.path as File
        String name = resolved.name?.toString() ?: SetupChecks.findWorkspaceRegistration(workspace)
        Map config = GlobalConfig.load()
        boolean registered = name && config.workspaces[name]
        boolean isDefault = registered && config.default_workspace == name
        [workspace, name, registered, isDefault]
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
            throw new IllegalArgumentException("Unexpected arguments for setup ${action}")
        }
    }

    private static void usage() {
        println 'Usage: ai-worklog setup {init|check|show|repair|revert} ...'
    }
}
