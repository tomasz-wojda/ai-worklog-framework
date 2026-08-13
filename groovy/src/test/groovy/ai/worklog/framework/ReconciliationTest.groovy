package ai.worklog.framework

import ai.worklog.framework.adapters.GitAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.commands.ReconciliationCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import ai.worklog.framework.reconciliation.ReconciliationComparators
import ai.worklog.framework.reconciliation.ReconciliationEngine
import ai.worklog.framework.reconciliation.ReconciliationReport
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

class ReconciliationTest extends GroovyTestCase {
    private File repository
    private File workspace

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-reconcile-', '-test')
        new File(workspace, '.ai-worklog/state').mkdirs()
        new File(workspace, '.ai-worklog/catalog').mkdirs()
        new File(workspace, 'integrations/jira').mkdirs()
        new File(workspace, 'integrations/jenkins').mkdirs()
        new File(workspace, 'repos/demo-repo/.git').mkdirs()
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testMissingStateReturnsUserError() {
        assertEquals(1, command(['status', 'TEST-9']))
    }

    void testInvalidSystemReturnsUserError() {
        writeState([ticket_key: 'TEST-1', summary: 'Fixture'])
        assertEquals(1, command(['status', 'TEST-1', '--system', 'aws']))
    }

    void testComparatorDetectsSummaryMismatch() {
        Map rules = ReconciliationEngine.defaultRules()
        List contradictions = ReconciliationComparators.compareState(
            [summary: 'Stored summary'],
            [new Observation(
                system: 'jira',
                source: 'jira',
                status: Status.READY,
                message: 'Jira issue fetched',
                details: [summary: 'Different summary', status_category: 'indeterminate']
            )],
            rules
        )
        assertEquals(1, contradictions.size())
        assertEquals('jira_summary_mismatch', contradictions[0].code)
    }

    void testComparatorDetectsUncommittedMismatch() {
        Map rules = ReconciliationEngine.defaultRules()
        List contradictions = ReconciliationComparators.compareState(
            [implementation: [uncommitted: false]],
            [new Observation(
                system: 'git',
                source: 'git:demo',
                status: Status.READY,
                message: 'Repository inspected',
                details: [present: true, dirty: true, ahead_of_upstream: 0]
            )],
            rules
        )
        assertEquals('uncommitted_mismatch', contradictions[0].code)
    }

    void testEngineWithMockedSystemsProducesJsonWithoutSecrets() {
        writeState([
            ticket_key: 'TEST-1',
            summary: 'Fixture summary',
            implementation: [state: 'in_progress', uncommitted: false],
            closeout: [tempo_logged: false, tempo_seconds: 0]
        ])
        new File(workspace, 'integrations/jira/jira.properties').setText(
            "jira.url=https://jira.example.test\njira.token=secret-token-value\n",
            'UTF-8'
        )
        ReconciliationReport report = engineWithMocks().run('TEST-1', ['jira', 'tempo'])
        Redaction redaction = new Redaction(repository)
        Map payload = (Map) new JsonSlurper().parseText(report.renderJson(redaction))
        assertEquals('TEST-1', payload.ticket_key)
        assertNotNull payload.timestamp
        assertTrue payload.observations.any { it.system == 'jira' }
        assertFalse report.renderJson(redaction).contains('secret-token-value')
    }

    void testBlockingContradictionReturnsBlockedExitCode() {
        writeState([
            ticket_key: 'TEST-1',
            summary: 'Fixture',
            services: ['example-eks-platform'],
            pull_requests: [[number: 42, state: 'open', url: 'https://github.com/example-org/example-jenkins/pull/42']],
            closeout: [tempo_logged: false, tempo_seconds: 0]
        ])
        ReconciliationEngine engine = engineWithMocks()
        engine.process.executeHandler = { command, timeout ->
            if (command.contains('which')) {
                return [code: 0, out: '/usr/bin/gh', err: '']
            }
            if (command.contains('gh')) {
                return [code: 0, out: '[]', err: '']
            }
            [code: 127, out: '', err: 'missing']
        }
        ReconciliationReport report = engine.run('TEST-1', ['github'])
        assertTrue report.hasBlockingContradiction()
        assertEquals('pr_missing_external', report.contradictions[0].code)
        assertEquals(3, exitCodeForReport(report))
    }

