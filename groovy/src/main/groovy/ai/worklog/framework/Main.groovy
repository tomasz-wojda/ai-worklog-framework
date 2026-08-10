package ai.worklog.framework

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.commands.CatalogCommands
import ai.worklog.framework.commands.CloseoutCommands
import ai.worklog.framework.commands.DailyCommands
import ai.worklog.framework.commands.DeliveryCommands
import ai.worklog.framework.commands.DiagnosticsCommands
import ai.worklog.framework.commands.GlobalConfigCommands
import ai.worklog.framework.commands.JenkinsCommands
import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.commands.ReconciliationCommands
import ai.worklog.framework.commands.StateCommands
import ai.worklog.framework.commands.TicketCommands
import ai.worklog.framework.commands.ToolchainCommands
import ai.worklog.framework.commands.WorkspaceCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.StateManager

class Main {
    static final String VERSION = '0.6.0'

    static void main(String[] input) {
        int code
        ExitCodes exitCodes
        try {
            exitCodes = new ExitCodes(FrameworkPaths.resolveFrameworkRoot())
            code = execute(input.toList())
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.message)
            code = exitCodes?.userError ?: 1
        } catch (Exception exception) {
            System.err.println("System error: ${exception.message ?: exception.class.simpleName}")
            code = exitCodes?.systemError ?: 2
        }
        System.exit(code)
    }

    static int execute(List<String> input) {
        List<String> args = new ArrayList<>(input)
        Map options = extractGlobalOptions(args)
        File frameworkRoot = FrameworkPaths.resolveFrameworkRoot()
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)

        if (args.remove('--version')) {
            println "ai-worklog ${VERSION} (groovy ${GroovySystem.version} / java ${System.getProperty('java.version')})"
            return exitCodes.success
        }
        if (!args || args[0] in ['-h', '--help']) {
            help()
            return args ? exitCodes.success : exitCodes.userError
        }

        String command = args.remove(0)
        String action = args ? args.remove(0) : null
        if (command == 'config') {
            return GlobalConfigCommands.run(action, args, frameworkRoot)
        }
        if (command == 'workspace') {
            return WorkspaceCommands.run(
                action,
                args,
                frameworkRoot,
                options.workspace,
                options.workspaceName
            )
        }
        File workspaceRoot = FrameworkPaths.resolveWorkspace(
            options.workspace,
            options.workspaceName,
            frameworkRoot
        )
        FrameworkPaths paths = new FrameworkPaths(workspaceRoot)
        Map config = ConfigLoader.load(workspaceRoot)
        CatalogLoader catalog = new CatalogLoader(frameworkRoot, paths)
        StateManager states = new StateManager(frameworkRoot, paths)

        switch (command) {
            case 'catalog':
                return CatalogCommands.run(action, args, catalog)
            case 'ticket':
                return TicketCommands.run(action, args, paths, catalog)
            case 'state':
                return StateCommands.run(action, args, frameworkRoot, states)
            case 'preflight':
                List<String> preflightArgs = action ? [action] + args : args
                return PreflightCommands.run(frameworkRoot, paths, config, preflightArgs)
            case 'day':
                return DailyCommands.run(action, paths, states)
            case 'delivery':
                return DeliveryCommands.run(action, args, frameworkRoot, states)
            case 'closeout':
                return CloseoutCommands.run(action, args, paths, states)
            case 'diag':
                return DiagnosticsCommands.run(action, args, frameworkRoot, paths)
            case 'toolchain':
                return ToolchainCommands.run(action, args, frameworkRoot, config)
            case 'reconcile':
                return ReconciliationCommands.run(
                    action, args, frameworkRoot, paths, config, catalog, states
                )
            case 'jenkins':
                return JenkinsCommands.run(action, args, frameworkRoot, paths, config)
            default:
                help()
                return exitCodes.userError
        }
    }

    static Map extractGlobalOptions(List<String> args) {
        [
            workspace: takeOption(args, '--workspace'),
            workspaceName: takeWorkspaceNameOption(args),
            runtime: takeOption(args, '--runtime')
        ]
    }

    static String takeWorkspaceNameOption(List<String> args) {
        String value = takeOption(args, '--workspace-name')
        value ?: takeShortOption(args, '-w')
    }

    static String takeOption(List<String> args, String name) {
        int index = args.indexOf(name)
        if (index < 0) {
            return null
        }
        if (index + 1 >= args.size()) {
            throw new IllegalArgumentException("Missing value for ${name}")
        }
        String value = args[index + 1]
        args.remove(index + 1)
        args.remove(index)
        value
    }

    static String takeShortOption(List<String> args, String name) {
        int index = args.indexOf(name)
        if (index < 0) {
            return null
        }
        if (index + 1 >= args.size()) {
            throw new IllegalArgumentException("Missing value for ${name}")
        }
        if (args[index + 1].startsWith('-')) {
            throw new IllegalArgumentException("Missing value for ${name}")
        }
        String value = args[index + 1]
        args.remove(index + 1)
        args.remove(index)
        value
    }

    static void help() {
        println 'usage: ai-worklog [--runtime groovy|python] [--workspace PATH] [-w NAME] [--workspace-name NAME] [--version]'
        println '                  {config,workspace,catalog,ticket,state,preflight,reconcile,jenkins,day,delivery,closeout,diag,toolchain} ...'
        println()
        println 'DevOps daily workflow automation framework'
        println()
        println 'commands:'
        println '  config       Global runtime and workspace registry'
        println '  workspace    Workspace setup operations'
        println '  catalog      Service catalog operations'
        println '  ticket       Ticket preparation'
        println '  state        Structured ticket state'
        println '  preflight    Environment preflight checks'
        println '  reconcile    Cross-system read-only reconciliation'
        println '  jenkins      Read-only Jenkins operator'
        println '  day          Daily routines'
        println '  delivery     Delivery state tracking'
        println '  closeout     Close-out and handover'
        println '  diag         Diagnostic packs'
        println '  toolchain    Python/Java/Groovy detection and routing'
    }
}
