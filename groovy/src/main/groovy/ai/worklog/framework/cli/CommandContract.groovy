package ai.worklog.framework.cli

import ai.worklog.framework.core.JsonFiles

class CommandContract {
    private static final Map<String, CommandContract> CACHE = [:]

    final Map data

    private CommandContract(Map data) {
        this.data = data
    }

    static synchronized CommandContract load(File frameworkRoot) {
        String key = frameworkRoot.canonicalPath
        if (!CACHE[key]) {
            Map fallback = [
                version: 1,
                program: 'ai-worklog',
                description: 'DevOps daily workflow automation framework',
                global_options: [],
                commands: []
            ]
            CACHE[key] = new CommandContract(
                (Map) JsonFiles.read(new File(frameworkRoot, 'shared/command-contract.json'), fallback)
            )
        }
        CACHE[key]
    }

    Map command(String name) {
        commands().find { Map command -> command.name?.toString() == name }
    }

    Map action(String commandName, String actionName) {
        actions(commandName)?.find { Map action -> action.name?.toString() == actionName }
    }

    List<Map> commands() {
        (List<Map>) (data.commands ?: [])
    }

    List<Map> actions(String commandName) {
        Map command = command(commandName)
        command?.actions instanceof List ? (List<Map>) command.actions : null
    }

    Object resolveDefault(Map option, Map rules) {
        String path = option.default_from?.toString()
        if (!path) {
            return null
        }
        Object current = rules
        for (String segment : path.split('\\.')) {
            if (!(current instanceof Map) || !((Map) current).containsKey(segment)) {
                return null
            }
            current = ((Map) current)[segment]
        }
        current
    }
}
