package ai.worklog.framework.commands

import ai.worklog.framework.core.FrameworkPaths
import groovy.json.JsonOutput

class MemoryCommands {
    static final String USAGE =
        'Usage: ai-worklog memory {serve [--init] [--daemon] [--stub]|status|stop|doctor|reindex [options]|eval [options]}'

    static final Map DEFAULT_CONFIG = [
        contract_version: 1,
        rerank_model: 'small-multilingual',
        models: [:],
        late_model: null,
        default_deadline_ms: 1200,
        rerank_depth: 30,
        rerank_min_scored: 10,
        relax_min_results: 1,
        index_archives: ['chat:turns', 'worklog:chunks', 'worklog:state']
    ]

    static int run(String action, List<String> args, FrameworkPaths paths) {
        File ingesterRoot = ingesterRoot(paths)
        switch (action) {
            case 'serve':
                return serve(args, paths, ingesterRoot)
            case 'status':
            case 'stop':
            case 'doctor':
                return delegate(serviceLauncher(ingesterRoot), serviceArguments(paths, action, args), paths)
            case 'reindex':
            case 'eval':
                return delegate(ingesterLauncher(ingesterRoot), [action] + args, paths)
            default:
                println USAGE
                return 1
        }
    }

    static File ingesterRoot(FrameworkPaths paths) {
        String configured = System.getenv('AI_MEMORY_INGESTER_ROOT')
        configured ? new File(configured) : new File(paths.root, 'repos/ai-memory-ingester')
    }

    static File serviceLauncher(File ingesterRoot) {
        new File(ingesterRoot, 'service/ai-memory-service')
    }

    static File ingesterLauncher(File ingesterRoot) {
        new File(ingesterRoot, 'ai-memory-ingester')
    }

    static File configFile(FrameworkPaths paths) {
        new File(paths.configDir, 'memory-service.json')
    }

    static List<String> serviceArguments(FrameworkPaths paths, String action, List<String> args) {
        ['--workspace-root', paths.root.path, action] + args
    }

    static int serve(List<String> args, FrameworkPaths paths, File ingesterRoot) {
        File launcher = serviceLauncher(ingesterRoot)
        if (!launcher.canExecute()) {
            println "The memory service is not installed: ${launcher} not found"
            return 1
        }
        if (args.contains('--init')) {
            return init(paths, launcher)
        }
        List<String> serviceArgs = serviceArguments(paths, 'serve', args.findAll { it in ['--stub'] })
        if (!args.contains('--daemon')) {
            return delegate(launcher, serviceArgs, paths)
        }
        File logDir = new File(paths.configDir, 'logs')
        logDir.mkdirs()
        ProcessBuilder builder = processFor(launcher, serviceArgs, paths)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir, 'memory-service.log')))
        Process process = builder.start()
        long deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (!process.alive) {
                println "The memory service exited with code ${process.exitValue()}. See ${new File(logDir, 'memory-service.log')}"
                return 1
            }
            if (capture(launcher, serviceArguments(paths, 'status', []), paths).code == 0) {
                println "Memory service started (pid ${process.pid()})"
                return 0
            }
            Thread.sleep(200)
        }
        println 'The memory service did not report readiness in 10 seconds'
        1
    }

    static int init(FrameworkPaths paths, File launcher) {
        File file = configFile(paths)
        if (file.exists()) {
            println "Configuration exists, not changed: ${file}"
        } else {
            file.parentFile.mkdirs()
            file.setText(JsonOutput.prettyPrint(JsonOutput.toJson(DEFAULT_CONFIG)) + '\n', 'UTF-8')
            println "Configuration written: ${file}"
        }
        println 'Environment check:'
        delegate(launcher, serviceArguments(paths, 'doctor', []), paths)
    }

    static ProcessBuilder processFor(File launcher, List<String> arguments, FrameworkPaths paths) {
        ProcessBuilder builder = new ProcessBuilder([launcher.path] + arguments)
        builder.directory(paths.root)
        builder.environment().put('MEMORY_WORKSPACE_ROOT', paths.root.path)
        builder
    }

    static int delegate(File launcher, List<String> arguments, FrameworkPaths paths) {
        if (!launcher.canExecute()) {
            println "Not installed: ${launcher} not found"
            return 1
        }
        ProcessBuilder builder = processFor(launcher, arguments, paths)
        builder.redirectErrorStream(true)
        Process process = builder.start()
        process.inputStream.eachLine('UTF-8') { String line -> println line }
        process.waitFor()
    }

    private static Map capture(File launcher, List<String> arguments, FrameworkPaths paths) {
        ProcessBuilder builder = processFor(launcher, arguments, paths)
        builder.redirectErrorStream(true)
        Process process = builder.start()
        String text = process.inputStream.getText('UTF-8')
        [code: process.waitFor(), out: text]
    }
}
