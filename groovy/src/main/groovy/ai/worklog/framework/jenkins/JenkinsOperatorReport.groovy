package ai.worklog.framework.jenkins

import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Redaction

class JenkinsOperatorReport extends OperatorReport {
    static final List<String> KEPT_ITEM_KEYS = [
        'has_user', 'has_token', 'value_present', 'active', 'enabled', 'buildable', 'in_queue',
        'building', 'recent_failure', 'available', 'idle', 'offline', 'temporarily_offline',
        'stuck', 'blocked', 'truncated', 'authenticated', 'applied', 'dry_run', 'force',
        'replaced', 'run_scripts'
    ]

    String controller
    String domain
    String folder
    String query
    String view
    String job
    String buildSelector
    Map required
    String coreVersion
    Map updateCenter
    Map filter
    Map enrichment
    Map summary

    static JenkinsOperatorReport fromPayload(Map payload) {
        new JenkinsOperatorReport(
            controller: payload.controller?.toString(),
            domain: payload.domain?.toString(),
            folder: payload.folder?.toString(),
            query: payload.query?.toString(),
            view: payload.view?.toString(),
            job: payload.job?.toString(),
            buildSelector: payload.build_selector?.toString(),
            required: mapOrNull(payload.required),
            coreVersion: payload.core_version?.toString(),
            updateCenter: mapOrNull(payload.update_center),
            filter: mapOrNull(payload.filter),
            enrichment: mapOrNull(payload.enrichment),
            summary: mapOrNull(payload.summary)
        ).readCommon(payload)
    }

    protected List<String> userErrorMarkers() {
        ['not found', 'invalid', 'no files', 'missing']
    }

    Map toMap(Redaction redaction) {
        Map payload = commonMap { redactItem(it, redaction) }
        if (controller) payload.controller = controller
        if (message) payload.message = message
        if (domain) payload.domain = domain
        if (folder) payload.folder = folder
        if (query) payload.query = query
        if (view) payload.view = view
        if (job) payload.job = job
        if (buildSelector) payload.build_selector = buildSelector
        if (required) payload.required = required
        if (coreVersion) payload.core_version = coreVersion
        if (updateCenter) payload.update_center = updateCenter
        if (filter) payload.filter = filter
        if (enrichment) payload.enrichment = enrichment
        if (summary) payload.summary = summary
        payload
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        line(output, "Jenkins ${operation}")
        if (controller) line(output, "  Controller: ${controller}")
        if (folder) line(output, "  Folder: ${folder}")
        if (query) line(output, "  Query: ${query}")
        if (view) line(output, "  View: ${view}")
        if (job) line(output, "  Job: ${job}")
        if (buildSelector) line(output, "  Build selector: ${buildSelector}")
        line(output, "  Fetched: ${fetchedAt}")
        line(output, "  Status: ${status.value}")
        if (message) line(output, "  Message: ${redaction.redact(message)}")
        if (required?.requested) {
            Set verified = (required.requested as Set) -
                ((required.missing ?: []) as Set) -
                ((required.inactive ?: []) as Set)
            if (verified) {
                line(output, "  Verified required: ${verified.toList().sort().join(', ')}")
            }
        }
        if (operation == 'plugin-vulnerabilities') {
            renderVulnerabilities(output)
        } else if (operation == 'run-script') {
            items.each { renderRunScriptItem(output, redactItem(it, redaction)) }
        } else {
            items.each { line(output, "  - ${pythonItemString(redactItem(it, redaction))}") }
        }
        output.toString()
    }

    private void renderVulnerabilities(StringBuilder output) {
        if (coreVersion) line(output, "  Jenkins core: ${coreVersion}")
        Map totals = summary ?: [:]
        line(output, '  Summary: ' +
            "scanned=${totals.scanned ?: 0}, " +
            "affected=${totals.affected ?: 0}, " +
            "remediable=${totals.REMEDIABLE ?: 0}, " +
            "unfixable=${totals.UNFIXABLE ?: 0}, " +
            "blocked=${totals.BLOCKED ?: 0}")
        items.each { Map item ->
            String candidate = item.candidate_version ? " -> ${item.candidate_version}" : ''
            line(output, "  - ${item.short_name} ${item.installed_version}: ${item.remediation_status}${candidate}")
        }
    }

    private static void renderRunScriptItem(StringBuilder output, Map item) {
        line(output, "  Source: ${item.source}" + (item.path ? " ${item.path}" : ''))
        line(output, "  Script: ${item.bytes} bytes, sha256 ${item.sha256}")
        line(output, "  Applied: ${item.applied}")
        if (!item.containsKey('output')) {
            return
        }
        if (item.truncated) line(output, '  Truncated: true')
        line(output, '  Output:')
        String text = item.output?.toString() ?: ''
        output.append(text)
        if (text && !text.endsWith('\n')) {
            output.append(System.lineSeparator())
        }
    }

    private static Map redactItem(Map item, Redaction redaction) {
        redactKeeping(item, redaction, KEPT_ITEM_KEYS)
    }
}
