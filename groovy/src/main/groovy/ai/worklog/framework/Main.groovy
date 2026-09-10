package ai.worklog.framework

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.commands.CatalogCommands
import ai.worklog.framework.commands.CloseoutCommands
import ai.worklog.framework.commands.DailyCommands
import ai.worklog.framework.commands.DeliveryCommands
import ai.worklog.framework.commands.DiagnosticsCommands
import ai.worklog.framework.commands.GlobalConfigCommands
import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.commands.ReconciliationCommands
import ai.worklog.framework.commands.ServiceCommands
import ai.worklog.framework.commands.StateCommands
import ai.worklog.framework.commands.TicketCommands
import ai.worklog.framework.commands.ToolchainCommands
import ai.worklog.framework.commands.WorkspaceCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.StateManager
import groovy.json.JsonOutput

class Main {
    static final String VERSION = '0.12.0'

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
        CommandContract contract = CommandContract.load(frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)

        if (args.remove('--version')) {
            println "ai-worklog ${VERSION} (groovy ${GroovySystem.version} / java ${System.getProperty('java.version')})"
            return exitCodes.success
        }
        if (args.any { it in ['-h', '--help'] }) {
            args.removeAll { it in ['-h', '--help'] }
            renderRequestedHelp(args, usage)
            return exitCodes.success
        }
        if (!args) {
            System.err.print usage.renderRoot()
            return exitCodes.userError
        }

        String command = args.remove(0)
        if (command == 'help') {
            return renderHelpCommand(args, usage, exitCodes)
        }
        String action = args ? args.remove(0) : null
        if (command == 'config') {
            return GlobalConfigCommands.run(action, args, frameworkRoot)
        }
        if (command == 'workspace') {
            return WorkspaceCommands.run(action, args, frameworkRoot, options)
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
            case 'service':
                return ServiceCommands.run(action, args, frameworkRoot, paths, config)
            default:
                System.err.print usage.renderRoot()
                return exitCodes.userError
        }
    }

    static Map extractGlobalOptions(List<String> args) {
        int commandIndex = commandIndex(args)
        [
            workspace: takeOption(args, '--workspace'),
            workspaceName: takeWorkspaceNameOption(args),
            runtime: takeOptionBefore(args, '--runtime', commandIndex)
        ]
    }

    private static int commandIndex(List<String> args) {
        List<String> commands = [
            'workspace', 'config', 'catalog', 'ticket', 'state',
            'preflight', 'reconcile', 'service', 'day', 'delivery',
            'closeout', 'diag', 'toolchain', 'help'
        ]
        int index = args.findIndexOf { it in commands }
        index >= 0 ? index : args.size()
    }

    static String takeOptionBefore(List<String> args, String name, int beforeIndex) {
        int index = args.indexOf(name)
        if (index < 0 || index >= beforeIndex) {
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
        File frameworkRoot = FrameworkPaths.resolveFrameworkRoot()
        print new UsageRenderer(CommandContract.load(frameworkRoot)).renderRoot()
    }

    private static int renderHelpCommand(
        List<String> args,
        UsageRenderer usage,
        ExitCodes exitCodes
    ) {
        boolean json = args.remove('--json')
        List<String> path = new ArrayList<>(args)
        if (json) {
            Map description = path ? usage.describePath(path) : usage.describe()
            if (!description) {
                System.err.println("Unknown help path: ${path.join(' ')}")
                return exitCodes.userError
            }
            println JsonOutput.prettyPrint(JsonOutput.toJson(description))
            return exitCodes.success
        }
        if (path) {
            if (!usage.describePath(path)) {
                System.err.println("Unknown help path: ${path.join(' ')}")
                return exitCodes.userError
            }
            print usage.renderPath(path)
        } else {
            print usage.renderRoot()
        }
        exitCodes.success
    }

    private static void renderRequestedHelp(List<String> args, UsageRenderer usage) {
        boolean json = args.remove('--json')
        List<String> path = deepestHelpPath(args, usage)
        if (json) {
            Map description = path ? usage.describePath(path) : usage.describe()
            println JsonOutput.prettyPrint(JsonOutput.toJson(description ?: usage.describe()))
        } else if (path) {
            print usage.renderPath(path)
        } else {
            print usage.renderRoot()
        }
    }

    private static List<String> deepestHelpPath(List<String> args, UsageRenderer usage) {
        List<String> path = []
        for (String token : args) {
            List<String> candidate = path + token
            if (!usage.describePath(candidate)) {
                break
            }
            path = candidate
        }
        path
    }
}
