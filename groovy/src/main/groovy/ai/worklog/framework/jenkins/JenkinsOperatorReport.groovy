package ai.worklog.framework.jenkins

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput

class JenkinsOperatorReport {
    String operation
    String fetchedAt
    Status status
    String controller
    String message
    String domain
    String folder
    String query
    String view
    String job
    String buildSelector
    Map required
    List<Map> items = []

    static JenkinsOperatorReport fromPayload(Map payload) {
        new JenkinsOperatorReport(
            operation: payload.operation?.toString(),
            fetchedAt: payload.fetched_at?.toString(),
            status: payload.status instanceof Status ? (Status) payload.status : Status.values().find {
                it.value == payload.status?.toString()
            } ?: Status.UNKNOWN,
            controller: payload.controller?.toString(),
            message: payload.message?.toString() ?: '',
            domain: payload.domain?.toString(),
            folder: payload.folder?.toString(),
            query: payload.query?.toString(),
            view: payload.view?.toString(),
            job: payload.job?.toString(),
            buildSelector: payload.build_selector?.toString(),
            required: payload.required instanceof Map ? (Map) payload.required : null,
            items: (payload.items ?: []).collect { it instanceof Map ? new LinkedHashMap(it) : [:] }
        )
    }

    Map toMap(Redaction redaction) {
        Map payload = [
            operation: operation,
            fetched_at: fetchedAt,
            status: status.value,
            items: items.collect { item -> redactItem(item, redaction) }
        ]
        if (controller) {
            payload.controller = controller
        }
        if (message) {
            payload.message = message
        }
        if (domain) {
            payload.domain = domain
        }
        if (folder) {
            payload.folder = folder
        }
        if (query) {
            payload.query = query
        }
        if (view) {
            payload.view = view
        }
        if (job) {
            payload.job = job
        }
        if (buildSelector) {
            payload.build_selector = buildSelector
        }
        if (required) {
            payload.required = required
        }
        payload
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        output.append("Jenkins ${operation}").append(System.lineSeparator())
        if (controller) {
            output.append("  Controller: ${controller}").append(System.lineSeparator())
        }
        if (folder) {
            output.append("  Folder: ${folder}").append(System.lineSeparator())
        }
        if (query) {
            output.append("  Query: ${query}").append(System.lineSeparator())
        }
        if (view) {
            output.append("  View: ${view}").append(System.lineSeparator())
        }
        if (job) {
            output.append("  Job: ${job}").append(System.lineSeparator())
        }
        if (buildSelector) {
            output.append("  Build selector: ${buildSelector}").append(System.lineSeparator())
        }
        output.append("  Fetched: ${fetchedAt}").append(System.lineSeparator())
        output.append("  Status: ${status.value}").append(System.lineSeparator())
        if (message) {
            output.append("  Message: ${redaction.redact(message)}").append(System.lineSeparator())
        }
        items.each { item ->
            output.append("  - ${pythonItemString(redactItem(item, redaction))}").append(System.lineSeparator())
        }
        output.toString()
    }

    private static String pythonItemString(Object value) {
        if (value instanceof Map) {
            '{' + ((Map) value).collect { key, entry ->
                "'${key}': ${pythonValueString(entry)}"
            }.join(', ') + '}'
        } else if (value instanceof List) {
            '[' + ((List) value).collect { pythonValueString(it) }.join(', ') + ']'
        } else {
            pythonValueString(value)
        }
    }

    private static String pythonValueString(Object value) {
        if (value == null) {
            return 'None'
        }
        if (value instanceof Boolean) {
            return value ? 'True' : 'False'
        }
        if (value instanceof Number) {
            return value.toString()
        }
        if (value instanceof Map) {
            return pythonItemString(value)
        }
        if (value instanceof List) {
            return '[' + value.collect { pythonValueString(it) }.join(', ') + ']'
        }
        return "'${value}'"
    }

    static int exitCodeFor(JenkinsOperatorReport report, ExitCodes exitCodes) {
        if (report.status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (report.status == Status.ERROR) {
            String lower = report.message?.toLowerCase() ?: ''
            if (lower.contains('not found') || lower.contains('invalid') ||
                lower.contains('no files') || lower.contains('missing')) {
                return exitCodes.userError
            }
            return exitCodes.systemError
        }
        exitCodes.success
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = (Map) redaction.redact(item)
        ['has_user', 'has_token', 'value_present', 'active', 'enabled', 'buildable', 'in_queue',
         'building', 'recent_failure', 'available', 'idle', 'offline', 'temporarily_offline',
         'stuck', 'blocked', 'truncated', 'authenticated'].each { key ->
            if (item.containsKey(key)) {
                redacted[key] = item[key]
            }
        }
        redacted
    }
}
