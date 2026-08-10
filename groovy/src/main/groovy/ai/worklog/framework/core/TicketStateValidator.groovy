package ai.worklog.framework.core

class TicketStateValidator {
    final Map rules
    final Redaction redaction

    TicketStateValidator(File frameworkRoot) {
        rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/ticket-state-rules.json'),
            [:]
        )
        redaction = new Redaction(frameworkRoot)
    }

    Object valueAt(Map data, String path) {
        Object current = data
        for (String part : path.tokenize('.')) {
            if (!(current instanceof Map) || !((Map) current).containsKey(part)) {
                return null
            }
            current = ((Map) current)[part]
        }
        current
    }

    List<String> validateValue(String path, Object value) {
        String expected = ((Map) rules.path_types)[path]?.toString()
        if (!expected) {
            return ["Unknown or immutable state path: ${path}"]
        }
        if (path.tokenize('.').any { redaction.sensitiveKey(it) }) {
            return ["Sensitive state path is forbidden: ${path}"]
        }
        boolean valid = [
            string: value instanceof String,
            boolean: value instanceof Boolean,
            integer: value instanceof Integer || value instanceof Long,
            array: value instanceof List,
            object: value instanceof Map
        ][expected] ?: false
        if (!valid) {
            return ["${path} must be ${expected}"]
        }
        List allowed = (List) (((Map) rules.enums)[path] ?: [])
        if (allowed && !allowed.contains(value)) {
            return ["${path} must be one of: ${allowed.join(', ')}"]
        }
        []
    }

    List<String> validate(Map data) {
        List<String> errors = []
        Set allowed = ((List) rules.allowed_top_level).toSet()
        data.keySet().findAll { !allowed.contains(it) }.each {
            errors << "Unknown top-level field: ${it}"
        }
        ((List) rules.required).findAll { !data.containsKey(it) }.each {
            errors << "Missing required field: ${it}"
        }
        ((Map) rules.path_types).keySet().each { path ->
            Object value = valueAt(data, path.toString())
            if (value != null) {
                errors.addAll(validateValue(path.toString(), value))
            }
        }
        errors
    }
}
