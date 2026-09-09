package ai.worklog.framework.cli

class ParsedArguments {
    final Map<String, String> positionals
    final Map<String, List<String>> variadic
    final Set<String> flags
    final Map<String, Object> optionValues

    ParsedArguments(
        Map<String, String> positionals = [:],
        Map<String, List<String>> variadic = [:],
        Set<String> flags = [] as Set,
        Map<String, Object> optionValues = [:]
    ) {
        this.positionals = positionals
        this.variadic = variadic
        this.flags = flags
        this.optionValues = optionValues
    }

    String positional(String name) {
        positionals[name]
    }

    List<String> variadic(String name) {
        variadic[name] ?: []
    }

    boolean flag(String name) {
        flags.contains(name)
    }

    Object value(String name) {
        optionValues[name]
    }

    List<String> values(String name) {
        Object value = optionValues[name]
        value instanceof List ? (List<String>) value : value == null ? [] : [value.toString()]
    }
}
