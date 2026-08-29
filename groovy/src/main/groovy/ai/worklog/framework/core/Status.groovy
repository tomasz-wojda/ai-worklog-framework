package ai.worklog.framework.core

enum Status {
    READY('ready'),
    DEGRADED('degraded'),
    BLOCKED('blocked'),
    ERROR('error'),
    UNKNOWN('unknown'),
    NOT_CONFIGURED('not_configured')

    final String value

    Status(String value) {
        this.value = value
    }
}
