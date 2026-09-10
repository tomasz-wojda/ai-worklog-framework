package ai.worklog.framework.newrelic

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput

class NewRelicOperatorReport {
    String operation
    String fetchedAt
    Status status
    String profile
    String accountId
    String message
    String application
    String dashboard
    String policy
    String condition
    String page
    String widget
    String query
    Map filters
    Map totals
    Map change
    Map output
    Map definition
    Boolean dryRun
    Boolean applied
    Boolean truncated
    List<Map> items = []

    static NewRelicOperatorReport fromPayload(Map payload) {
        new NewRelicOperatorReport(
            operation: payload.operation?.toString(),
            fetchedAt: payload.fetched_at?.toString(),
            status: payload.status instanceof Status ? (Status) payload.status : Status.values().find {
                it.value == payload.status?.toString()
            } ?: Status.UNKNOWN,
            profile: payload.profile?.toString(),
            accountId: payload.account_id?.toString(),
            message: payload.message?.toString() ?: '',
            application: payload.application?.toString(),
            dashboard: payload.dashboard?.toString(),
            policy: payload.policy?.toString(),
            condition: payload.condition?.toString(),
            page: payload.page?.toString(),
            widget: payload.widget?.toString(),
            query: payload.query?.toString(),
            filters: payload.filters instanceof Map ? (Map) payload.filters : null,
            totals: payload.totals instanceof Map ? (Map) payload.totals : null,
            change: payload.change instanceof Map ? (Map) payload.change : null,
            output: payload.output instanceof Map ? (Map) payload.output : null,
            definition: payload.definition instanceof Map ? (Map) payload.definition : null,
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
        if (accountId) {
            payload.account_id = accountId
        }
        if (message) {
            payload.message = redaction.redact(message)?.toString()
        }
        if (application) {
            payload.application = application
        }
        if (dashboard) {
            payload.dashboard = dashboard
        }
        if (policy) {
            payload.policy = policy
        }
        if (condition) {
            payload.condition = condition
        }
        if (page) {
            payload.page = page
        }
        if (widget) {
            payload.widget = widget
        }
        if (query) {
            payload.query = redaction.redact(query)?.toString()
        }
        if (filters) {
            payload.filters = (Map) redaction.redact(filters)
        }
        if (totals) {
            payload.totals = (Map) redaction.redact(totals)
        }
        if (change) {
            payload.change = (Map) redaction.redact(change)
        }
        if (output) {
            payload.output = (Map) redaction.redact(output)
        }
        if (definition) {
            payload.definition = definition
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
        StringBuilder outputText = new StringBuilder()
        outputText.append("New Relic ${operation}").append(System.lineSeparator())
        if (profile) {
            outputText.append("  Profile: ${profile}").append(System.lineSeparator())
        }
        if (accountId) {
            outputText.append("  Account: ${accountId}").append(System.lineSeparator())
        }
        if (application) {
            outputText.append("  Application: ${application}").append(System.lineSeparator())
        }
        if (dashboard) {
            outputText.append("  Dashboard: ${dashboard}").append(System.lineSeparator())
        }
        if (policy) {
            outputText.append("  Policy: ${policy}").append(System.lineSeparator())
        }
        if (condition) {
            outputText.append("  Condition: ${condition}").append(System.lineSeparator())
        }
        if (page) {
            outputText.append("  Page: ${page}").append(System.lineSeparator())
        }
        if (widget) {
            outputText.append("  Widget: ${widget}").append(System.lineSeparator())
        }
        if (query) {
            outputText.append("  Query: ${redaction.redact(query)}").append(System.lineSeparator())
        }
        outputText.append("  Fetched: ${fetchedAt}").append(System.lineSeparator())
        outputText.append("  Status: ${status.value}").append(System.lineSeparator())
        if (dryRun != null) {
            outputText.append("  Dry run: ${dryRun}").append(System.lineSeparator())
        }
        if (applied != null) {
            outputText.append("  Applied: ${applied}").append(System.lineSeparator())
        }
        if (truncated != null) {
            outputText.append("  Truncated: ${truncated}").append(System.lineSeparator())
        }
        if (message) {
            outputText.append("  Message: ${redaction.redact(message)}").append(System.lineSeparator())
        }
        if (filters) {
            outputText.append("  Filters: ${redaction.redact(filters)}").append(System.lineSeparator())
        }
        if (totals) {
            outputText.append("  Totals: ${redaction.redact(totals)}").append(System.lineSeparator())
        }
        if (change) {
            outputText.append("  Change: ${redaction.redact(change)}").append(System.lineSeparator())
        }
        if (output) {
            outputText.append("  Output: ${redaction.redact(output)}").append(System.lineSeparator())
        }
        if (definition) {
            outputText.append("  Definition: ${definition}").append(System.lineSeparator())
        }
        items.each { item ->
            outputText.append("  - ${pythonItemString(redactItem(item, redaction))}").append(System.lineSeparator())
        }
        outputText.toString()
    }

    static int exitCodeFor(NewRelicOperatorReport report, ExitCodes exitCodes) {
        if (report.status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (report.status == Status.ERROR) {
            String lower = report.message?.toLowerCase() ?: ''
            if (lower.contains('not found') || lower.contains('invalid') ||
                lower.contains('missing') || lower.contains('ambiguous') ||
                lower.contains('confirmation') || lower.contains('must be inside workspace')) {
                return exitCodes.userError
            }
            return exitCodes.systemError
        }
        exitCodes.success
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

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = (Map) redaction.redact(item)
        ['has_api_key', 'enabled', 'applied', 'dry_run', 'truncated', 'id', 'guid',
         'policy_id', 'condition_id', 'account_id'].each { key ->
            if (item.containsKey(key)) {
                redacted[key] = item[key]
            }
        }
        if (item.source instanceof Map) {
            List<String> allowedSources = ['env', 'properties', 'legacy', 'default', 'derived']
            redacted.source = ((Map) item.source).collectEntries { key, value ->
                [(key): allowedSources.contains(value?.toString()) ? value.toString() : 'default']
            }
        }
        redacted
    }
}
