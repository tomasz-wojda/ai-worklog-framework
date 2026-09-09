package ai.worklog.framework.jira

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput

class JiraOperatorReport {
    String operation
    String fetchedAt
    Status status
    String message
    String ticketKey
    String date
    String reporter
    boolean dryRun
    boolean applied
    boolean truncated
    Map totals
    List<Map> items = []

    static JiraOperatorReport fromPayload(Map payload) {
        new JiraOperatorReport(
            operation: payload.operation?.toString(),
            fetchedAt: payload.fetched_at?.toString(),
            status: payload.status instanceof Status ? (Status) payload.status : Status.values().find {
                it.value == payload.status?.toString()
            } ?: Status.UNKNOWN,
            message: payload.message?.toString() ?: '',
            ticketKey: payload.ticket_key?.toString(),
            date: payload.date?.toString(),
            reporter: payload.reporter?.toString(),
            dryRun: payload.dry_run as boolean,
            applied: payload.applied as boolean,
            truncated: payload.truncated as boolean,
            totals: payload.totals instanceof Map ? (Map) payload.totals : null,
            items: ((List) (payload.items ?: [])).collect {
                it instanceof Map ? new LinkedHashMap((Map) it) : [:]
            }
        )
    }

    Map toMap(Redaction redaction) {
        Map result = [
            operation: operation,
            fetched_at: fetchedAt,
            status: status.value,
            items: redaction.redact(items)
        ]
        if (message) {
            result.message = redaction.redact(message)
        }
        if (ticketKey) {
            result.ticket_key = ticketKey
        }
        if (date) {
            result.date = date
        }
        if (reporter) {
            result.reporter = reporter
        }
        if (operation == 'log-time') {
            result.dry_run = dryRun
            result.applied = applied
        }
        if (truncated) {
            result.truncated = true
        }
        if (totals != null) {
            result.totals = redaction.redact(totals)
        }
        result
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    String renderHuman(Redaction redaction) {
        StringBuilder output = new StringBuilder()
        output.append("Jira ${operation}").append(System.lineSeparator())
        if (ticketKey) {
            output.append("  Ticket: ${ticketKey}").append(System.lineSeparator())
        }
        if (date) {
            output.append("  Date: ${date}").append(System.lineSeparator())
        }
        if (reporter) {
            output.append("  Reporter: ${reporter}").append(System.lineSeparator())
        }
        output.append("  Fetched: ${fetchedAt}").append(System.lineSeparator())
        output.append("  Status: ${status.value}").append(System.lineSeparator())
        if (message) {
            output.append("  Message: ${redaction.redact(message)}").append(System.lineSeparator())
        }
        if (operation == 'log-time') {
            output.append("  Dry run: ${dryRun}").append(System.lineSeparator())
            output.append("  Applied: ${applied}").append(System.lineSeparator())
        }
        if (truncated) {
            output.append('  Truncated: true').append(System.lineSeparator())
        }
        if (totals != null) {
            output.append('  Totals: ')
                .append(JsonOutput.toJson(redaction.redact(totals)))
                .append(System.lineSeparator())
        }
        ((List) redaction.redact(items)).each { item ->
            output.append('  - ').append(JsonOutput.toJson(item)).append(System.lineSeparator())
        }
        output.toString()
    }

    static int exitCodeFor(JiraOperatorReport report, ExitCodes exitCodes) {
        if (report.status == Status.BLOCKED) {
            return exitCodes.blocked
        }
        if (report.status == Status.ERROR) {
            String lower = report.message?.toLowerCase() ?: ''
            if (lower.contains('not found') || lower.contains('invalid') ||
                lower.contains('must contain')) {
                return exitCodes.userError
            }
            return exitCodes.systemError
        }
        exitCodes.success
    }
}
