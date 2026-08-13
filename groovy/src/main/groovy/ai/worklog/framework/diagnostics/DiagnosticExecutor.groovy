package ai.worklog.framework.diagnostics

import ai.worklog.framework.commands.ToolchainCommands
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DiagnosticExecutor {
    final File frameworkRoot
    final Redaction redaction

    DiagnosticExecutor(File frameworkRoot) {
        this.frameworkRoot = frameworkRoot
        redaction = new Redaction(frameworkRoot)
    }

    Map runPack(
        String packId,
        Map pack,
        Map<String, String> parameters,
        FrameworkPaths paths,
        File output
    ) {
        validateParameters(parameters)
        Instant timestamp = Instant.now()
        List<String> missing = ((List) (pack.required_parameters ?: [])).findAll {
            !parameters[it.toString()]
        }.collect { it.toString() }
        boolean isWindows = System.getProperty("os.name")?.toLowerCase()?.contains("win")
        List<String> missingTools = ((List) (pack.prerequisites ?: [])).findAll {
            List<String> checkCmd = isWindows ? ['where.exe', it.toString()] : ['which', it.toString()]
            ToolchainCommands.execute(checkCmd).code != 0
        }.collect { it.toString() }
        List<Map> steps = []
        String status
        if (!pack.read_only || missing || missingTools) {
            status = 'blocked'
        } else {
            steps = ((List) (pack.steps ?: [])).collect {
                executeStep((Map) it, parameters)
            }
            status = steps.every { it.exit_code == 0 } ? 'success' : 'degraded'
        }
        if (missing) {
            steps << errorStep('parameters', 1, "Missing parameters: ${missing.join(', ')}")
        }
        if (missingTools) {
            steps << errorStep('prerequisites', 127, "Missing prerequisites: ${missingTools.join(', ')}")
        }
        if (!pack.read_only) {
            steps << errorStep('safety', 1, 'Write-capable diagnostic packs are refused')
        }
        EvidenceBundle bundle = new EvidenceBundle(
            pack: packId,
            timestamp: timestamp.toString(),
            parameters: (Map<String, String>) redaction.redact(parameters),
            status: status,
            steps: steps
        )
        File target = output ?: new File(
            paths.root,
            ".ai-worklog/evidence/${packId}-${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(timestamp)}.json"
        )
        if (output && !target.canonicalFile.parentFile.isDirectory()) {
            throw new IllegalArgumentException(
                "Output directory not found: ${target.canonicalFile.parentFile}"
            )
        }
        JsonFiles.write(target.canonicalFile, bundle.toMap())
        [bundle: bundle, path: target.canonicalFile]
    }

    Map executeStep(Map step, Map<String, String> parameters) {
        List<String> command = ((List) step.command).collect { part ->
            String value = part.toString()
            parameters.each { key, replacement ->
                value = value.replace("{${key}}", replacement)
            }
            value
        }
        long started = System.nanoTime()
        int exitCode
        String stdout
        String stderr
        try {
            Process process = new ProcessBuilder(command).start()
            boolean completed = process.waitFor(
                (step.timeout_seconds ?: 30) as long,
                TimeUnit.SECONDS
            )
            if (!completed) {
                process.destroyForcibly()
                exitCode = 124
                stdout = ''
                stderr = 'Timed out'
            } else {
                exitCode = process.exitValue()
                stdout = process.inputStream.getText('UTF-8')
                stderr = process.errorStream.getText('UTF-8')
            }
        } catch (IOException exception) {
            exitCode = 127
            stdout = ''
            stderr = exception.message ?: exception.class.simpleName
        }
        [
            id: step.id.toString(),
            command: command.collect { redaction.redactString(it) },
            exit_code: exitCode,
            duration_ms: ((System.nanoTime() - started) / 1_000_000L) as int,
            stdout: redaction.redactString(stdout),
            stderr: redaction.redactString(stderr)
        ]
    }

    static Map errorStep(String id, int code, String message) {
        [
            id: id,
            command: [],
            exit_code: code,
            duration_ms: 0,
            stdout: '',
            stderr: message
        ]
    }

    static void validateParameters(Map<String, String> parameters) {
        parameters.each { key, value ->
            if (value.contains('\u0000') || value.contains('\n') || value.contains('\r')) {
                throw new IllegalArgumentException(
                    "Invalid control character in parameter: ${key}"
                )
            }
            if (key == 'url') {
                URI uri
                try {
                    uri = new URI(value)
                } catch (URISyntaxException ignored) {
                    throw new IllegalArgumentException('URL parameter must be valid')
                }
                if (!(uri.scheme in ['http', 'https'])) {
                    throw new IllegalArgumentException(
                        'URL parameter must use http or https'
                    )
                }
            } else if (value.startsWith('-')) {
                throw new IllegalArgumentException(
                    "Parameter must not begin with '-': ${key}"
                )
            }
        }
    }
}
