package ai.worklog.framework

import ai.worklog.framework.commands.DashboardCommands
import ai.worklog.framework.core.DashboardNotifier
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import com.sun.net.httpserver.HttpServer
import groovy.test.GroovyTestCase
import java.util.concurrent.CopyOnWriteArrayList

class DashboardCommandsTest extends GroovyTestCase {
    private File workspace
    private FrameworkPaths paths
    private HttpServer server

    void setUp() {
        workspace = File.createTempDir('ai-worklog-', '-dashboard')
        new File(workspace, '.ai-worklog').mkdirs()
        paths = new FrameworkPaths(workspace)
    }

    void tearDown() {
        server?.stop(0)
        workspace.deleteDir()
    }

    private static Map capture(Closure<Integer> action) {
        PrintStream original = System.out
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        System.setOut(new PrintStream(buffer, true, 'UTF-8'))
        int code
        try {
            code = action.call()
        } finally {
            System.setOut(original)
        }
        [code: code, out: buffer.toString('UTF-8')]
    }

    private int startStub(Closure<String> body) {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            byte[] bytes = body.call(exchange).getBytes('UTF-8')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        server.address.port
    }

    private void writeInfo(int port, long pid = ProcessHandle.current().pid()) {
        JsonFiles.write(new File(workspace, '.ai-worklog/dashboard.json'),
            [pid: pid, port: port, started_at: '2026-10-30T10:00:00Z'])
    }

    void testUnknownActionPrintsUsage() {
        Map result = capture { DashboardCommands.run('bogus', [], paths) }
        assertEquals(1, result.code)
        assertTrue(result.out.contains('Usage: ai-worklog dashboard {start'))
        assertEquals(1, capture { DashboardCommands.run(null, [], paths) }.code)
    }

    void testStatusStopAndOpenWhenNotRunning() {
        assertEquals('Dashboard is not running\n', capture { DashboardCommands.status(false, paths) }.out)
        Map json = capture { DashboardCommands.status(true, paths) }
        assertEquals(1, json.code)
        assertEquals('{"running":false}', json.out.trim())
        assertEquals(1, capture { DashboardCommands.stop(paths) }.code)
        assertEquals(1, capture { DashboardCommands.open(paths) }.code)
    }

    void testStatusReadsHealthEndpointOfTheRunningDashboard() {
        int port = startStub { exchange ->
            assertEquals('/healthz', exchange.requestURI.path)
            '{"status":"ok","uptime_s":42,"tickets":16,"lanes":10,"clients":1,"last_jira_refresh":null}'
        }
        writeInfo(port)
        Map text = capture { DashboardCommands.status(false, paths) }
        assertEquals(0, text.code)
        assertTrue(text.out.contains("Dashboard running: http://127.0.0.1:${port}"))
        assertTrue(text.out.contains('tickets 16, lanes 10'))
        Map json = capture { DashboardCommands.status(true, paths) }
        Map parsed = (Map) new groovy.json.JsonSlurper().parseText(json.out)
        assertTrue(parsed.running)
        assertTrue(parsed.healthy)
        assertEquals(10, parsed.lanes)
    }

    void testStatusReportsUnhealthyWhenPortDoesNotAnswer() {
        ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName('127.0.0.1'))
        int port = free.localPort
        free.close()
        writeInfo(port)
        Map result = capture { DashboardCommands.status(false, paths) }
        assertEquals(2, result.code)
        assertTrue(result.out.contains('Health check failed'))
    }

    void testStaleInfoFileForDeadProcessCountsAsNotRunning() {
        writeInfo(9, 999_999_999L)
        assertNull(DashboardCommands.runningInfo(paths))
        assertEquals(1, capture { DashboardCommands.status(false, paths) }.code)
    }

    void testStartRefusesWhenAlreadyRunningOrNotBuiltOrBadPort() {
        writeInfo(8765)
        Map running = capture { DashboardCommands.run('start', [], paths) }
        assertEquals(1, running.code)
        assertTrue(running.out.contains('already running'))
        new File(workspace, '.ai-worklog/dashboard.json').delete()
        Map notBuilt = capture { DashboardCommands.start([], paths, new File(workspace, 'missing')) }
        assertEquals(1, notBuilt.code)
        assertTrue(notBuilt.out.contains('Dashboard is not built. Run: gradle -p'))
        File root = new File(workspace, 'scaleup')
        new File(root, 'build').mkdirs()
        new File(root, 'build/ai-worklog-scaleup.classpath').text = '/nowhere.jar\n'
        Map badPort = capture { DashboardCommands.start(['--port', 'abc'], paths, root) }
        assertEquals(1, badPort.code)
        assertTrue(badPort.out.contains('Invalid --port'))
    }

    void testNotifierPostsTheChangeToTheRunningDashboardAndIgnoresFailures() {
        List<Map> seen = new CopyOnWriteArrayList<>()
        int port = startStub { exchange ->
            seen << [method: exchange.requestMethod, path: exchange.requestURI.path,
                     header: exchange.requestHeaders.getFirst('X-Ai-Worklog'),
                     body: exchange.requestBody.getText('UTF-8')]
            ''
        }
        DashboardNotifier.notifyChange(paths, 'PROJ-1')
        assertTrue(seen.isEmpty())
        writeInfo(port)
        DashboardNotifier.notifyChange(paths, 'PROJ-1')
        assertEquals(1, seen.size())
        assertEquals('POST', seen[0].method)
        assertEquals('/events', seen[0].path)
        assertEquals('1', seen[0].header)
        assertEquals('{"ticket_key":"PROJ-1","kind":"state"}', seen[0].body)
        server.stop(0)
        DashboardNotifier.notifyChange(paths, 'PROJ-1')
    }
}
