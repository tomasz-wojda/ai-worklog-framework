package ai.worklog.framework.core

class CheckResult {
    Status status
    String source
    String message
    Map detail

    boolean isOk() {
        status == Status.READY
    }

    boolean isActionable() {
        status in [Status.DEGRADED, Status.BLOCKED, Status.ERROR]
    }
}
