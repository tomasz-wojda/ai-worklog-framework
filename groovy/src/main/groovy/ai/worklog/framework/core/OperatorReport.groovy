package ai.worklog.framework.core

import groovy.json.JsonOutput

abstract class OperatorReport {
    String operation
    String fetchedAt
    Status status
    String message
    String errorKind
    List<Map> items = []

    abstract Map toMap(Redaction redaction)

    abstract String renderHuman(Redaction redaction)

    protected List<String> userErrorMarkers() {
        []
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    boolean isUserError() {
        if (errorKind) {
            return errorKind == 'user'
        }
        String lower = message?.toLowerCase() ?: ''
        userErrorMarkers().any { lower.contains(it) }
    }

    int exitCode(ExitCodes exitCodes) {
        if (status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (status == Status.ERROR) {
            return isUserError() ? exitCodes.userError : exitCodes.systemError
        }
        exitCodes.success
    }

    static int exitCodeFor(OperatorReport report, ExitCodes exitCodes) {
        report.exitCode(exitCodes)
    }

    protected <T extends OperatorReport> T readCommon(Map payload) {
        operation = payload.operation?.toString()
        fetchedAt = payload.fetched_at?.toString()
        status = parseStatus(payload.status)
        message = payload.message?.toString() ?: ''
        errorKind = payload.error_kind?.toString()
        items = ((List) (payload.items ?: [])).collect {
            it instanceof Map ? new LinkedHashMap((Map) it) : [:]
        }
        (T) this
    }

    protected Map commonMap(Closure<Map> redactItem) {
        [
            operation: operation,
            fetched_at: fetchedAt,
            status: status.value,
            items: items.collect { redactItem(it) }
        ]
    }

    static Status parseStatus(Object value) {
        if (value instanceof Status) {
            return (Status) value
        }
        Status.values().find { it.value == value?.toString() } ?: Status.UNKNOWN
    }

    static Map mapOrNull(Object value) {
        value instanceof Map ? (Map) value : null
    }

    static Boolean flagOrNull(Map payload, String key) {
        payload.containsKey(key) ? payload[key] as Boolean : null
    }

    static Map redactKeeping(Map item, Redaction redaction, Collection<String> keep) {
        Map redacted = (Map) redaction.redact(item)
        keep.each { key ->
            if (item.containsKey(key)) {
                redacted[key] = item[key]
            }
        }
        redacted
    }

    static Map allowedSources(Map source, Collection<String> allowed) {
        source.collectEntries { key, value ->
            [(key): allowed.contains(value?.toString()) ? value.toString() : 'default']
        }
    }

    static void line(StringBuilder output, Object text) {
        output.append(text).append(System.lineSeparator())
    }

    static String pythonItemString(Object value) {
        if (value instanceof Map) {
            return '{' + ((Map) value).collect { key, entry ->
                "'${key}': ${pythonValueString(entry)}"
            }.join(', ') + '}'
        }
        if (value instanceof List) {
            return '[' + ((List) value).collect { pythonValueString(it) }.join(', ') + ']'
        }
        pythonValueString(value)
    }

    static String pythonValueString(Object value) {
        if (value == null) {
            return 'None'
        }
        if (value instanceof Boolean) {
            return value ? 'True' : 'False'
        }
        if (value instanceof Number) {
            return value.toString()
        }
        if (value instanceof Map || value instanceof List) {
            return pythonItemString(value)
        }
        "'${value}'"
    }
}
