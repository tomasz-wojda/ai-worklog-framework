package ai.worklog.framework.core

import groovy.json.JsonSlurper

class StatePatch {
    final TicketStateValidator validator

    StatePatch(File frameworkRoot) {
        validator = new TicketStateValidator(frameworkRoot)
    }

    static Object parseValue(String raw) {
        try {
            return new JsonSlurper().parseText(raw)
        } catch (Exception ignored) {
            return raw
        }
    }

    Object applyPath(Map data, String path, Object value) {
        if (((List) validator.rules.immutable_paths).contains(path)) {
            throw new IllegalArgumentException("Immutable state path: ${path}")
        }
        List<String> errors = validator.validateValue(path, value)
        if (errors) {
            throw new IllegalArgumentException(errors[0])
        }
        List<String> parts = path.tokenize('.')
        Map current = data
        List<String> parents = parts.size() > 1 ? parts.subList(0, parts.size() - 1) : []
        parents.each { part ->
            if (!(current[part] instanceof Map)) {
                throw new IllegalArgumentException("State path is not an object: ${part}")
            }
            current = (Map) current[part]
        }
        Object previous = current[parts[-1]]
        current[parts[-1]] = value
        previous
    }
}
