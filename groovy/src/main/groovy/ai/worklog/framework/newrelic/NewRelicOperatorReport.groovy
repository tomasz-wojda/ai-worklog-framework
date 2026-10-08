package ai.worklog.framework.newrelic

import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Redaction

class NewRelicOperatorReport extends OperatorReport {
    static final List<String> KEPT_ITEM_KEYS = [
        'has_api_key', 'enabled', 'applied', 'dry_run', 'truncated', 'id', 'guid',
        'policy_id', 'condition_id', 'account_id'
    ]

    String profile
    String accountId
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

    static NewRelicOperatorReport fromPayload(Map payload) {
        new NewRelicOperatorReport(
            profile: payload.profile?.toString(),
            accountId: payload.account_id?.toString(),
            application: payload.application?.toString(),
            dashboard: payload.dashboard?.toString(),
            policy: payload.policy?.toString(),
            condition: payload.condition?.toString(),
            page: payload.page?.toString(),
            widget: payload.widget?.toString(),
            query: payload.query?.toString(),
            filters: mapOrNull(payload.filters),
            totals: mapOrNull(payload.totals),
            change: mapOrNull(payload.change),
            output: mapOrNull(payload.output),
            definition: mapOrNull(payload.definition),
            dryRun: flagOrNull(payload, 'dry_run'),
            applied: flagOrNull(payload, 'applied'),
            truncated: flagOrNull(payload, 'truncated')
        ).readCommon(payload)
    }

    protected List<String> userErrorMarkers() {
        ['not found', 'invalid', 'missing', 'ambiguous', 'confirmation', 'must be inside workspace']
    }

    Map toMap(Redaction redaction) {
        Map payload = commonMap { redactItem(it, redaction) }
        if (profile) payload.profile = profile
        if (accountId) payload.account_id = accountId
        if (message) payload.message = redaction.redact(message)?.toString()
        if (application) payload.application = application
        if (dashboard) payload.dashboard = dashboard
        if (policy) payload.policy = policy
        if (condition) payload.condition = condition
        if (page) payload.page = page
        if (widget) payload.widget = widget
        if (query) payload.query = redaction.redact(query)?.toString()
        if (filters) payload.filters = (Map) redaction.redact(filters)
        if (totals) payload.totals = (Map) redaction.redact(totals)
        if (change) payload.change = (Map) redaction.redact(change)
        if (output) payload.output = (Map) redaction.redact(output)
        if (definition) payload.definition = definition
        if (dryRun != null) payload.dry_run = dryRun
        if (applied != null) payload.applied = applied
        if (truncated != null) payload.truncated = truncated
        payload
    }

    String renderHuman(Redaction redaction) {
        StringBuilder text = new StringBuilder()
        line(text, "New Relic ${operation}")
        if (profile) line(text, "  Profile: ${profile}")
        if (accountId) line(text, "  Account: ${accountId}")
        if (application) line(text, "  Application: ${application}")
        if (dashboard) line(text, "  Dashboard: ${dashboard}")
        if (policy) line(text, "  Policy: ${policy}")
        if (condition) line(text, "  Condition: ${condition}")
        if (page) line(text, "  Page: ${page}")
        if (widget) line(text, "  Widget: ${widget}")
        if (query) line(text, "  Query: ${redaction.redact(query)}")
        line(text, "  Fetched: ${fetchedAt}")
        line(text, "  Status: ${status.value}")
        if (dryRun != null) line(text, "  Dry run: ${dryRun}")
        if (applied != null) line(text, "  Applied: ${applied}")
        if (truncated != null) line(text, "  Truncated: ${truncated}")
        if (message) line(text, "  Message: ${redaction.redact(message)}")
        if (filters) line(text, "  Filters: ${redaction.redact(filters)}")
        if (totals) line(text, "  Totals: ${redaction.redact(totals)}")
        if (change) line(text, "  Change: ${redaction.redact(change)}")
        if (output) line(text, "  Output: ${redaction.redact(output)}")
        if (definition) line(text, "  Definition: ${definition}")
        items.each { line(text, "  - ${pythonItemString(redactItem(it, redaction))}") }
        text.toString()
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = redactKeeping(item, redaction, KEPT_ITEM_KEYS)
        if (item.source instanceof Map) {
            redacted.source = allowedSources(
                (Map) item.source,
                ['env', 'properties', 'legacy', 'default', 'derived']
            )
        }
        redacted
    }
}
