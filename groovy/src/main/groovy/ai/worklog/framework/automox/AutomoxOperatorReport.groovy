package ai.worklog.framework.automox

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput

class AutomoxOperatorReport {
    String operation
    String fetchedAt
    Status status
    String profile
    String org
    String message
    String query
    String group
    String device
    String policy
    Map filters
    Map totals
    Map schedule
    Map change
    Boolean dryRun
    Boolean applied
    Boolean truncated
    List<Map> items = []

    static AutomoxOperatorReport fromPayload(Map payload) {
        new AutomoxOperatorReport(
            operation: payload.operation?.toString(),
            fetchedAt: payload.fetched_at?.toString(),
            status: payload.status instanceof Status ? (Status) payload.status : Status.values().find {
                it.value == payload.status?.toString()
            } ?: Status.UNKNOWN,
            profile: payload.profile?.toString(),
            org: payload.org?.toString(),
            message: payload.message?.toString() ?: '',
            query: payload.query?.toString(),
            group: payload.group?.toString(),
            device: payload.device?.toString(),
            policy: payload.policy?.toString(),
            filters: payload.filters instanceof Map ? (Map) payload.filters : null,
            totals: payload.totals instanceof Map ? (Map) payload.totals : null,
            schedule: payload.schedule instanceof Map ? (Map) payload.schedule : null,
            change: payload.change instanceof Map ? (Map) payload.change : null,
            dryRun: payload.containsKey('dry_run') ? payload.dry_run as Boolean : null,
            applied: payload.containsKey('applied') ? payload.applied as Boolean : null,
            truncated: payload.containsKey('truncated') ? payload.truncated as Boolean : null,
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
        if (profile) {
            payload.profile = profile
        }
        if (org) {
            payload.org = org
        }
        if (message) {
            payload.message = message
        }
        if (query) {
            payload.query = query
        }
        if (group) {
            payload.group = group
        }
        if (device) {
            payload.device = device
        }
        if (policy) {
            payload.policy = policy
        }
        if (filters) {
            payload.filters = (Map) redaction.redact(filters)
        }
        if (totals) {
            payload.totals = (Map) redaction.redact(totals)
        }
        if (schedule) {
            payload.schedule = (Map) redaction.redact(schedule)
        }
        if (change) {
            payload.change = (Map) redaction.redact(change)
        }
        if (dryRun != null) {
            payload.dry_run = dryRun
        }
        if (applied != null) {
            payload.applied = applied
        }
        if (truncated != null) {
            payload.truncated = truncated
        }
        payload
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        output.append("Automox ${operation}").append(System.lineSeparator())
        if (profile) {
            output.append("  Profile: ${profile}").append(System.lineSeparator())
        }
        if (org) {
            output.append("  Org: ${org}").append(System.lineSeparator())
        }
        if (query) {
            output.append("  Query: ${query}").append(System.lineSeparator())
        }
        if (group) {
            output.append("  Group: ${group}").append(System.lineSeparator())
        }
        if (device) {
            output.append("  Device: ${device}").append(System.lineSeparator())
        }
        if (policy) {
            output.append("  Policy: ${policy}").append(System.lineSeparator())
        }
        output.append("  Fetched: ${fetchedAt}").append(System.lineSeparator())
        output.append("  Status: ${status.value}").append(System.lineSeparator())
        if (dryRun != null) {
            output.append("  Dry run: ${dryRun}").append(System.lineSeparator())
        }
        if (applied != null) {
            output.append("  Applied: ${applied}").append(System.lineSeparator())
        }
        if (truncated != null) {
            output.append("  Truncated: ${truncated}").append(System.lineSeparator())
        }
        if (message) {
            output.append("  Message: ${redaction.redact(message)}").append(System.lineSeparator())
        }
        if (filters) {
            output.append("  Filters: ${redaction.redact(filters)}").append(System.lineSeparator())
        }
        if (totals) {
            output.append("  Totals: ${redaction.redact(totals)}").append(System.lineSeparator())
        }
        if (schedule) {
            output.append("  Schedule days: ${schedule.days}").append(System.lineSeparator())
            output.append("  Schedule weeks: ${schedule.weeks_of_month}").append(System.lineSeparator())
            output.append("  Schedule time: ${schedule.time}").append(System.lineSeparator())
        }
        if (change) {
            output.append("  Change: ${redaction.redact(change)}").append(System.lineSeparator())
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

    static int exitCodeFor(AutomoxOperatorReport report, ExitCodes exitCodes) {
        if (report.status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (report.status == Status.ERROR) {
            String lower = report.message?.toLowerCase() ?: ''
            if (lower.contains('not found') || lower.contains('invalid') ||
                lower.contains('missing') || lower.contains('ambiguous')) {
                return exitCodes.userError
            }
            return exitCodes.systemError
        }
        exitCodes.success
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = (Map) redaction.redact(item)
        ['has_api_token', 'has_enrollment_key', 'connected', 'compliant', 'needs_reboot',
         'applied', 'dry_run', 'installed', 'pending', 'truncated', 'id', 'server_group_id',
         'policy_id', 'device_id', 'success', 'failed'].each { key ->
            if (item.containsKey(key)) {
                redacted[key] = item[key]
            }
        }
        if (item.source instanceof Map) {
            List<String> allowedSources = ['env', 'properties', 'legacy', 'default']
            redacted.source = ((Map) item.source).collectEntries { key, value ->
                [(key): allowedSources.contains(value?.toString()) ? value.toString() : 'default']
            }
        }
        redacted
    }
}
