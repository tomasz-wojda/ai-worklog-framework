package ai.worklog.framework.cli

class ArgumentParser {
    private final CommandContract contract

    ArgumentParser(CommandContract contract) {
        this.contract = contract
    }

    ParsedArguments parse(String command, Map action, List<String> input, Map rules = [:]) {
        List<Map> options = (List<Map>) (action.options ?: [])
        Map<String, Map> optionsByToken = [:]
        options.each { Map option ->
            optionsByToken[option.name.toString()] = option
            if (option.short) {
                optionsByToken[option.short.toString()] = option
            }
        }

        List<String> positionalTokens = []
        Set<String> flags = [] as Set
        Map<String, Object> optionValues = [:]
        Set<String> seen = [] as Set
        boolean optionsEnabled = true
        int index = 0

        while (index < input.size()) {
            String token = input[index]
            if (optionsEnabled && token == '--') {
                optionsEnabled = false
                index++
                continue
            }
            if (optionsEnabled && token.startsWith('-') && token != '-') {
                Map option = optionsByToken[token]
                if (!option) {
                    throw error("Unknown option for ${command} ${action.name}: ${token}", command, action)
                }
                String name = option.name.toString()
                if (seen.contains(name) && !(option.repeatable ?: false)) {
                    throw error("Repeated option: ${name}", command, action)
                }
                seen << name
                if (option.takes_value as boolean) {
                    if (index + 1 >= input.size() || input[index + 1].startsWith('-')) {
                        throw error("Missing value for ${name}", command, action)
                    }
                    String supplied = input[++index]
                    validateValue(supplied, (Map) option.value, name, command, action)
                    if (option.repeatable ?: false) {
                        if (!(optionValues[name] instanceof List)) {
                            optionValues[name] = []
                        }
                        ((List<String>) optionValues[name]) << supplied
                    } else {
                        optionValues[name] = supplied
                    }
                } else {
                    flags << name
                }
                index++
                continue
            }
            positionalTokens << token
            index++
        }

        Map<String, String> positionals = [:]
        Map<String, List<String>> variadic = [:]
        List<Map> definitions = (List<Map>) (action.positionals ?: [])
        int tokenIndex = 0
        definitions.eachWithIndex { Map definition, int definitionIndex ->
            String name = definition.name.toString()
            if (definition.variadic ?: false) {
                if (definitionIndex != definitions.size() - 1) {
                    throw error("Variadic positional must be last: ${name}", command, action)
                }
                List<String> values = positionalTokens.drop(tokenIndex)
                if ((definition.required as boolean) && !values) {
                    throw error("Missing ${name}", command, action)
                }
                values.each { validateValue(it, (Map) definition.value, name, command, action) }
                variadic[name] = values
                tokenIndex = positionalTokens.size()
                return
            }
            if (tokenIndex >= positionalTokens.size()) {
                if (definition.required as boolean) {
                    throw error("Missing ${name}", command, action)
                }
                return
            }
            String supplied = positionalTokens[tokenIndex++]
            validateValue(supplied, (Map) definition.value, name, command, action)
            positionals[name] = supplied
        }

        if (tokenIndex < positionalTokens.size()) {
            throw error(
                "Unexpected argument for ${command} ${action.name}: ${positionalTokens[tokenIndex]}",
                command,
                action
            )
        }

        options.each { Map option ->
            String name = option.name.toString()
            if (!seen.contains(name) && option.default_from) {
                Object value = contract.resolveDefault(option, rules)
                if (value != null) {
                    optionValues[name] = value
                }
            }
        }

        new ParsedArguments(positionals, variadic, flags, optionValues)
    }

    private static void validateValue(
        String supplied,
        Map definition,
        String label,
        String command,
        Map action
    ) {
        if (!definition) {
            if (!supplied) {
                throw error("Invalid ${label}: '${supplied}'", command, action)
            }
            return
        }

        List<Boolean> results = []
        if (definition.choices instanceof List) {
            List<String> choices = ((List) definition.choices)*.toString()
            results << (
                definition.case_insensitive == true ?
                    choices*.toLowerCase().contains(supplied.toLowerCase()) :
                    choices.contains(supplied)
            )
        }
        if (definition.pattern) {
            results << (supplied ==~ definition.pattern.toString())
        }
        if (definition.containsKey('integer_min') || definition.containsKey('integer_max')) {
            boolean valid = supplied ==~ /-?\d+/
            if (valid) {
                BigInteger value = new BigInteger(supplied)
                if (definition.containsKey('integer_min')) {
                    valid = valid && value >= new BigInteger(definition.integer_min.toString())
                }
                if (definition.containsKey('integer_max')) {
                    valid = valid && value <= new BigInteger(definition.integer_max.toString())
                }
            }
            results << valid
        }
        if (!results) {
            results << !!supplied
        }
        if (!results.any { it }) {
            throw error(
                "Invalid ${label}: '${supplied}' (expected ${definition.help_values})",
                command,
                action
            )
        }
    }

    private static UsageError error(String message, String command, Map action) {
        new UsageError(message, command, action.name?.toString())
    }
}
