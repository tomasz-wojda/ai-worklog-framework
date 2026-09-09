package ai.worklog.framework.cli

class UsageError extends IllegalArgumentException {
    final String command
    final String action

    UsageError(String message, String command = null, String action = null) {
        super(message)
        this.command = command
        this.action = action
    }
}
