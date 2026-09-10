package ai.worklog.framework.adapters

class NewRelicCredentialException extends IllegalStateException {
    final String profile
    final String accountId

    NewRelicCredentialException(String message, String profile, String accountId) {
        super(message)
        this.profile = profile
        this.accountId = accountId
    }
}
