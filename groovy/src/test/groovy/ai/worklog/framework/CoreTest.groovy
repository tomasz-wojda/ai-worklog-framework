package ai.worklog.framework

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.StateManager
import groovy.test.GroovyTestCase

class CoreTest extends GroovyTestCase {
    private File repository
    private File workspace

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-test')
        new File(workspace, '.ai-worklog/catalog').mkdirs()
        new File(workspace, 'worklog/done').mkdirs()
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testJsonRoundTripAndDeepMerge() {
        File file = new File(workspace, 'value.json')
        JsonFiles.write(file, [outer: [left: 1]])
        assertEquals([outer: [left: 1]], JsonFiles.read(file))
        assertEquals(
            [outer: [left: 1, right: 2]],
            JsonFiles.deepMerge([outer: [left: 1]], [outer: [right: 2]])
        )
    }

    void testLayeredConfiguration() {
        JsonFiles.write(new File(workspace, '.ai-worklog/config.json'), [
            services: [jira: [enabled: true]],
            preflight: [required_binaries: ['git']]
        ])
        JsonFiles.write(new File(workspace, '.ai-worklog/local.json'), [
            services: [jira: [url: 'local']]
        ])
        Map config = ConfigLoader.load(workspace)
        assertEquals(true, config.services.jira.enabled)
        assertEquals('local', config.services.jira.url)
        assertEquals(['git'], config.preflight.required_binaries)
    }

    void testCatalogLoadingAndValidation() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        CatalogLoader loader = new CatalogLoader(repository, paths)
        Map catalog = loader.load()
        assertTrue(catalog.containsKey('example-eks-platform'))
        assertEquals([], loader.validate(catalog['example-eks-platform']))
        assertTrue(loader.findServices('PROJ').contains('example-eks-platform'))
    }

    void testStateDefaultsAndPersistence() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        StateManager manager = new StateManager(repository, paths)
        Map state = manager.load('TEST-1')
        assertEquals('not_started', state.investigation.state)
        state.next_action = 'Verify'
        manager.save(state)
        assertEquals('Verify', manager.load('TEST-1').next_action)
        assertEquals(['TEST-1'], manager.activeTickets())
    }

    void testRedaction() {
        Redaction redaction = new Redaction(repository)
        Map value = (Map) redaction.redact([
            token: 'abcdefgh',
            note: 'Bearer secret-value'
        ])
        assertEquals('ab...gh (8 chars)', value.token)
        assertEquals('***REDACTED***', value.note)
    }
}
