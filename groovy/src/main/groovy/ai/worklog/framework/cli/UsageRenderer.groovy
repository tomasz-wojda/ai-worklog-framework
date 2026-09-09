package ai.worklog.framework.cli

class UsageRenderer {
    private final CommandContract contract
    private final Map defaults

    UsageRenderer(CommandContract contract, Map defaults = [:]) {
        this.contract = contract
        this.defaults = defaults
    }

    String renderRoot() {
        List<Map> commands = contract.commands().findAll { it.name != 'help' }
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
        Map command = contract.command(commandName)
        if (!command) {
            return renderRoot()
        }
        List<Map> actions = contract.actions(commandName)
        if (!actions) {
            return "Usage: ai-worklog ${commandName} ..." + System.lineSeparator()
        }
        String names = actions.collect { it.name }.join('|')
        List<String> lines = [
            "Usage: ai-worklog ${commandName} {${names}} ...",
            '',
            command.description.toString(),
            '',
            'actions:'
        ]
        int width = actions.collect { it.name.toString().size() }.max() as int
        actions.each { Map action ->
            lines << "  ${action.name.toString().padRight(width)}  ${action.description}"
        }
        lines.join(System.lineSeparator()) + System.lineSeparator()
    }

    String renderAction(String commandName, String actionName) {
        Map action = contract.action(commandName, actionName)
        if (!action) {
            return renderCommand(commandName)
        }
        List<Map> positionals = (List<Map>) (action.positionals ?: [])
        List<Map> options = (List<Map>) (action.options ?: [])
        List<String> usageParts = ['Usage:', contract.data.program.toString(), commandName, actionName]
        positionals.each { Map positional ->
            String name = positional.name.toString()
            String token = positional.variadic ? "${name}..." : name
            usageParts << ((positional.required as boolean) ? "<${token}>" : "[${token}]")
        }
        if (options) {
            usageParts << '[options]'
        }

        List<String> lines = [usageParts.join(' '), '', action.description.toString()]
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
        if (actionName) {
            Map action = contract.action(commandName, actionName)
            return action ? [version: contract.data.version, action: action] : null
        }
        if (commandName) {
            Map command = contract.command(commandName)
            return command ? [version: contract.data.version, command: command] : null
        }
        contract.data
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
