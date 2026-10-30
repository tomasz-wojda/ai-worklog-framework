package ai.worklog.framework.commands

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import groovy.json.JsonOutput
import java.util.concurrent.TimeUnit

class DashboardCommands {
    static final String USAGE =
        'Usage: ai-worklog dashboard {start [--port N] [--no-open] [--daemon]|status [--json]|stop|open}'

    static int run(String action, List<String> args, FrameworkPaths paths, Closure<Map> environment = null) {
        File scaleupRoot = scaleupRoot(paths)
        switch (action) {
            case 'start':
                return start(args, paths, scaleupRoot)
            case 'status':
                return status(args.contains('--json'), paths)
            case 'stop':
                return stop(paths)
            case 'open':
                return open(paths)
            default:
                println USAGE
                return 1
        }
    }

    static File scaleupRoot(FrameworkPaths paths) {
        String configured = System.getenv('AI_WORKLOG_SCALEUP_ROOT')
        configured ? new File(configured) : new File(paths.root, 'repos/ai-worklog-scaleup')
    }

    static File infoFile(FrameworkPaths paths) {
        new File(paths.configDir, 'dashboard.json')
    }

    static Map runningInfo(FrameworkPaths paths) {
        Object info = JsonFiles.read(infoFile(paths), [:])
        if (!(info instanceof Map) || !info.pid || !info.port) {
            return null
        }
        boolean alive = ProcessHandle.of(info.pid as long).map { it.isAlive() }.orElse(false)
        alive ? (Map) info : null
    }

    static int start(List<String> args, FrameworkPaths paths, File scaleupRoot) {
        Map running = runningInfo(paths)
        if (running) {
            println "Dashboard already running: http://127.0.0.1:${running.port} (pid ${running.pid})"
            return 1
        }
        File classpathFile = new File(scaleupRoot, 'build/ai-worklog-scaleup.classpath')
        if (!classpathFile.isFile()) {
            println "Dashboard is not built. Run: gradle -p ${scaleupRoot} jar"
            return 1
        }
        String port = option(args, '--port')
        if (port != null && !(port ==~ /\d{1,5}/)) {
            println 'Invalid --port value'
            return 1
        }
        List<String> command = [
            javaExecutable(), '-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '--enable-native-access=ALL-UNNAMED',
            '-cp', classpathFile.getText('UTF-8').trim(), 'ai.worklog.scaleup.server.DashboardMain',
            '--workspace', paths.root.path, '--no-open'
        ]
        if (port != null) {
            command += ['--port', port]
        }
        ProcessBuilder builder = new ProcessBuilder(command)
        builder.directory(paths.root)
        if (!args.contains('--daemon')) {
            builder.inheritIO()
            Process process = builder.start()
            if (!args.contains('--no-open')) {
                openBrowserWhenReady(paths)
            }
            return process.waitFor()
        }
        File logDir = new File(paths.configDir, 'logs')
        logDir.mkdirs()
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir, 'dashboard.log')))
        Process process = builder.start()
        long deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            Map info = runningInfo(paths)
            if (info) {
                println "Dashboard started: http://127.0.0.1:${info.port} (pid ${info.pid})"
                if (!args.contains('--no-open')) {
                    browse("http://127.0.0.1:${info.port}")
                }
                return 0
            }
            if (!process.alive) {
                println "Dashboard exited with code ${process.exitValue()}. See ${new File(logDir, 'dashboard.log')}"
                return 2
            }
            Thread.sleep(200)
        }
        println 'Dashboard did not report readiness in 15 seconds'
        2
    }

    static void openBrowserWhenReady(FrameworkPaths paths) {
        Thread.startDaemon('dashboard-open') {
            long deadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < deadline) {
                Map info = runningInfo(paths)
                if (info) {
                    browse("http://127.0.0.1:${info.port}")
                    return
                }
                Thread.sleep(200)
            }
        }
    }

    static int status(boolean json, FrameworkPaths paths) {
        Map info = runningInfo(paths)
        if (!info) {
            println json ? JsonOutput.toJson([running: false]) : 'Dashboard is not running'
            return 1
        }
        Map health = health(info.port as int)
        Map result = [running: true, url: "http://127.0.0.1:${info.port}".toString(), pid: info.pid,
                      started_at: info.started_at, healthy: health != null] + (health ?: [:])
        if (json) {
            println JsonOutput.toJson(result)
        } else {
            println "Dashboard running: ${result.url} (pid ${result.pid})"
            if (health) {
                println "  uptime ${health.uptime_s}s, tickets ${health.tickets}, lanes ${health.lanes}, clients ${health.clients}"
                println "  last Jira refresh: ${health.last_jira_refresh ?: 'none yet'}"
            } else {
                println '  Health check failed'
            }
        }
        health ? 0 : 2
    }

    static Map health(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:${port}/healthz").openConnection()
            connection.connectTimeout = 1000
            connection.readTimeout = 2000
            if (connection.responseCode != 200) {
                return null
            }
            Object body = new groovy.json.JsonSlurper().parseText(connection.inputStream.getText('UTF-8'))
            body instanceof Map ? (Map) body : null
        } catch (Exception ignored) {
            null
        }
    }

    static int stop(FrameworkPaths paths) {
        Map info = runningInfo(paths)
        if (!info) {
            println 'Dashboard is not running'
            return 1
        }
        ProcessHandle handle = ProcessHandle.of(info.pid as long).get()
        handle.destroy()
        try {
            handle.onExit().get(10, TimeUnit.SECONDS)
        } catch (Exception ignored) {
            handle.destroyForcibly()
        }
        infoFile(paths).delete()
        println "Dashboard stopped (pid ${info.pid})"
        0
    }

    static int open(FrameworkPaths paths) {
        Map info = runningInfo(paths)
        if (!info) {
            println 'Dashboard is not running'
            return 1
        }
        browse("http://127.0.0.1:${info.port}")
        0
    }

    static void browse(String url) {
        try {
            new ProcessBuilder('open', url).start()
        } catch (IOException ignored) {
            println url
        }
    }

    static String javaExecutable() {
        String home = System.getenv('JAVA_HOME')
        home && new File(home, 'bin/java').canExecute() ? new File(home, 'bin/java').path :
            new File(System.getProperty('java.home'), 'bin/java').path
    }

    static String option(List<String> args, String name) {
        int index = args.indexOf(name)
        index >= 0 && index + 1 < args.size() ? args[index + 1] : null
    }
}
