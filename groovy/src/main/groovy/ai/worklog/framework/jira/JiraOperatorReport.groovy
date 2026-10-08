package ai.worklog.framework.jira

import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Redaction
import groovy.json.JsonGenerator
import groovy.json.JsonOutput

class JiraOperatorReport extends OperatorReport {
    private static final JsonGenerator HUMAN_JSON = new JsonGenerator.Options()
        .disableUnicodeEscaping()
        .build()

    String ticketKey
    String date
    String reporter
    String objectKey
    String query
    String env
    Integer schemaId
    Integer typeId
    boolean dryRun
    boolean applied
    boolean truncated
    Map totals

    static JiraOperatorReport fromPayload(Map payload) {
        new JiraOperatorReport(
            ticketKey: payload.ticket_key?.toString(),
            date: payload.date?.toString(),
            reporter: payload.reporter?.toString(),
            objectKey: payload.object_key?.toString(),
            query: payload.query?.toString(),
            env: payload.env?.toString(),
            schemaId: payload.schema_id == null ? null : payload.schema_id as Integer,
            typeId: payload.type_id == null ? null : payload.type_id as Integer,
            dryRun: payload.dry_run as boolean,
            applied: payload.applied as boolean,
            truncated: payload.truncated as boolean,
            totals: mapOrNull(payload.totals)
        ).readCommon(payload)
    }

    Map toMap(Redaction redaction) {
        Map result = commonMap { redaction.redact(it) }
        if (message) {
            result.message = redaction.redact(message)
        }
        if (errorKind) {
            result.error_kind = errorKind
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
        if (objectKey) {
            result.object_key = objectKey
        }
        if (query) {
            result.query = redaction.redact(query)
        }
        if (env) {
            result.env = env
        }
        if (schemaId != null) {
            result.schema_id = schemaId
        }
        if (typeId != null) {
            result.type_id = typeId
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
        if (objectKey && operation != 'get-ci') {
            output.append("  Object: ${objectKey}").append(System.lineSeparator())
        }
        if (query) {
            output.append("  Query: ${redaction.redact(query)}").append(System.lineSeparator())
        }
        if (schemaId != null) {
            output.append("  Schema: ${schemaId}").append(System.lineSeparator())
        }
        if (typeId != null) {
            output.append("  Type: ${typeId}").append(System.lineSeparator())
        }
        if (env) {
            output.append("  Env: ${env}").append(System.lineSeparator())
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
        List redacted = (List) redaction.redact(items)
        if (operation == 'get-ci') {
            redacted.each { Map item -> appendCi(output, item) }
        } else if (operation == 'get-cis') {
            int width = redacted.collect { it.key?.toString()?.size() ?: 0 }.max() ?: 0
            redacted.each { Map item ->
                output.append('  ')
                    .append(item.key.toString().padRight(width))
                    .append('  ')
                    .append(item.label)
                    .append(System.lineSeparator())
            }
        } else {
            redacted.each { item ->
                output.append('  - ').append(HUMAN_JSON.toJson(item)).append(System.lineSeparator())
            }
        }
        output.toString()
    }

    private static void appendCi(StringBuilder output, Map item) {
        output.append("  Object: ${item.key} / ${item.label}").append(System.lineSeparator())
        Map fields = (Map) (item.fields ?: [:])
        Map attributes = (Map) (item.field_attributes ?: [:])
        fields.each { field, value ->
            output.append("    ${attributes[field] ?: field}: ${value}").append(System.lineSeparator())
        }
    }
}
