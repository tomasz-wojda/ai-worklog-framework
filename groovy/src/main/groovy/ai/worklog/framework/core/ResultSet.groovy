package ai.worklog.framework.core

class ResultSet {
    final List<CheckResult> results = []

    void add(CheckResult result) {
        results << result
    }

    Status overallStatus() {
        List<Status> priority = [
            Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.UNKNOWN, Status.READY
        ]
        priority.find { level -> results.any { it.status == level } } ?: Status.UNKNOWN
    }

    boolean isOk() {
        overallStatus() == Status.READY
    }

    List<CheckResult> actionable() {
        results.findAll { it.actionable }
    }

    String summary() {
        Map<Status, String> indicators = [
            (Status.READY): '[OK]',
            (Status.DEGRADED): '[DEGRADED]',
            (Status.BLOCKED): '[BLOCKED]',
            (Status.ERROR): '[ERROR]',
            (Status.UNKNOWN): '[?]'
        ]
        results.collect {
            "  ${indicators[it.status]} ${it.source}: ${it.message}"
        }.join(System.lineSeparator())
    }
}