    void testMalformedJiraResponseReturnsSystemError() {
        writeState([ticket_key: 'TEST-1', summary: 'Fixture'])
        new File(workspace, 'integrations/jira/jira.properties').setText(
            "jira.url=https://jira.example.test\njira.token=token\n",
            'UTF-8'
        )
        ReconciliationEngine engine = engineWithMocks()
        engine.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: 'not-json', error: '']
        }
        ReconciliationReport report = engine.run('TEST-1', ['jira'])
        assertTrue report.hasErrorObservation()
        assertEquals(2, exitCodeForReport(report))
    }

    void testMalformedTempoNonListReturnsSystemError() {
        writeState([ticket_key: 'TEST-1', summary: 'Fixture', created_at: '2026-01-01T00:00:00Z'])
        new File(workspace, 'integrations/jira/jira.properties').setText(
            "jira.url=https://jira.example.test\njira.token=token\n",
            'UTF-8'
        )
        ReconciliationEngine engine = engineWithMocks()
        engine.http.requestHandler = { method, url, headers, timeout ->
            if (url.contains('/rest/api/2/myself')) {
                return [code: 200, body: '{"name":"operator","displayName":"Operator"}', error: '']
            }
            if (url.contains('/rest/tempo-timesheets/')) {
                return [code: 200, body: '{"unexpected":"object"}', error: '']
            }
            [code: 404, body: '', error: '']
        }
        ReconciliationReport report = engine.run('TEST-1', ['tempo'])
        assertTrue report.hasErrorObservation()
    }

    void testSystemFilterLimitsObservations() {
        writeState([ticket_key: 'TEST-1', summary: 'Fixture'])
        new File(workspace, 'integrations/jira/jira.properties').setText(
            "jira.url=https://jira.example.test\njira.token=token\n",
            'UTF-8'
        )
        ReconciliationReport report = engineWithMocks().run('TEST-1', ['jira'])
        assertTrue report.observations.every { it.system == 'jira' }
    }

    void testHumanOutputMatchesPythonFormat() {
        writeState([
            ticket_key: 'TEST-1',
            summary: 'Alpha',
            repositories: ['demo-repo'],
            implementation: [uncommitted: true]
        ])
        ReconciliationReport report = engineWithMocks().run('TEST-1', ['git'])
        String output = report.renderHuman()
        assertTrue output.contains('RECONCILIATION: TEST-1')
        assertTrue output.contains('OBSERVATIONS:')
        assertTrue output.contains('CONTRADICTIONS:')
        assertTrue output.contains('OVERALL:')
        assertFalse output.contains('RECONCILIATION STATUS:')
    }

    void testSharedRulesLoaded() {
        Map rules = ReconciliationEngine.mergeRules(
            ReconciliationEngine.loadRules(repository),
            ReconciliationEngine.defaultRules()
        )
        assertEquals(15, rules.timeouts.process_seconds)
        assertEquals('blocked', rules.contradiction_codes.repo_missing.severity)
    }

    void testReadOnlyHttpRejectsInvalidUrl() {
        ReadOnlyHttp http = new ReadOnlyHttp()
        Map response = http.get('ftp://example.test/resource')
        assertEquals(0, response.code)
        assertEquals('Invalid URL', response.error)
    }

    void testReadOnlyProcessRejectsControlCharacters() {
        shouldFail(IllegalArgumentException) {
            ReadOnlyProcess.validateArgv(['git', 'status', "bad\narg"])
        }
    }

    void testReadOnlyProcessTimeout() {
        Map result = new ReadOnlyProcess().execute(['sleep', '2'], 1)
        assertEquals(124, result.code)
        assertEquals('', result.out)
        assertEquals('Timed out', result.err)
    }

    void testGitAdapterRejectsRepositoryRootOutsideWorkspace() {
        List observations = new GitAdapter(
            new FrameworkPaths(workspace),
            new ReadOnlyProcess(),
            [repositories_root: '../outside']
        ).observe([:], ['fixture-repository'])
        assertEquals(Status.ERROR, observations[0].status)
        assertEquals('Repository root outside workspace', observations[0].message)
    }

    private ReconciliationEngine engineWithMocks() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        ReconciliationEngine engine = new ReconciliationEngine(
            repository,
            paths,
            ConfigLoader.load(workspace),
            new CatalogLoader(repository, paths).load(),
            new StateManager(repository, paths)
        )
        engine.http.requestHandler = { method, url, headers, timeout ->
            if (url.contains('/rest/api/2/myself')) {
                return [code: 200, body: '{"name":"operator","displayName":"Operator"}', error: '']
            }
            if (url.contains('/rest/api/2/issue/')) {
                return [code: 200, body: '''
                {
                  "fields": {
                    "summary": "Fixture summary",
                    "status": {"name": "In Progress", "statusCategory": {"key": "indeterminate"}},
                    "assignee": {"displayName": "Operator"},
                    "created": "2026-01-01T00:00:00.000+0000",
                    "updated": "2026-01-02T00:00:00.000+0000"
                  }
                }
                ''', error: '']
            }
            if (url.contains('/rest/tempo-timesheets/')) {
                return [code: 200, body: '[]', error: '']
            }
            [code: 404, body: '', error: '']
        }
        engine.process.executeHandler = { command, timeout ->
            if (command.contains('which')) {
                return [code: 0, out: '/usr/bin/git', err: '']
            }
            if (command.contains('git') && command.contains('status')) {
                return [code: 0, out: ' M file.txt', err: '']
            }
            if (command.contains('rev-parse') && command.contains('HEAD')) {
                return [code: 0, out: 'abc1234567890', err: '']
            }
            if (command.contains('rev-parse') && command.contains('--abbrev-ref')) {
                return [code: 0, out: 'main', err: '']
            }
            if (command.contains('rev-parse') && command.contains('@{upstream}')) {
                return [code: 0, out: 'deadbeef', err: '']
            }
            if (command.contains('rev-list')) {
                return [code: 0, out: '0', err: '']
            }
            [code: 127, out: '', err: 'missing']
        }
        engine
    }

    private int command(List<String> args) {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        CatalogLoader catalog = new CatalogLoader(repository, paths)
        StateManager states = new StateManager(repository, paths)
        ReconciliationCommands.run(
            'status',
            args,
            repository,
            paths,
            ConfigLoader.load(workspace),
            catalog,
            states
        )
    }

    private int exitCodeForReport(ReconciliationReport report) {
        if (report.hasErrorObservation()) {
            return 2
        }
        if (report.hasBlockingContradiction()) {
            return 3
        }
        0
    }

    private void writeState(Map state) {
        JsonFiles.write(new File(workspace, ".ai-worklog/state/${state.ticket_key}.json"), state)
    }
}
