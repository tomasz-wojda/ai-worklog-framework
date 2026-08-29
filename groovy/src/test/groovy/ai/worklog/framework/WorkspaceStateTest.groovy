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

    private List<String> services() {
        Map rules = (Map) new groovy.json.JsonSlurper().parse(
            new File(repository, 'shared/workspace-init.json')
        )
        ((List) rules.services)*.toString()
    }

    private void initWorkspace() {
        WorkspacePlanner planner = new WorkspacePlanner(repository)
        WorkspacePlanner.apply((List) planner.planInit(workspace).actions, workspace)
    }

    private void revertWorkspace() {
        WorkspacePlanner planner = new WorkspacePlanner(repository)
        WorkspacePlanner.apply((List) planner.planRevert(workspace).actions, workspace)
    }

    private void linkService(String service) {
        new File(workspace, 'integrations').mkdirs()
        new File(workspace, service).mkdirs()
        java.nio.file.Files.createSymbolicLink(
            new File(workspace, "integrations/${service}").toPath(),
            java.nio.file.Paths.get('..', service)
        )
    }

    void testWorkspaceInitAndRevert() {
        WorkspacePlanner planner = new WorkspacePlanner(repository)
        initWorkspace()
        assertTrue(new File(workspace, '.ai-worklog/state').isDirectory())
        assertTrue(new File(workspace, '.ai-worklog/config.json').isFile())
        assertTrue(new File(workspace, 'integrations').isDirectory())
        assertTrue(new File(workspace, 'repos').isDirectory())
        assertTrue(new File(workspace, 'tmp').isDirectory())
        assertEquals(
            "*${System.lineSeparator()}!.gitignore${System.lineSeparator()}",
            new File(workspace, '.ai-worklog/.gitignore').getText('UTF-8')
        )
        assertTrue(((List) planner.planInit(workspace).actions).every { it.skip })

        revertWorkspace()

        services().each { String service ->
            assertFalse(service, new File(workspace, "integrations/${service}").exists())
        }
    }

    void testWorkspaceRevertNeverRemovesHubsOrState() {
        initWorkspace()
        revertWorkspace()

        assertTrue(new File(workspace, 'integrations').isDirectory())
        assertTrue(new File(workspace, 'worklog').isDirectory())
        assertTrue(new File(workspace, 'repos').isDirectory())
        assertTrue(new File(workspace, 'tmp').isDirectory())
        assertTrue(new File(workspace, '.ai-worklog').isDirectory())
        assertTrue(new File(workspace, '.ai-worklog/config.json').isFile())
    }

    void testWorkspaceRevertKeepsADirectoryItDidNotCreate() {
        File preexisting = new File(workspace, 'integrations/jira')
        preexisting.mkdirs()

        initWorkspace()
        revertWorkspace()

        assertTrue(preexisting.isDirectory())
    }

    void testWorkspaceRevertKeepsAPreexistingSymlink() {
        linkService('jira')

        initWorkspace()
        revertWorkspace()

        File canonical = new File(workspace, 'integrations/jira')
        assertTrue(java.nio.file.Files.isSymbolicLink(canonical.toPath()))
        assertTrue(new File(workspace, 'jira').isDirectory())
    }

    void testApplyIsByteIdenticalForPreexistingContent() {
        Map<String, String> legacy = [
            'worklog/done/2026-01-01_TICKET.log': 'worklog entry\n',
            'integrations/jira/credentials': 'token\n',
            'integrations/confluence/notes.md': '# notes\n',
            'repos/checkout/README.md': 'readme\n'
        ]
        legacy.each { String relative, String content ->
            File target = new File(workspace, relative)
            target.parentFile.mkdirs()
            target.setText(content, 'UTF-8')
        }

        Map<String, String> before = contents(workspace)
        initWorkspace()
        Map<String, String> after = contents(workspace)

        before.each { String relative, String content ->
            assertEquals(relative, content, after[relative])
        }
    }

    private Map<String, String> contents(File root) {
        Map<String, String> found = [:]
        root.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            if (!java.nio.file.Files.isSymbolicLink(file.toPath())) {
                String relative = root.toPath().relativize(file.toPath()).toString()
                found[relative] = file.bytes.encodeHex().toString()
            }
        }
        found
    }

    void testWorkspaceInitCreatesADirectoryForEveryService() {
        initWorkspace()
        services().each { String service ->
            File target = new File(workspace, "integrations/${service}")
            assertTrue(service, target.isDirectory())
            assertFalse(service, java.nio.file.Files.isSymbolicLink(target.toPath()))
        }
    }

    void testWorkspaceInitLeavesAnExistingSymlinkAlone() {
        linkService('jira')
        initWorkspace()
        File canonical = new File(workspace, 'integrations/jira')
        assertTrue(java.nio.file.Files.isSymbolicLink(canonical.toPath()))
        assertEquals(
            java.nio.file.Paths.get('..', 'jira'),
            java.nio.file.Files.readSymbolicLink(canonical.toPath())
        )
    }

    void testWorkspaceInitLeavesAnUnknownIntegrationAlone() {
        File unknown = new File(workspace, 'integrations/confluence')
        unknown.mkdirs()
        File sentinel = new File(unknown, 'keep.txt')
        sentinel.setText('keep', 'UTF-8')

        initWorkspace()

        assertEquals('keep', sentinel.getText('UTF-8'))
    }

    void testWorkspaceRevertKeepsPopulatedServiceDirectories() {
        initWorkspace()
        File credentials = new File(workspace, 'integrations/jira/credentials')
        credentials.setText('secret', 'UTF-8')

        revertWorkspace()

        assertEquals('secret', credentials.getText('UTF-8'))
    }

    void testWorkspaceRevertKeepsUnknownIntegrations() {
        initWorkspace()
        File unmanaged = new File(workspace, 'integrations/custom')
        unmanaged.mkdirs()
        File sentinel = new File(unmanaged, 'keep.txt')
        sentinel.setText('keep', 'UTF-8')

        revertWorkspace()

        assertEquals('keep', sentinel.getText('UTF-8'))
    }

    void testServiceDirResolutionOrder() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        File rootJira = new File(workspace, 'jira')
        File canonicalJira = new File(workspace, 'integrations/jira')

        rootJira.mkdir()
        assertEquals(rootJira.canonicalFile, paths.serviceDir('jira').canonicalFile)

        canonicalJira.mkdirs()
        assertEquals(canonicalJira.canonicalFile, paths.serviceDir('jira').canonicalFile)

        assertEquals(
            new File(workspace, 'integrations/jenkins').canonicalFile,
            paths.serviceDir('jenkins').canonicalFile
        )
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
