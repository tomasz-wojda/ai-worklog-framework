package ai.worklog.framework

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.core.StatePatch
import ai.worklog.framework.core.TicketStateValidator
import ai.worklog.framework.workspace.WorkspacePlanner
import groovy.test.GroovyTestCase

class WorkspaceStateTest extends GroovyTestCase {
    File repository
    File workspace

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-test')
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testWorkspaceInitAndRevert() {
        new File(workspace, 'jira').mkdir()
        WorkspacePlanner planner = new WorkspacePlanner(repository)
        WorkspacePlanner.apply(planner.planInit(workspace))
        assertTrue(new File(workspace, '.ai-worklog/state').isDirectory())
        assertTrue(new File(workspace, '.ai-worklog/config.json').isFile())
        assertTrue(
            java.nio.file.Files.isSymbolicLink(
                new File(workspace, 'worklog/interface/jira').toPath()
            )
        )
        assertTrue(planner.planInit(workspace).every { it.skip })
        WorkspacePlanner.apply(planner.planRevert(workspace))
        assertFalse(new File(workspace, 'worklog/interface/jira').exists())
    }

    void testValidatedStatePatchAndAtomicSave() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        StateManager manager = new StateManager(repository, paths)
        Map state = manager.defaultState('TEST-1')
        StatePatch patch = new StatePatch(repository)
        assertEquals('not_started', patch.applyPath(
            state,
            'implementation.state',
            'in_progress'
        ))
        assertEquals([], new TicketStateValidator(repository).validate(state))
        manager.save(state)
        assertEquals('in_progress', manager.load('TEST-1').implementation.state)
        assertEquals([], paths.stateDir.listFiles().findAll { it.name.endsWith('.tmp') })
    }

    void testInvalidStatePatchIsRejected() {
        Map state = new StateManager(
            repository,
            new FrameworkPaths(workspace)
        ).defaultState('TEST-1')
        shouldFail(IllegalArgumentException) {
            new StatePatch(repository).applyPath(
                state,
                'implementation.state',
                'invalid'
            )
        }
    }

    void testTicketKeyRejectsPathTraversal() {
        shouldFail(IllegalArgumentException) {
            new FrameworkPaths(workspace).ticketStateFile('../../outside')
        }
    }
}
