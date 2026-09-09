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
                version: 2,
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
        node([name])
    }

    List<Map> commands() {
        (List<Map>) (data.commands ?: [])
    }

    Map node(List<String> path) {
        if (!path) {
            return null
        }
        List<Map> level = commands()
        Map current
        for (String segment : path) {
            current = level.find { Map item -> item.name?.toString() == segment }
            if (!current) {
                return null
            }
            level = current.subcommands instanceof List ?
                (List<Map>) current.subcommands :
                []
        }
        current
    }

    List<Map> children(List<String> path) {
        Map current = node(path)
        current?.subcommands instanceof List ? (List<Map>) current.subcommands : []
    }

    List<Map> visibleChildren(List<String> path) {
        List<Map> values = path ? children(path) : commands()
        values.findAll { it.name != 'help' }
    }

    boolean isExecutable(List<String> path) {
        Map current = node(path)
        current && current.positionals instanceof List && current.options instanceof List
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
