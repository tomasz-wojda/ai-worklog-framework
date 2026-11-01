package ai.worklog.framework

import ai.worklog.framework.commands.MemoryCommands
import ai.worklog.framework.core.FrameworkPaths
import groovy.test.GroovyTestCase

class MemoryCommandsTest extends GroovyTestCase {
    private File workspace
    private FrameworkPaths paths
    private File ingester

    void setUp() {
        workspace = File.createTempDir('ai-worklog-', '-memory')
        new File(workspace, '.ai-worklog').mkdirs()
        paths = new FrameworkPaths(workspace)
        ingester = new File(workspace, 'repos/ai-memory-ingester')
        new File(ingester, 'service').mkdirs()
    }

    void tearDown() {
        workspace.deleteDir()
    }

    private void fake(File file, int exitCode) {
        file.text = "#!/bin/sh\necho \"\$(basename \$0) \$*\"\necho \"root=\$MEMORY_WORKSPACE_ROOT\"\nexit ${exitCode}\n"
        file.setExecutable(true)
    }

    private void installFakes(int serviceCode = 0, int ingesterCode = 0) {
        fake(new File(ingester, 'service/ai-memory-service'), serviceCode)
        fake(new File(ingester, 'ai-memory-ingester'), ingesterCode)
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

    void testUnknownActionPrintsUsage() {
        Map result = capture { MemoryCommands.run('bogus', [], paths) }
        assertEquals(1, result.code)
        assertTrue(result.out.contains('Usage: ai-worklog memory {serve'))
        assertEquals(1, capture { MemoryCommands.run(null, [], paths) }.code)
    }

    void testMissingInstallIsReported() {
        Map result = capture { MemoryCommands.run('status', [], paths) }
        assertEquals(1, result.code)
        assertTrue(result.out.contains('Not installed'))
        Map serve = capture { MemoryCommands.run('serve', [], paths) }
        assertEquals(1, serve.code)
        assertTrue(serve.out.contains('not installed'))
    }

    void testStatusStopAndDoctorDelegateToTheServiceWithTheWorkspaceRoot() {
        installFakes()
        ['status', 'stop', 'doctor'].each { String action ->
            Map result = capture { MemoryCommands.run(action, [], paths) }
            assertEquals(0, result.code)
            assertTrue(result.out, result.out.contains("ai-memory-service --workspace-root ${workspace.canonicalPath} ${action}"))
            assertTrue(result.out.contains("root=${workspace.canonicalPath}"))
        }
    }

    void testExitCodeOfTheServiceIsPassedThrough() {
        installFakes(1, 3)
        assertEquals(1, capture { MemoryCommands.run('status', [], paths) }.code)
        assertEquals(3, capture { MemoryCommands.run('reindex', ['--verify'], paths) }.code)
    }

    void testReindexAndEvalDelegateToTheIngester() {
        installFakes()
        Map reindex = capture { MemoryCommands.run('reindex', ['--verify', '--archives', 'chat:turns'], paths) }
        assertTrue(reindex.out, reindex.out.contains('ai-memory-ingester reindex --verify --archives chat:turns'))
        Map evaluation = capture { MemoryCommands.run('eval', ['--systems', 'S0,S1', '--json'], paths) }
        assertTrue(evaluation.out.contains('ai-memory-ingester eval --systems S0,S1 --json'))
    }

    void testServeRunsInTheForegroundAndPassesOnlyTheStubFlag() {
        installFakes()
        Map result = capture { MemoryCommands.run('serve', ['--stub', '--daemon-unknown'], paths) }
        assertEquals(0, result.code)
        assertTrue(result.out, result.out.contains("ai-memory-service --workspace-root ${workspace.canonicalPath} serve --stub"))
        assertFalse(result.out.contains('daemon-unknown'))
    }

    void testServeInitWritesTheConfigurationOnceAndRunsTheDoctor() {
        installFakes()
        Map first = capture { MemoryCommands.run('serve', ['--init'], paths) }
        File config = new File(workspace, '.ai-worklog/memory-service.json')
        assertTrue(config.isFile())
        assertTrue(first.out.contains('Configuration written'))
        assertTrue(first.out.contains('ai-memory-service --workspace-root'))
        Map parsed = new groovy.json.JsonSlurper().parse(config) as Map
        assertEquals('small-multilingual', parsed.rerank_model)
        assertEquals(1, parsed.relax_min_results)
        config.text = '{"rerank_model": "mine"}'
        Map second = capture { MemoryCommands.run('serve', ['--init'], paths) }
        assertTrue(second.out.contains('not changed'))
        assertEquals('{"rerank_model": "mine"}', config.text)
    }

    void testDaemonStartReportsAnEarlyExit() {
        installFakes()
        new File(ingester, 'service/ai-memory-service').text = '#!/bin/sh\nexit 7\n'
        Map result = capture { MemoryCommands.run('serve', ['--daemon'], paths) }
        assertEquals(1, result.code)
        assertTrue(result.out, result.out.contains('exited with code 7'))
    }
}
