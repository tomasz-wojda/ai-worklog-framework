package ai.worklog.framework.adapters

import ai.worklog.framework.core.Redaction

import java.util.concurrent.TimeUnit

class ReadOnlyProcess {
    Closure<Map> executeHandler
    Redaction redaction

    ReadOnlyProcess(Redaction redaction = null) {
        this.redaction = redaction
    }

    Map execute(List<String> command, int timeoutSeconds = 15) {
        if (executeHandler) {
            return executeHandler(command, timeoutSeconds)
        }
        validateArgv(command)
        try {
            Process process = new ProcessBuilder(command).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return [code: 124, out: '', err: 'Timed out']
            }
            [
                code: process.exitValue(),
                out: process.inputStream.getText('UTF-8'),
                err: redact(process.errorStream.getText('UTF-8'))
            ]
        } catch (IllegalArgumentException exception) {
            throw exception
        } catch (Exception exception) {
            [code: 127, out: '', err: redact(exception.message ?: exception.class.simpleName)]
        }
    }

    static void validateArgv(List<String> argv) {
        if (!argv || argv[0].startsWith('-')) {
            throw new IllegalArgumentException('Invalid command')
        }
        argv.each { part ->
            if (!part) {
                throw new IllegalArgumentException('Empty command argument')
            }
            if (part.indexOf('\u0000') >= 0 || part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) {
                throw new IllegalArgumentException('Invalid control character in command argument')
            }
        }
    }

    private String redact(String value) {
        value ? (redaction ? redaction.redactString(value) : value) : ''
    }
}
