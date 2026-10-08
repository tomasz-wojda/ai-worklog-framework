package ai.worklog.framework.automox

import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Redaction

class AutomoxOperatorReport extends OperatorReport {
    static final List<String> KEPT_ITEM_KEYS = [
        'has_api_token', 'has_enrollment_key', 'connected', 'compliant', 'needs_reboot',
        'applied', 'dry_run', 'installed', 'pending', 'truncated', 'id', 'server_group_id',
        'policy_id', 'device_id', 'success', 'failed', 'active'
    ]

    String profile
    String org
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

    static AutomoxOperatorReport fromPayload(Map payload) {
        new AutomoxOperatorReport(
            profile: payload.profile?.toString(),
            org: payload.org?.toString(),
            query: payload.query?.toString(),
            group: payload.group?.toString(),
            device: payload.device?.toString(),
            policy: payload.policy?.toString(),
            filters: mapOrNull(payload.filters),
            totals: mapOrNull(payload.totals),
            schedule: mapOrNull(payload.schedule),
            change: mapOrNull(payload.change),
            dryRun: flagOrNull(payload, 'dry_run'),
            applied: flagOrNull(payload, 'applied'),
            truncated: flagOrNull(payload, 'truncated')
        ).readCommon(payload)
    }

    protected List<String> userErrorMarkers() {
        ['not found', 'invalid', 'missing', 'ambiguous']
    }

    Map toMap(Redaction redaction) {
        Map payload = commonMap { redactItem(it, redaction) }
        if (profile) payload.profile = profile
        if (org) payload.org = org
        if (message) payload.message = message
        if (query) payload.query = query
        if (group) payload.group = group
        if (device) payload.device = device
        if (policy) payload.policy = policy
        if (filters) payload.filters = (Map) redaction.redact(filters)
        if (totals) payload.totals = (Map) redaction.redact(totals)
        if (schedule) payload.schedule = (Map) redaction.redact(schedule)
        if (change) payload.change = (Map) redaction.redact(change)
        if (dryRun != null) payload.dry_run = dryRun
        if (applied != null) payload.applied = applied
        if (truncated != null) payload.truncated = truncated
        payload
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        line(output, "Automox ${operation}")
        if (profile) line(output, "  Profile: ${profile}")
        if (org) line(output, "  Active org: ${org}")
        if (query) line(output, "  Query: ${query}")
        if (group) line(output, "  Group: ${group}")
        if (device) line(output, "  Device: ${device}")
        if (policy) line(output, "  Policy: ${policy}")
        line(output, "  Fetched: ${fetchedAt}")
        line(output, "  Status: ${status.value}")
        if (dryRun != null) line(output, "  Dry run: ${dryRun}")
        if (applied != null) line(output, "  Applied: ${applied}")
        if (truncated != null) line(output, "  Truncated: ${truncated}")
        if (message) line(output, "  Message: ${redaction.redact(message)}")
        if (filters) line(output, "  Filters: ${redaction.redact(filters)}")
        if (totals) line(output, "  Totals: ${redaction.redact(totals)}")
        if (schedule) {
            line(output, "  Schedule days: ${schedule.days}")
            line(output, "  Schedule weeks: ${schedule.weeks_of_month}")
            line(output, "  Schedule time: ${schedule.time}")
        }
        if (change) line(output, "  Change: ${redaction.redact(change)}")
        items.each { line(output, "  - ${pythonItemString(redactItem(it, redaction))}") }
        output.toString()
    }

    private static Map redactItem(Map item, Redaction redaction) {
        Map redacted = redactKeeping(item, redaction, KEPT_ITEM_KEYS)
        if (item.source instanceof Map) {
            redacted.source = allowedSources(
                (Map) item.source,
                ['argument', 'env', 'properties', 'legacy', 'default']
            )
        }
        redacted
    }
}
