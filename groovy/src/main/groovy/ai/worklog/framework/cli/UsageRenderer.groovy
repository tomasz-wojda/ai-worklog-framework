package ai.worklog.framework.cli

class UsageRenderer {
    private final CommandContract contract
    private final Map defaults

    UsageRenderer(CommandContract contract, Map defaults = [:]) {
        this.contract = contract
        this.defaults = defaults
    }

    String renderRoot() {
        List<Map> commands = contract.visibleChildren([])
        String names = commands.collect { it.name }.join(',')
        List<String> lines = [
            'usage: ai-worklog [--runtime groovy|python] [--workspace PATH] [-w NAME] [--workspace-name NAME] [--version]',
            "                  {${names}} ...",
            '',
            contract.data.description.toString(),
            '',
            'commands:'
        ]
        commands.each { Map command ->
            lines << "  ${command.name.toString().padRight(12)} ${command.description}"
        }
        lines.join(System.lineSeparator()) + System.lineSeparator()
    }

    String renderCommand(String commandName) {
        renderPath([commandName])
    }

    String renderAction(String commandName, String actionName) {
        renderPath([commandName, actionName])
    }

    String renderPath(List<String> path) {
        Map node = contract.node(path)
        if (!node) {
            return renderRoot()
        }
        if (contract.isExecutable(path)) {
            return renderLeaf(path, node)
        }
        List<Map> children = contract.visibleChildren(path)
        if (!children) {
            return "Usage: ai-worklog ${path.join(' ')} ..." + System.lineSeparator()
        }
        String names = children.collect { it.name }.join('|')
        List<String> lines = [
            "Usage: ai-worklog ${path.join(' ')} {${names}} ...",
            '',
            node.description.toString(),
            '',
            path == ['service'] ? 'services:' : 'actions:'
        ]
        int width = children.collect { it.name.toString().size() }.max() as int
        children.each { Map child ->
            lines << "  ${child.name.toString().padRight(width)}  ${child.description}"
        }
        lines.join(System.lineSeparator()) + System.lineSeparator()
    }

    private String renderLeaf(List<String> path, Map node) {
        List<Map> positionals = (List<Map>) (node.positionals ?: [])
        List<Map> options = (List<Map>) (node.options ?: [])
        List<String> usageParts = ['Usage:', contract.data.program.toString()] + path
        positionals.each { Map positional ->
            String name = positional.name.toString()
            String token = positional.variadic ? "${name}..." : name
            usageParts << ((positional.required as boolean) ? "<${token}>" : "[${token}]")
        }
        if (options) {
            usageParts << '[options]'
        }

        List<String> lines = [usageParts.join(' '), '', node.description.toString()]
        if (positionals) {
            lines.addAll(['', 'positional arguments:'])
            int width = positionals.collect { it.name.toString().size() }.max() as int
            positionals.each { Map positional ->
                String detail = positional.value?.help_values?.toString()
                String description = positional.description.toString()
                String suffix = detail ? "${description} (${detail})" : description
                lines << "  ${positional.name.toString().padRight(width)}  ${suffix}"
            }
        }
        if (options) {
            lines.addAll(['', 'options:'])
            int width = options.collect { optionLabel(it).size() }.max() as int
            options.each { Map option ->
                String description = option.description.toString()
                Object defaultValue = contract.resolveDefault(option, defaults)
                String suffix = defaultValue == null ? description : "${description} (default: ${defaultValue})"
                lines << "  ${optionLabel(option).padRight(width)}  ${suffix}"
            }
        }
        lines.join(System.lineSeparator()) + System.lineSeparator()
    }

    Map describe(String commandName = null, String actionName = null) {
        if (!commandName) {
            return contract.data
        }
        List<String> path = actionName ?
            [commandName, actionName] :
            [commandName]
        describePath(path)
    }

    Map describePath(List<String> path) {
        Map node = contract.node(path)
        node ? [version: contract.data.version, node: node] : null
    }

    private static String optionLabel(Map option) {
        List<String> names = []
        if (option.short) {
            names << option.short.toString()
        }
        String longName = option.name.toString()
        if (option.takes_value as boolean) {
            longName += " <${option.value.help_values}>"
        }
        names << longName
        names.join(', ')
    }
}
