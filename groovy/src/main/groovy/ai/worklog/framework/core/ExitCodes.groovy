package ai.worklog.framework.core

class ExitCodes {
    final int success
    final int userError
    final int systemError
    final int blocked

    ExitCodes(File frameworkRoot) {
        Map values = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/exit-codes.json'),
            [success: 0, user_error: 1, system_error: 2, blocked: 3]
        )
        success = values.success as int
        userError = values.user_error as int
        systemError = values.system_error as int
        blocked = values.blocked as int
    }
}
