package ai.worklog.framework.artifactory

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput

class ArtifactoryOperatorReport {
    String operation
    String fetchedAt
    Status status
    String profile
    String server
    String repository
    String path
    String message
    String errorKind
    Map filters
    Map totals
    Boolean truncated
    List<Map> items = []

    static ArtifactoryOperatorReport fromPayload(Map payload) {
        new ArtifactoryOperatorReport(
            operation: payload.operation?.toString(),
            fetchedAt: payload.fetched_at?.toString(),
            status: payload.status instanceof Status ? (Status) payload.status : Status.values().find {
                it.value == payload.status?.toString()
            } ?: Status.UNKNOWN,
            profile: payload.profile?.toString(),
            server: payload.server?.toString(),
            repository: payload.repository?.toString(),
            path: payload.path?.toString(),
            message: payload.message?.toString() ?: '',
            errorKind: payload.error_kind?.toString(),
            filters: payload.filters instanceof Map ? new LinkedHashMap((Map) payload.filters) : null,
            totals: payload.totals instanceof Map ? new LinkedHashMap((Map) payload.totals) : null,
            truncated: payload.containsKey('truncated') ? payload.truncated as Boolean : null,
            items: (payload.items ?: []).collect { it instanceof Map ? new LinkedHashMap((Map) it) : [:] }
        )
    }

    Map toMap(Redaction redaction) {
        Map payload = [
            operation: operation,
            fetched_at: fetchedAt,
            status: status.value,
            items: items.collect { redactItem(it, redaction) }
        ]
        if (profile) payload.profile = profile
        if (server) payload.server = redaction.redactString(server)
        if (repository) payload.repository = repository
        if (path) payload.path = redaction.redactString(path)
        if (message) payload.message = redaction.redactString(message)
        if (errorKind) payload.error_kind = errorKind
        if (filters) payload.filters = redactSafeMap(filters, redaction)
        if (totals) payload.totals = redactSafeMap(totals, redaction)
        if (truncated != null) payload.truncated = truncated
        payload
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        output.append("Artifactory ${operation}").append(System.lineSeparator())
        if (profile) output.append("  Profile: ${profile}").append(System.lineSeparator())
        if (server) output.append("  Server: ${redaction.redactString(server)}").append(System.lineSeparator())
        if (repository) output.append("  Repository: ${repository}").append(System.lineSeparator())
        if (path) output.append("  Path: ${redaction.redactString(path)}").append(System.lineSeparator())
        output.append("  Fetched: ${fetchedAt}").append(System.lineSeparator())
        output.append("  Status: ${status.value}").append(System.lineSeparator())
        if (filters) {
            output.append("  Filters: ${redactSafeMap(filters, redaction)}").append(System.lineSeparator())
        }
        if (totals) {
            output.append("  Totals: ${redactSafeMap(totals, redaction)}").append(System.lineSeparator())
        }
        if (truncated != null) {
            output.append("  Truncated: ${truncated}").append(System.lineSeparator())
        }
        if (message) {
            output.append("  Message: ${redaction.redactString(message)}").append(System.lineSeparator())
        }
        items.each { item ->
            output.append("  - ${pythonItemString(redactItem(item, redaction))}").append(System.lineSeparator())
        }
        output.toString()
    }

    static int exitCodeFor(ArtifactoryOperatorReport report, ExitCodes exitCodes) {
        if (report.status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (report.status == Status.ERROR) {
            return report.errorKind == 'user' ? exitCodes.userError : exitCodes.systemError
        }
        exitCodes.success
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = (Map) redaction.redact(item)
        [
            'default', 'has_url', 'has_token', 'reachable', 'authenticated', 'readable',
            'folder', 'recursive', 'truncated', 'size', 'bytes', 'matched', 'returned'
        ].each { key ->
            if (item.containsKey(key)) {
                redacted[key] = item[key]
            }
        }
        if (item.auth_scheme in ['bearer', 'api-key']) {
            redacted.auth_scheme = item.auth_scheme
        }
        if (item.source instanceof Map) {
            redacted.source = ((Map) item.source).collectEntries { key, value ->
                [(key): value?.toString() in ['env', 'properties', 'legacy', 'default'] ?
                    value.toString() :
                    'default']
            }
        }
        if (item.checksums instanceof Map) {
            redacted.checksums = new LinkedHashMap((Map) item.checksums)
        }
        redacted
    }

    private static Map redactSafeMap(Map value, Redaction redaction) {
        Map redacted = (Map) redaction.redact(value)
        ['recursive', 'matched', 'returned', 'bytes'].each { key ->
            if (value.containsKey(key)) {
                redacted[key] = value[key]
            }
        }
        redacted
    }

    private static String pythonItemString(Object value) {
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

    private static String pythonValueString(Object value) {
        if (value == null) return 'None'
        if (value instanceof Boolean) return value ? 'True' : 'False'
        if (value instanceof Number) return value.toString()
        if (value instanceof Map || value instanceof List) return pythonItemString(value)
        "'${value}'"
    }
}
