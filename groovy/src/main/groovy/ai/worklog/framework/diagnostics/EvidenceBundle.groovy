package ai.worklog.framework.diagnostics

class EvidenceBundle {
    String pack
    String timestamp
    Map<String, String> parameters
    String status
    List<Map> steps = []

    Map toMap() {
        [
            pack: pack,
            timestamp: timestamp,
            parameters: parameters,
            status: status,
            steps: steps
        ]
    }
}
