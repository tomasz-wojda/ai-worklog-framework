package ai.worklog.framework.commands

import ai.worklog.framework.core.JsonFiles

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ToolchainCommands {
    static int run(String action, List<String> args, File frameworkRoot, Map config) {
        Map rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/toolchain-tools.json'),
            [tools: [:], compatibility: [:]]
        )
        Map toolchain = config.toolchain instanceof Map ? (Map) config.toolchain : [:]
        Map<Integer, Map> javaRuntimes = detectJava(toolchain)
        Map<Integer, Map> groovyRuntimes = detectGroovy(toolchain)

        switch (action) {
            case 'check':
                return check(rules, toolchain, javaRuntimes, groovyRuntimes)
            case 'list':
                list(rules, toolchain, javaRuntimes, groovyRuntimes)
                return 0
            case 'env':
                if (!args) {
                    println 'Usage: ai-worklog toolchain env <tool>'
                    return 1
                }
                return environment(args[0], rules, toolchain, javaRuntimes, groovyRuntimes)
            default:
                println 'Usage: ai-worklog toolchain {check|list|env}'
                return 1
        }
    }

    static int check(Map rules, Map config, Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes) {
        List<List<String>> rows = []
        rows << ['OK', 'python3', commandVersion(['python3', '--version']) ?: 'Not detected']
        javaRuntimes.each { major, runtime -> rows << ['OK', "java:${major}", runtime.version] }
        if (!javaRuntimes) rows << ['DEGRADED', 'java', 'No Java runtimes detected']
        groovyRuntimes.each { major, runtime ->
            rows << ['OK', "groovy:${major}", "${runtime.version} @ ${runtime.executable}"]
        }
        if (!groovyRuntimes) rows << ['DEGRADED', 'groovy', 'No Groovy runtimes detected']
        ((Map) rules.tools).each { name, spec ->
            Map resolved = resolve(name.toString(), rules, config, javaRuntimes, groovyRuntimes)
            rows << [resolved.ready ? 'OK' : 'BLOCKED', "tool:${name}", resolved.message]
        }
        rows.each { println "  [${it[0]}] ${it[1]}: ${it[2]}" }
        println()
        boolean ready = rows.every { it[0] == 'OK' }
        println "Toolchain: ${ready ? 'READY' : 'BLOCKED'}"
        ready ? 0 : 1
    }

    static void list(Map rules, Map config, Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes) {
        println 'Configured tools:'
        ((Map) rules.tools).each { name, spec ->
            String requirement = "Java ${spec.java}"
            if (spec.groovy != null) requirement += ", Groovy ${spec.groovy}+"
            println "  ${name}: ${requirement}"
            println "    ${spec.description}"
        }
        println()
        println 'Resolved environments:'
        ((Map) rules.tools).each { name, ignored ->
            Map resolved = resolve(name.toString(), rules, config, javaRuntimes, groovyRuntimes)
            println "  [${resolved.ready ? 'OK' : 'BLOCKED'}] ${name}: ${resolved.message}"
        }
    }

    static int environment(
        String name, Map rules, Map config,
        Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes
    ) {
        if (!((Map) rules.tools).containsKey(name)) {
            println "Unknown tool: ${name}"
            println "Available: ${((Map) rules.tools).keySet().sort().join(', ')}"
            return 1
        }
        Map resolved = resolve(name, rules, config, javaRuntimes, groovyRuntimes)
        if (!resolved.ready) {
            println "# BLOCKED: ${resolved.message}"
            return 1
        }
        println "# Environment for ${name}"
        println "export JAVA_HOME=${resolved.javaHome}"
        println 'export PATH=$JAVA_HOME/bin:$PATH'
        if (resolved.groovyExecutable) {
            println "# Groovy: ${resolved.groovyExecutable}"
        }
        0
    }

    static Map resolve(
        String name, Map rules, Map config,
        Map<Integer, Map> javaRuntimes, Map<Integer, Map> groovyRuntimes
    ) {
        Map spec = new LinkedHashMap((Map) rules.tools[name])
        Map overrides = config.tools instanceof Map ? (Map) config.tools : [:]
        if (overrides[name] instanceof Map) {
            spec.putAll((Map) overrides[name])
        }
        int javaMajor = spec.java as int
        Integer groovyMajor = spec.groovy != null ? spec.groovy as int : null
        Map javaRuntime = javaRuntimes[javaMajor]
        if (!javaRuntime) {
            return [ready: false, message: "Java ${javaMajor} not found"]
        }
        Map groovyRuntime = null
        if (groovyMajor != null) {
            groovyRuntime = groovyRuntimes[groovyMajor]
            if (!groovyRuntime) {
                List<Integer> candidates = groovyRuntimes.keySet()
                    .findAll { it >= groovyMajor }
                    .sort()
                if (candidates) groovyRuntime = groovyRuntimes[candidates[0]]
            }
            if (!groovyRuntime) {
                return [ready: false, message: "Groovy ${groovyMajor} not found for Java ${javaMajor}"]
            }
            Map bounds = (Map) rules.compatibility[groovyRuntime.major.toString()]
            if (bounds && !(javaMajor >= (bounds.min_java as int) && javaMajor <= (bounds.max_java as int))) {
                return [
                    ready: false,
                    message: "Incompatible: Groovy ${groovyRuntime.major} with Java ${javaMajor} " +
                        "(supported Java ${bounds.min_java}-${bounds.max_java})"
                ]
            }
        }
        List<String> message = ["Java ${javaMajor} @ ${javaRuntime.home}"]
        if (groovyRuntime) {
            message << "Groovy ${groovyRuntime.version} @ ${groovyRuntime.executable}"
        }
        [
            ready: true,
            message: message.join('; '),
            javaHome: javaRuntime.home,
            groovyExecutable: groovyRuntime?.executable
        ]
    }

    static Map<Integer, Map> detectJava(Map config) {
        Map<Integer, Map> runtimes = [:]
        Map configured = config.java instanceof Map ? (Map) config.java : [:]
        configured.each { key, path ->
            try {
                int major = key.toString().replace('java', '') as int
                File home = new File(path.toString()).canonicalFile
                String version = commandVersion([new File(home, 'bin/java').path, '-version'])
                if (version) runtimes[major] = [major: major, home: home.path, version: version]
            } catch (Exception ignored) {
            }
        }

        File javaHomeTool = new File('/usr/libexec/java_home')
        if (javaHomeTool.isFile()) {
            [17, 21, 25].each { major ->
                if (!runtimes[major]) {
                    Map result = execute([javaHomeTool.path, '-v', major.toString()])
                    if (result.code == 0 && result.out.trim()) {
                        File home = new File(result.out.trim())
                        String version = commandVersion([new File(home, 'bin/java').path, '-version'])
                        if (version && version =~ /"${major}(?:\.|")/) {
                            runtimes[major] = [major: major, home: home.path, version: version]
                        }
                    }
                }
            }
        }
        String current = commandVersion(['java', '-version'])
        if (current) {
            def match = current =~ /version "(\d+)/
            if (match.find()) {
                int major = match.group(1) as int
                if (!runtimes[major]) {
                    String home = System.getenv('JAVA_HOME') ?: new File(System.getProperty('java.home')).path
                    runtimes[major] = [major: major, home: home, version: current]
                }
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
        candidates.unique().each { executable ->
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
        String text = [result.out, result.err].findAll { it }.join(System.lineSeparator()).trim()
        text ? text.readLines()[0] : null
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
