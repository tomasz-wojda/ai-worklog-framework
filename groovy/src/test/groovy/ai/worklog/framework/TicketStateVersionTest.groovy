package ai.worklog.framework

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.core.StatePatch
import ai.worklog.framework.core.TicketStateValidator
import groovy.test.GroovyTestCase

class TicketStateVersionTest extends GroovyTestCase {
    private File repository
    private File workspace
    private FrameworkPaths paths
    private StateManager manager

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-test')
        new File(workspace, '.ai-worklog/catalog').mkdirs()
        new File(workspace, 'worklog/done').mkdirs()
        paths = new FrameworkPaths(workspace)
        manager = new StateManager(repository, paths)
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testDefaultStateHasSchemaVersionOne() {
        assertEquals(1, manager.defaultState('TEST-1').schema_version)
    }

    void testStateWithoutVersionLoadsAndSaveStampsVersionOne() {
        Map legacy = manager.defaultState('TEST-1')
        legacy.remove('schema_version')
        JsonFiles.write(paths.ticketStateFile('TEST-1'), legacy)
        Map loaded = manager.load('TEST-1')
        assertFalse(loaded.containsKey('schema_version'))
        manager.save(loaded)
        assertEquals(1, ((Map) JsonFiles.read(paths.ticketStateFile('TEST-1'))).schema_version)
    }

    void testUnsupportedVersionIsRejected() {
        Map future = manager.defaultState('TEST-1')
        future.schema_version = 99
        JsonFiles.write(paths.ticketStateFile('TEST-1'), future)
        String message = shouldFail(IllegalArgumentException) {
            manager.load('TEST-1')
        }
        assertTrue(message.startsWith('Unsupported ticket state schema_version 99 in '))
        assertTrue(message.endsWith('; supported: [1]'))
    }

    void testValidatorAcceptsSchemaVersion() {
        Map state = manager.defaultState('TEST-1')
        assertEquals([], new TicketStateValidator(repository).validate(state))
    }

    void testSchemaVersionIsImmutableThroughPatch() {
        Map state = manager.defaultState('TEST-1')
        String message = shouldFail(IllegalArgumentException) {
            new StatePatch(repository).applyPath(state, 'schema_version', 2)
        }
        assertEquals('Immutable state path: schema_version', message)
    }
}
