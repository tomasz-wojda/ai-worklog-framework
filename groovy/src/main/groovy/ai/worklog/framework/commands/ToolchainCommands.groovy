package ai.worklog.framework.commands

import java.util.concurrent.TimeUnit

class ToolchainCommands {
    static int run(String action, List<String> args, File frameworkRoot, Map config) {
        Map toolchain = config.toolchain instanceof Map ? (Map) config.toolchain : [:]
        Map<Integer, Map> javaRuntimes = detectJava(toolchain)
        Map<Integer, Map> groovyRuntimes = detectGroovy(toolchain)

        switch (action) {
            case 'check':
                return check(javaRuntimes, groovyRuntimes)
            case 'list':
                list(javaRuntimes, groovyRuntimes)
                return 0
            default:
                println 'Usage: ai-worklog toolchain {check|list}'
                return 1
        }
    }

    static String detectPythonVersion() {
        commandVersion(['python3', '--version']) ?:
            commandVersion(['python', '--version']) ?:
            commandVersion(['py', '-3', '--version'])
    }

    static int check(Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes) {
        List<List<String>> rows = []
        String pythonVersion = detectPythonVersion()
        rows << [pythonVersion ? 'OK' : 'BLOCKED', 'python3', pythonVersion ?: 'Not detected']
        javaRuntimes.each { major, runtime -> rows << ['OK', "java:${major}", runtime.version] }
        if (!javaRuntimes) rows << ['DEGRADED', 'java', 'No Java runtimes detected']
        groovyRuntimes.each { major, runtime ->
            rows << ['OK', "groovy:${major}", "${runtime.version} @ ${runtime.executable}"]
        }
        if (!groovyRuntimes) rows << ['DEGRADED', 'groovy', 'No Groovy runtimes detected']
        rows.each { println "  [${it[0]}] ${it[1]}: ${it[2]}" }
        println()
        boolean ready = rows.every { it[0] == 'OK' }
        println "Toolchain: ${ready ? 'READY' : 'BLOCKED'}"
        ready ? 0 : 1
    }

    static void list(Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes) {
        println 'Detected runtimes:'
        String pythonVersion = detectPythonVersion()
        println "  [${pythonVersion ? 'OK' : 'BLOCKED'}] python3: ${pythonVersion ?: 'Not detected'}"
        javaRuntimes.each { major, runtime ->
            println "  [OK] java:${major}: ${runtime.version}"
        }
        if (!javaRuntimes) println '  [DEGRADED] java: No Java runtimes detected'
        groovyRuntimes.each { major, runtime ->
            println "  [OK] groovy:${major}: ${runtime.version} @ ${runtime.executable}"
        }
        if (!groovyRuntimes) println '  [DEGRADED] groovy: No Groovy runtimes detected'
    }

    static Map<Integer, Map> detectJava(Map config) {
        Map<Integer, Map> runtimes = [:]
        String current = commandVersion(['java', '-version'])
        if (current) {
            def match = current =~ /version "(\d+)/
            if (match.find()) {
                int major = match.group(1) as int
                String home = System.getenv('JAVA_HOME') ?: new File(System.getProperty('java.home')).path
                runtimes[major] = [major: major, home: home, version: current]
            }
        }
        runtimes
    }

    static Map<Integer, Map> detectGroovy(Map config) {
        Map<Integer, Map> runtimes = [:]
        List<String> candidates = []
        Map configured = config.groovy instanceof Map ? (Map) config.groovy : [:]
        configured.values().each { candidates << it.toString() }
        candidates << 'groovy'
        boolean isWindows = System.getProperty('os.name')?.toLowerCase()?.contains('win')
        if (isWindows) {
            candidates << 'groovy.bat'
            candidates << 'groovy.cmd'
        }
        candidates.unique().each { executable ->
            if (executable.contains('/') || executable.contains('\\')) {
                if (!new File(executable).isFile()) return
            }
            String version = commandVersion([executable, '--version'])
            if (version) {
                def match = version =~ /Groovy Version:\s*(\d+(?:\.\d+)*)/
                if (match.find()) {
                    String value = match.group(1)
                    int major = value.tokenize('.')[0] as int
                    runtimes[major] = [
                        major: major,
                        executable: executable,
                        version: value
                    ]
                }
            }
        }
        runtimes
    }

    static String commandVersion(List<String> command) {
        Map result = execute(command)
        if (result.code != 0) {
            return null
        }
        String text = [result.out, result.err].findAll { it }.join(System.lineSeparator()).trim()
        if (!text) return null
        String firstLine = text.readLines()[0]
        if (firstLine.toLowerCase().contains('nie znaleziono') || firstLine.toLowerCase().contains('not found')) {
            return null
        }
        firstLine
    }

    static Map execute(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return [code: 127, out: '', err: 'timeout']
            }
            [
                code: process.exitValue(),
                out: process.inputStream.getText('UTF-8'),
                err: process.errorStream.getText('UTF-8')
            ]
        } catch (Exception exception) {
            [code: 127, out: '', err: exception.message ?: exception.class.simpleName]
        }
    }
}
