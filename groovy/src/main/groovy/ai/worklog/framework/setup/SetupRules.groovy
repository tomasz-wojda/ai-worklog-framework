package ai.worklog.framework.setup

import ai.worklog.framework.core.Status
import groovy.json.JsonSlurper

import java.util.concurrent.TimeUnit

class SetupRules {
    static final String INSTALLER = 'scripts/install-cursor-harness.py'
    static final int TIMEOUT_SECONDS = 60

    static Closure<Map> runner = { List<String> command ->
        Process process = new ProcessBuilder(command).start()
        StringBuilder output = new StringBuilder()
        StringBuilder errors = new StringBuilder()
        Thread reader = Thread.start { output.append(process.inputStream.getText('UTF-8')) }
        Thread drain = Thread.start { errors.append(process.errorStream.getText('UTF-8')) }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return [code: -1, out: '', err: "timed out after ${TIMEOUT_SECONDS} seconds"]
        }
        reader.join()
        drain.join()
        [code: process.exitValue(), out: output.toString(), err: errors.toString()]
    }

    static Map run(File vaultRoot, File workspace, boolean apply) {
        File installer = vaultRoot ? new File(vaultRoot, INSTALLER) : null
        if (!installer?.isFile()) {
            return result(Status.UNKNOWN, 'AI vault has no workspace rules installer')
        }
        List<String> command = [
            System.getenv('AI_WORKLOG_PYTHON') ?: 'python3',
            installer.path,
            '--workspace-rules',
            workspace.canonicalPath
        ]
        if (apply) {
            command << '--apply'
        }
        Map response
        try {
            response = runner(command)
        } catch (IOException exception) {
            return result(Status.UNKNOWN, "Workspace rules installer unavailable: ${exception.message}")
        }
        Map payload
        try {
            payload = (Map) new JsonSlurper().parseText(response.out?.toString() ?: '')
        } catch (Exception ignored) {
            String detail = response.err?.toString()?.readLines()?.findAll { it.trim() }?.with { it ? it.last().trim() : '' }
            return result(
                Status.UNKNOWN,
                "Workspace rules installer failed (exit ${response.code}, ${command[0]})" +
                    (detail ? ": ${detail}" : '')
            )
        }
        if (payload.error) {
            return result(Status.BLOCKED, "Workspace rules not installed: ${payload.error}")
        }
        List<Map> actions = ((List) (payload.actions ?: [])).collect { Map action ->
            [
                kind: action.kind?.toString(),
                target: action.target?.toString(),
                status: action.status?.toString()
            ]
        }
        int pending = actions.count { it.status != 'unchanged' }
        if (apply || !pending) {
            return result(Status.READY, apply && pending ?
                "Installed ${pending} workspace rule file(s)" :
                'Workspace rules current', actions)
        }
        result(Status.DEGRADED, "${pending} workspace rule file(s) missing or outdated", actions)
    }

    static Map check(File vaultRoot, File workspace) {
        Map rules = run(vaultRoot, workspace, false)
        String status = rules.status == Status.UNKNOWN.value ? Status.DEGRADED.value : rules.status
        [layer: 'rules', status: status, message: rules.message]
    }

    private static Map result(Status status, String message, List<Map> actions = []) {
        [status: status.value, message: message, actions: actions]
    }
}
