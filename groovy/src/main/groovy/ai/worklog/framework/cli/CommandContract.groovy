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
                expand((Map) JsonFiles.read(new File(frameworkRoot, 'shared/command-contract.json'), fallback))
            )
        }
        CACHE[key]
    }

    static Map expand(Map raw) {
        Map shared = (Map) (raw.shared ?: [:])
        Map expanded = new LinkedHashMap(raw)
        expanded.remove('shared')
        expanded.commands = expandNodes((List) (raw.commands ?: []), shared)
        expanded
    }

    private static List expandNodes(List nodes, Map shared) {
        nodes.collect { Object item ->
            Map node = new LinkedHashMap((Map) item)
            ['options', 'positionals'].each { String kind ->
                if (node[kind] instanceof List) {
                    node[kind] = ((List) node[kind]).collect { resolve(kind, it, shared) }
                }
            }
            if (node.subcommands instanceof List) {
                node.subcommands = expandNodes((List) node.subcommands, shared)
            }
            node
        }
    }

    private static Object resolve(String kind, Object entry, Map shared) {
        if (!(entry instanceof Map) || !((Map) entry).containsKey('use')) {
            return entry
        }
        Map reference = (Map) entry
        Object definition = ((Map) (shared[kind] ?: [:]))[reference.use]
        if (reference.size() != 1 || !(definition instanceof Map)) {
            throw new IllegalStateException("Unknown shared ${kind} entry in command contract: ${reference.use}")
        }
        copy(definition)
    }

    private static Object copy(Object value) {
        if (value instanceof Map) {
            return ((Map) value).collectEntries { key, item -> [(key): copy(item)] }
        }
        if (value instanceof List) {
            return ((List) value).collect { copy(it) }
        }
        value
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
