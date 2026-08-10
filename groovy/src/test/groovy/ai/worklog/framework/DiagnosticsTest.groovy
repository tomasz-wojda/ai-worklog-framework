package ai.worklog.framework

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.diagnostics.DiagnosticExecutor
import groovy.test.GroovyTestCase

class DiagnosticsTest extends GroovyTestCase {
    File repository
    File workspace

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-test')
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testDiagnosticBlocksMissingParameters() {
        Map result = new DiagnosticExecutor(repository).runPack(
            'test-pack',
            [
                read_only: true,
                prerequisites: [],
                required_parameters: ['target'],
                steps: []
            ],
            [:],
            new FrameworkPaths(workspace),
            null
        )
        assertEquals('blocked', result.bundle.status)
        assertEquals('parameters', result.bundle.steps[0].id)
        assertTrue(result.path.isFile())
    }

    void testDiagnosticRefusesWriteCapablePack() {
        Map result = new DiagnosticExecutor(repository).runPack(
            'test-pack',
            [
                read_only: false,
                prerequisites: [],
                required_parameters: [],
                steps: []
            ],
            [:],
            new FrameworkPaths(workspace),
            null
        )
        assertEquals('blocked', result.bundle.status)
        assertEquals('safety', result.bundle.steps[0].id)
    }

    void testDiagnosticRejectsUnsafeParameter() {
        shouldFail(IllegalArgumentException) {
            new DiagnosticExecutor(repository).runPack(
                'test-pack',
                [
                    read_only: true,
                    prerequisites: [],
                    required_parameters: [],
                    steps: []
                ],
                [host: '-oProxyCommand=unsafe'],
                new FrameworkPaths(workspace),
                null
            )
        }
    }
}
