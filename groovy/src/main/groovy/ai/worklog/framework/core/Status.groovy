package ai.worklog.framework.core

enum Status {
    READY('ready'),
    DEGRADED('degraded'),
    BLOCKED('blocked'),
    ERROR('error'),
    UNKNOWN('unknown')

    final String value

    Status(String value) {
        this.value = value
    }
}
