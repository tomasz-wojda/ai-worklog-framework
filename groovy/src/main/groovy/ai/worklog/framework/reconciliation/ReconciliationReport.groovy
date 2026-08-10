package ai.worklog.framework.reconciliation

import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.commands.DailyCommands
import groovy.json.JsonOutput

class Observation {
    String system
    String source
    Status status
    String message
    Map details = [:]
}

class Contradiction {
    String code
    String system
    Status severity
    String expected
    String observed
    String source
    String message = ''
}

class ReconciliationReport {
    String ticketKey
    String timestamp
    Status overallStatus = Status.UNKNOWN
    List<Observation> observations = []
    List<Contradiction> contradictions = []

    boolean hasErrorObservation() {
        observations.any { it.status == Status.ERROR }
    }

    boolean hasBlockingContradiction() {
        contradictions.any { it.severity == Status.BLOCKED }
    }

    Map toMap(Redaction redaction) {
        [
            ticket_key: ticketKey,
            timestamp: timestamp,
            overall_status: overallStatus.value,
            observations: sortedObservations().collect { observationMap(it, redaction) },
            contradictions: sortedContradictions().collect { contradictionMap(it) }
        ]
    }

    String renderHuman() {
        StringBuilder output = new StringBuilder()
        output.append('=' * 72).append(System.lineSeparator())
        output.append("  RECONCILIATION: ${ticketKey}").append(System.lineSeparator())
        output.append("  ${DailyCommands.timestamp()}").append(System.lineSeparator())
        output.append('=' * 72).append(System.lineSeparator())
        output.append(System.lineSeparator())
        output.append('OBSERVATIONS:').append(System.lineSeparator())
        if (observations) {
            sortedObservations().each {
                output.append("  ${statusIndicator(it.status)} ${it.source}: ${it.message}")
                    .append(System.lineSeparator())
            }
        } else {
            output.append('  (none)').append(System.lineSeparator())
        }
        output.append(System.lineSeparator())
        output.append('CONTRADICTIONS:').append(System.lineSeparator())
        if (contradictions) {
            sortedContradictions().each {
                output.append("  ${statusIndicator(it.severity)} ${it.source}: ${it.code} - ${it.message}")
                    .append(System.lineSeparator())
            }
        } else {
            output.append('  (none)').append(System.lineSeparator())
        }
        output.append(System.lineSeparator())
        output.append("OVERALL: ${overallStatus.value}").append(System.lineSeparator())
        output.append('=' * 72).append(System.lineSeparator())
        output.toString()
    }

    String renderJson(Redaction redaction) {
        JsonOutput.prettyPrint(JsonOutput.toJson(toMap(redaction))) + System.lineSeparator()
    }

    private static String statusIndicator(Status status) {
        [
            (Status.READY): '[OK]',
            (Status.DEGRADED): '[DEGRADED]',
            (Status.BLOCKED): '[BLOCKED]',
            (Status.ERROR): '[ERROR]',
            (Status.UNKNOWN): '[?]'
        ][status] ?: '[?]'
    }

    private List<Observation> sortedObservations() {
        observations.toList().sort { a, b ->
            int bySystem = a.system <=> b.system
            bySystem != 0 ? bySystem : a.source <=> b.source
        }
    }

    private List<Contradiction> sortedContradictions() {
        contradictions.toList().sort { a, b ->
            int bySystem = a.system <=> b.system
            if (bySystem != 0) {
                return bySystem
            }
            int bySource = a.source <=> b.source
            bySource != 0 ? bySource : a.code <=> b.code
        }
    }

    private static Map observationMap(Observation observation, Redaction redaction) {
        [
            system: observation.system,
            source: observation.source,
            status: observation.status.value,
            message: observation.message,
            details: redaction.redact(observation.details ?: [:])
        ]
    }

    private static Map contradictionMap(Contradiction contradiction) {
        [
            code: contradiction.code,
            system: contradiction.system,
            severity: contradiction.severity.value,
            expected: contradiction.expected,
            observed: contradiction.observed,
            source: contradiction.source,
            message: contradiction.message
        ]
    }
}
