package ai.worklog.framework.artifactory

import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Redaction

class ArtifactoryOperatorReport extends OperatorReport {
    static final List<String> KEPT_ITEM_KEYS = [
        'default', 'has_url', 'has_token', 'reachable', 'authenticated', 'readable',
        'folder', 'recursive', 'truncated', 'size', 'bytes', 'matched', 'returned'
    ]
    static final List<String> KEPT_SUMMARY_KEYS = ['recursive', 'matched', 'returned', 'bytes']

    String profile
    String server
    String repository
    String path
    Map filters
    Map totals
    Boolean truncated

    static ArtifactoryOperatorReport fromPayload(Map payload) {
        new ArtifactoryOperatorReport(
            profile: payload.profile?.toString(),
            server: payload.server?.toString(),
            repository: payload.repository?.toString(),
            path: payload.path?.toString(),
            filters: payload.filters instanceof Map ? new LinkedHashMap((Map) payload.filters) : null,
            totals: payload.totals instanceof Map ? new LinkedHashMap((Map) payload.totals) : null,
            truncated: flagOrNull(payload, 'truncated')
        ).readCommon(payload)
    }

    Map toMap(Redaction redaction) {
        Map payload = commonMap { redactItem(it, redaction) }
        if (profile) payload.profile = profile
        if (server) payload.server = redaction.redactString(server)
        if (repository) payload.repository = repository
        if (path) payload.path = redaction.redactString(path)
        if (message) payload.message = redaction.redactString(message)
        if (errorKind) payload.error_kind = errorKind
        if (filters) payload.filters = redactSummary(filters, redaction)
        if (totals) payload.totals = redactSummary(totals, redaction)
        if (truncated != null) payload.truncated = truncated
        payload
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        line(output, "Artifactory ${operation}")
        if (profile) line(output, "  Profile: ${profile}")
        if (server) line(output, "  Server: ${redaction.redactString(server)}")
        if (repository) line(output, "  Repository: ${repository}")
        if (path) line(output, "  Path: ${redaction.redactString(path)}")
        line(output, "  Fetched: ${fetchedAt}")
        line(output, "  Status: ${status.value}")
        if (filters) line(output, "  Filters: ${redactSummary(filters, redaction)}")
        if (totals) line(output, "  Totals: ${redactSummary(totals, redaction)}")
        if (truncated != null) line(output, "  Truncated: ${truncated}")
        if (message) line(output, "  Message: ${redaction.redactString(message)}")
        items.each { line(output, "  - ${pythonItemString(redactItem(it, redaction))}") }
        output.toString()
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = redactKeeping(item, redaction, KEPT_ITEM_KEYS)
        if (item.auth_scheme in ['bearer', 'api-key']) {
            redacted.auth_scheme = item.auth_scheme
        }
        if (item.source instanceof Map) {
            redacted.source = allowedSources((Map) item.source, ['env', 'properties', 'legacy', 'default'])
        }
        if (item.checksums instanceof Map) {
            redacted.checksums = new LinkedHashMap((Map) item.checksums)
        }
        redacted
    }

    private static Map redactSummary(Map value, Redaction redaction) {
        redactKeeping(value, redaction, KEPT_SUMMARY_KEYS)
    }
}
