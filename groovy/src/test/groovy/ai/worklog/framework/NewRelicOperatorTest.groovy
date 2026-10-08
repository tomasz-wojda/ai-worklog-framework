package ai.worklog.framework

import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.NewRelicAdapter
import ai.worklog.framework.adapters.NewRelicCredentials
import ai.worklog.framework.adapters.NewRelicGraphqlClient
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.commands.NewRelicCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.newrelic.NewRelicOperatorReport
import ai.worklog.framework.newrelic.NewRelicTerraformRenderer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

import java.nio.file.Files
import java.time.Instant

class NewRelicOperatorTest extends GroovyTestCase {
    private File repository
    private File workspace
    private FrameworkPaths paths
    private Map rules
    private Map schema

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-newrelic-test')
        new File(workspace, 'integrations/newrelic').mkdirs()
        paths = new FrameworkPaths(workspace)
        rules = NewRelicAdapter.loadOperatorRules(repository)
        schema = (Map) JsonFiles.read(new File(repository, 'schemas/newrelic-operator-report.schema.json'), [:])
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testProfilesBlockedWithoutCredentials() {
        Map report = adapter([:]).operatorProfiles()
        assertEquals(Status.BLOCKED, report.status)
        assertTrue(report.message.contains('No newrelic.properties found'))
        assertEquals([], validateReport(report))
    }

    void testPrimaryAndSecondaryProfileKeysExposeRedactedBooleansOnly() {
        String primaryKey = syntheticNrakKey('PRIMARY')
        String secondaryKey = syntheticNrakKey('SECONDARY')
        writeProperties("""primary.api_key=${primaryKey}
primary.account_id=123456
primary.rest_url=https://api.eu.newrelic.com/v2
secondary.newrelic.api_key=${secondaryKey}
secondary.newrelic.account_id=654321
""")
        Map report = adapter([:]).operatorProfiles()
        assertEquals(Status.READY, report.status)
        assertEquals(2, report.items.size())
        Map primaryProfile = report.items.find { it.id == 'primary' }
        Map secondaryProfile = report.items.find { it.id == 'secondary' }
        assertTrue(primaryProfile.has_api_key)
        assertTrue(secondaryProfile.has_api_key)
        assertEquals('123456', primaryProfile.account_id)
        assertEquals('654321', secondaryProfile.account_id)
        String json = JsonOutput.toJson(report)
        assertFalse(json.contains(primaryKey))
        assertFalse(json.contains(secondaryKey))
        assertEquals([], validateReport(report))
    }

    void testCanonicalAndLegacyKeysResolve() {
        String defaultKey = syntheticNrakKey('DEFAULT')
        writeProperties("""default.api_key=${defaultKey}
default.account_id=111111
default.rest_url=https://api.eu.newrelic.com/v2
""")
        NewRelicCredentials.Resolved canonical = NewRelicCredentials.resolve(paths, [:], rules, 'default')
        assertEquals(defaultKey, canonical.apiKey)
        assertEquals('111111', canonical.accountId)
        String legacyKey = syntheticNrakKey('LEGACY')
        writeProperties("""newrelic.api_key=${legacyKey}
newrelic.account_id=222222
newrelic.url=https://api.eu.newrelic.com/v2
""")
        NewRelicCredentials.Resolved legacy = NewRelicCredentials.resolve(paths, [:], rules, 'default')
        assertEquals(legacyKey, legacy.apiKey)
        assertEquals('222222', legacy.accountId)
        List<Map> publicProfiles = NewRelicCredentials.publicProfiles(paths, [:], rules)
        assertEquals(['default'], publicProfiles*.id)
        assertTrue(publicProfiles[0].has_api_key)
    }

    void testWorkspaceConfigDefaultsAndProfileSelection() {
        writeProperties(defaultProperties())
        Map config = ConfigLoader.load(workspace)
        if (!config.adapters) {
            config.adapters = [:]
        }
        if (!config.adapters.newrelic) {
            config.adapters.newrelic = [:]
        }
        config.adapters.newrelic.default_profile = 'secondary'
        NewRelicCredentials.Resolved resolved = NewRelicCredentials.resolve(paths, config, rules, null)
        assertEquals('secondary', resolved.id)
        Map primaryProps = NewRelicCredentials.publicProfiles(paths, config, rules).find { it.id == 'primary' }
        assertEquals('123456', primaryProps.account_id)
    }

    void testAuthTestAndWhoamiThroughInjectedGraphql() {
        writeProperties(defaultProperties())
        Map auth = adapter([:]).operatorAuthTest('primary', 5)
        Map who = adapter([:]).operatorWhoami('primary', 5)
        assertEquals(Status.READY, auth.status)
        assertEquals(Status.READY, who.status)
        assertEquals('user@example.com', who.items[0].email)
        assertEquals([], validateReport(auth))
        assertEquals([], validateReport(who))
    }

    void testRestApplicationsPaginationDedupeAndEscaping() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.applications = (1..120).collect { int id ->
            [id: id, name: "App-${id}", language: 'java', health_status: 'green']
        }
        state.applications << [id: 50, name: 'App-50-dup', language: 'java', health_status: 'green']
        NewRelicAdapter client = adapter([:], state)
        Map report = client.operatorApplications('primary', "O'Reilly", 100, 5)
        assertEquals(0, report.items.size())
        Map all = client.operatorApplications('primary', 'App-1', 100, 5)
        assertTrue(all.items.size() <= 100)
        assertTrue(all.totals.matched >= 11)
        assertEquals([], validateReport(all))
    }

    void testApplicationHostsAndDeploymentsWithUtcDates() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.hosts = [[id: 1, host: 'host-a', health_status: 'green']]
        state.deployments = [[id: 1, revision: 'abc', changelog: 'deploy', timestamp: '2026-09-01T12:00:00Z']]
        NewRelicAdapter client = adapter([:], state)
        assertEquals('host-a', client.operatorHosts('primary', '100', null, 50, 5).items[0].host)
        Map deployments = client.operatorDeployments('primary', '100', '2026-09-01', '2026-09-01', 50, 5)
        assertEquals('2026-09-01', deployments.filters.since)
        assertEquals('2026-09-01', deployments.filters.until)
        Instant start = client.parseDateBoundary('2026-09-01', true)
        assertEquals('2026-09-01T00:00:00Z', start.toString())
    }

    void testViolationsIssuesIncidentsAndErrorsNormalization() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.violations = [[
            id: 1, priority: 'critical', opened_at: '2026-09-01T10:00:00Z',
            entity_name: 'worker', policy_name: 'CPU', policy_id: 900
        ]]
        state.issues = [[
            issueId: 'issue-1', title: 'Issue', priority: 'CRITICAL', state: 'ACTIVATED',
            createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-01T11:00:00Z',
            entities: [[name: 'worker', guid: entityGuid('1')]]
        ]]
        state.incidents = [[
            incidentId: 'inc-1', title: 'Incident', priority: 'CRITICAL', state: 'OPEN',
            createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-01T11:00:00Z',
            entity: [name: 'worker', guid: entityGuid('1')], conditionId: '7001', issueId: 'issue-1'
        ]]
        NewRelicAdapter client = adapter([:], state)
        assertEquals('critical', client.operatorViolations('primary', '900', 'worker', 'critical', 50, 5).items[0].priority)
        assertEquals('issue-1', client.operatorIssues('primary', 'activated', 'critical', 'worker', '2026-09-01', '2026-09-01', 50, 5).items[0].issue_id)
        assertEquals('inc-1', client.operatorIncidents('primary', 'issue-1', '7001', 'worker', '2026-09-01', '2026-09-01', 50, 5).items[0].incident_id)
        Map errors = client.operatorErrors('primary', '100', 1, 50, 5)
        assertEquals(3, errors.items[0].total_errors)
        assertTrue(errors.items[0].by_class instanceof List)
        assertEquals([], validateReport(errors))
    }

    void testNrqlUsesGraphqlVariableWithoutMutation() {
        writeProperties(defaultProperties())
        Map state = baseState()
        NewRelicAdapter client = adapter([:], state)
        Map report = client.operatorNrql('primary', "SELECT * FROM Transaction WHERE name = 'foo\\'bar'", null, 10, 5)
        assertEquals(Status.READY, report.status)
        assertEquals("SELECT * FROM Transaction WHERE name = 'foo\\'bar'", state.lastNrql)
        assertFalse((state.lastGraphqlQuery ?: '').contains("SELECT * FROM Transaction"))
        assertEquals([], validateReport(report))
    }

    void testNrqlFileSafetyAndExactOneSourceValidation() {
        writeProperties(defaultProperties())
        File nrqlFile = workspaceFile('queries/test.nrql', "SELECT count(*) FROM Transaction\n")
        Map fromFile = adapter([:]).operatorNrql('primary', null, nrqlFile, 10, 5)
        assertEquals('queries/test.nrql', fromFile.definition.path)
        shouldFail(IllegalArgumentException) {
            adapter([:]).operatorNrql('primary', 'SELECT 1', nrqlFile, 10, 5)
        }
        File outside = File.createTempFile('outside', '.nrql')
        outside.setText('SELECT 1', 'UTF-8')
        shouldFail(IllegalArgumentException) {
            adapter([:]).operatorNrql('primary', null, outside, 10, 5)
        }
        outside.delete()
        Map captured = captureStreams {
            NewRelicCommands.run('nrql', [], repository, paths, ConfigLoader.load(workspace))
        }
        assertEquals(new ExitCodes(repository).userError, captured.code)
        assertTrue(captured.err.contains('exactly one inline query or --file source'))
    }

    void testEntitiesDashboardsPoliciesAndConditionsReads() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.entities = [[guid: 'DASH-GUID', name: 'Ops', type: 'DASHBOARD', domain: 'VIZ', accountId: 123456]]
        state.dashboardDetail = dashboardFixture()
        state.policies = [[id: 900, name: 'CPU Policy', incident_preference: 'PER_POLICY']]
        state.nrqlConditions = [[
            id: 7001, name: 'CPU High', policy_id: 900, enabled: true,
            nrql: [query: 'SELECT average(cpuPercent) FROM SystemSample'],
            terms: [[priority: 'CRITICAL']], signal: [aggregationWindow: 60],
            violation_time_limit_seconds: 3600
        ]]
        NewRelicAdapter client = adapter([:], state)
        assertEquals('Ops', client.operatorEntities('primary', 'Ops', 'VIZ', 'DASHBOARD', 50, 5).items[0].name)
        assertEquals('Ops', client.operatorDashboards('primary', 'Ops', null, 50, 5).items[0].name)
        assertEquals('Ops Dashboard', client.operatorDashboard('primary', 'DASH-GUID', 5).items[0].name)
        assertEquals('CPU Policy', client.operatorAlertPolicies('primary', 'CPU', 50, 5).items[0].name)
        assertEquals('CPU High', client.operatorAlertPolicy('primary', '900', 5).items[0].conditions[0].name)
        assertEquals('7001', client.operatorAlertCondition('primary', '7001', 5).items[0].id.toString())
        assertEquals([], validateReport(client.operatorAlertConditions('primary', '900', null, 'NRQL', null, 50, 5)))
    }

    void testReportSchemaEnumForAllOperations() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.dashboardDetail = dashboardFixture()
        NewRelicAdapter client = adapter([:], state)
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson())
        File dashboardFile = workspaceFile('dashboards/new.json', dashboardDefinitionJson())
        File pageFile = workspaceFile('dashboards/page.json', pageDefinitionJson())
        File widgetFile = workspaceFile('dashboards/widget.json', widgetDefinitionJson())
        List<Map> reports = [
            client.operatorProfiles(),
            client.operatorAuthTest('primary', 5),
            client.operatorWhoami('primary', 5),
            client.operatorApplications('primary', null, 50, 5),
            client.operatorApplication('primary', '100', 5),
            client.operatorHosts('primary', '100', null, 50, 5),
            client.operatorDeployments('primary', '100', null, null, 50, 5),
            client.operatorViolations('primary', null, null, 'all', 50, 5),
            client.operatorIssues('primary', 'all', 'all', null, null, null, 50, 5),
            client.operatorIncidents('primary', null, null, null, null, null, 50, 5),
            client.operatorErrors('primary', '100', 1, 50, 5),
            client.operatorNrql('primary', 'SELECT 1', null, 10, 5),
            client.operatorEntities('primary', null, null, null, 50, 5),
            client.operatorDashboards('primary', null, null, 50, 5),
            client.operatorDashboard('primary', 'DASH-GUID', 5),
            client.operatorDashboardExport('primary', 'DASH-GUID', 'json', null, false, 5),
            client.operatorAlertPolicies('primary', null, 50, 5),
            client.operatorAlertPolicy('primary', '900', 5),
            client.operatorAlertConditions('primary', '900', null, null, null, 50, 5),
            client.operatorAlertCondition('primary', '7001', 5),
            client.operatorAlertConditionCreate('primary', '900', conditionFile, '900', 5),
            client.operatorAlertConditionUpdate('primary', '7001', conditionFile, 'CPU High', 5),
            client.operatorDashboardCreate('primary', dashboardFile, '123456', 5),
            client.operatorDashboardPageCreate('primary', 'DASH-GUID', pageFile, 'DASH-GUID', 5),
            client.operatorDashboardPageUpdate('primary', 'PAGE-GUID', pageFile, 'Overview', 5),
            client.operatorDashboardWidgetCreate('primary', 'DASH-GUID', 'PAGE-GUID', widgetFile, 'DASH-GUID', 5),
            client.operatorDashboardWidgetUpdate('primary', 'WIDGET-GUID', widgetFile, 'Errors', 5)
        ]
        reports.each { Map report ->
            assertEquals([], validateReport(report))
        }
    }

    void testDryRunMutationsMakeZeroGraphqlMutationCalls() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.nrqlConditions = [[
            id: 7001, name: 'CPU High', policy_id: 900, enabled: true,
            nrql: [query: 'SELECT average(cpuPercent) FROM SystemSample'],
            terms: [[priority: 'CRITICAL']], signal: [aggregationWindow: 60],
            violation_time_limit_seconds: 3600
        ]]
        state.dashboardDetail = dashboardFixture()
        NewRelicAdapter client = adapter([:], state, false)
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson())
        File dashboardFile = workspaceFile('dashboards/new.json', dashboardDefinitionJson())
        File pageFile = workspaceFile('dashboards/page.json', pageDefinitionJson())
        File widgetFile = workspaceFile('dashboards/widget.json', widgetDefinitionJson())
        client.operatorAlertConditionCreate('primary', '900', conditionFile, '900', 5)
        client.operatorAlertConditionUpdate('primary', '7001', conditionFile, 'CPU High', 5)
        client.operatorDashboardCreate('primary', dashboardFile, '123456', 5)
        client.operatorDashboardPageCreate('primary', 'DASH-GUID', pageFile, 'DASH-GUID', 5)
        client.operatorDashboardPageUpdate('primary', 'PAGE-GUID', pageFile, 'Overview', 5)
        client.operatorDashboardWidgetCreate('primary', 'DASH-GUID', 'PAGE-GUID', widgetFile, 'DASH-GUID', 5)
        client.operatorDashboardWidgetUpdate('primary', 'WIDGET-GUID', widgetFile, 'Errors', 5)
        assertEquals(0, state.mutationCalls)
    }

    void testAppliedMutationsVerifyPostApplyStateForAllSevenWrites() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.nrqlConditions = [[
            id: 7001, name: 'CPU High', policy_id: 900, enabled: true,
            nrql: [query: 'SELECT average(cpuPercent) FROM SystemSample'],
            terms: [[
                priority: 'CRITICAL', operator: 'ABOVE', threshold: 5,
                thresholdDuration: 300, thresholdOccurrences: 'ALL'
            ]],
            signal: [aggregationWindow: 60], violation_time_limit_seconds: 3600
        ]]
        state.dashboardDetail = dashboardFixture()
        seedDashboardState(state)
        NewRelicAdapter client = adapter([:], state, true)
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson('Updated CPU'))
        Map created = client.operatorAlertConditionCreate('primary', '900', conditionFile, '900', 5)
        assertTrue(created.applied)
        assertEquals('8001', created.items[0].id)
        assertEquals(123456, state.lastGraphqlVariables.accountId)
        Map updated = client.operatorAlertConditionUpdate('primary', '7001', conditionFile, 'CPU High', 5)
        assertTrue(updated.applied)
        assertEquals(123456, state.lastGraphqlVariables.accountId)
        Map dashboard = client.operatorDashboardCreate(
            'primary', workspaceFile('dashboards/new.json', dashboardDefinitionJson('Created Dashboard')), '123456', 5
        )
        assertTrue(dashboard.applied)
        assertEquals('NEW-DASH', dashboard.items[0].guid)
        File pageFile = workspaceFile('dashboards/page.json', pageDefinitionJson('Added Page'))
        Map pageCreated = client.operatorDashboardPageCreate('primary', 'NEW-DASH', pageFile, 'NEW-DASH', 5)
        assertTrue(pageCreated.applied)
        assertEquals('NEW-PAGE', pageCreated.items[0].guid)
        assertEquals('Added Page', state.pageDetails['NEW-PAGE'].name)
        File pageUpdateFile = workspaceFile('dashboards/page-update.json', pageDefinitionJson('Renamed Page'))
        Map pageUpdated = client.operatorDashboardPageUpdate('primary', 'NEW-PAGE', pageUpdateFile, 'Added Page', 5)
        assertTrue(pageUpdated.applied)
        assertEquals('Renamed Page', state.pageDetails['NEW-PAGE']?.name)
        File widgetFile = workspaceFile('dashboards/widget.json', widgetDefinitionJson('Added Widget'))
        Map widgetCreated = client.operatorDashboardWidgetCreate('primary', 'NEW-DASH', 'NEW-PAGE', widgetFile, 'NEW-DASH', 5)
        assertTrue(widgetCreated.applied)
        assertEquals('NEW-WIDGET', widgetCreated.items[0].guid)
        assertEquals('Added Widget', state.widgetDetails['NEW-WIDGET'].title)
        File widgetUpdateFile = workspaceFile('dashboards/widget-update.json', widgetDefinitionJson('Renamed Widget'))
        Map widgetUpdated = client.operatorDashboardWidgetUpdate('primary', 'NEW-WIDGET', widgetUpdateFile, 'Added Widget', 5)
        assertTrue(widgetUpdated.applied)
        assertEquals('Renamed Widget', state.widgetDetails['NEW-WIDGET'].title)
    }

    void testPostApplyMismatchLeavesAppliedFalse() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.nrqlConditions = [[
            id: 7001, name: 'CPU High', policy_id: 900, enabled: true,
            nrql: [query: 'SELECT average(cpuPercent) FROM SystemSample'],
            terms: [[
                priority: 'CRITICAL', operator: 'ABOVE', threshold: 5,
                thresholdDuration: 300, thresholdOccurrences: 'ALL'
            ]],
            signal: [aggregationWindow: 60], violation_time_limit_seconds: 3600
        ]]
        state.forceVerificationMismatch = true
        NewRelicAdapter client = adapter([:], state, true)
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson('Updated CPU'))
        Map updated = client.operatorAlertConditionUpdate('primary', '7001', conditionFile, 'CPU High', 5)
        assertEquals(Status.ERROR, updated.status)
        assertFalse(updated.applied)
        assertTrue(updated.message.contains('Post-apply verification failed'))
    }

    void testConfirmationFailuresBeforeTransport() {
        writeProperties(defaultProperties())
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson())
        Map captured = captureStreams {
            NewRelicCommands.run(
                'alert-condition-create',
                ['900', conditionFile.absolutePath, '--confirm-policy', '901', '--json'],
                repository, paths, ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).userError, captured.code)
        assertTrue(captured.err.contains('matching --confirm-policy'))
    }

    void testInvalidDefinitionSchemaRejectedBeforeTransport() {
        writeProperties(defaultProperties())
        Map state = baseState()
        NewRelicAdapter client = adapter([:], state, true)
        File bad = workspaceFile('conditions/bad.json', '{"name":"x"}')
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', bad, '900', 5)
        }
        assertEquals(0, state.mutationCalls)
    }

    void testDashboardExportTerraformDeterministicWithoutCredentials() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.dashboardDetail = dashboardFixture()
        NewRelicAdapter client = adapter([:], state, false)
        Map dry = client.operatorDashboardExport('primary', 'DASH-GUID', 'terraform', 'exports/tf', false, 5)
        assertTrue(dry.dry_run)
        assertFalse(dry.applied)
        assertEquals(0, state.mutationCalls)
        NewRelicAdapter applyClient = adapter([:], state, true)
        Map applied = applyClient.operatorDashboardExport('primary', 'DASH-GUID', 'terraform', 'exports/tf', true, 5)
        assertTrue(applied.applied)
        File provider = new File(workspace, 'exports/tf/provider.tf')
        File dashboardTf = new File(workspace, 'exports/tf/dashboard.tf')
        assertTrue(provider.isFile())
        assertTrue(dashboardTf.isFile())
        String providerText = provider.getText('UTF-8')
        assertFalse(providerText.contains("${nrakPrefix()}-"))
        assertTrue(providerText.contains('var.newrelic_api_key'))
        Map rerender = NewRelicTerraformRenderer.render(state.dashboardDetail, '123456', 'ops_dashboard')
        assertEquals(
            ['dashboard.tf', 'provider.tf', 'terraform.tfvars.example', 'variables.tf'],
            rerender.keySet().sort()
        )
        assertEquals(dashboardTf.getText('UTF-8'), rerender['dashboard.tf'])
    }

    void testDashboardExportJsonApplyForceAndAtomicWrite() {
        writeProperties(defaultProperties())
        Map state = baseState()
        state.dashboardDetail = dashboardFixture()
        File target = new File(workspace, 'exports/dashboard.json')
        target.parentFile.mkdirs()
        target.setText('old', 'UTF-8')
        NewRelicAdapter client = adapter([:], state, true)
        shouldFail(IllegalArgumentException) {
            client.operatorDashboardExport('primary', 'DASH-GUID', 'json', target.path, false, 5)
        }
        Map applied = client.operatorDashboardExport('primary', 'DASH-GUID', 'json', 'exports/dashboard.json', true, 5)
        assertTrue(applied.applied)
        assertTrue(applied.applied)
        assertTrue(target.getText('UTF-8').contains('Ops Dashboard'))
    }

    void testSensitiveOutputRedaction() {
        String leakedKey = syntheticNrakKey('PRIMARY')
        writeProperties(defaultProperties(leakedKey))
        Redaction redaction = new Redaction(repository)
        Map payload = [
            operation: 'profiles',
            fetched_at: NewRelicAdapter.utcNow(),
            status: Status.READY,
            items: [[
                newrelic_api_key: leakedKey,
                license_key: 'license-secret-value',
                note: 'Bearer ' + leakedKey,
                has_api_key: true
            ]]
        ]
        NewRelicOperatorReport report = NewRelicOperatorReport.fromPayload(payload)
        String json = report.renderJson(redaction)
        String human = report.renderHuman(redaction)
        assertFalse(json.contains(leakedKey))
        assertFalse(json.contains('license-secret-value'))
        assertFalse(human.contains(leakedKey))
        assertTrue(json.contains('"has_api_key": true'))
    }

    void testCliProfilesJsonRedactsSecrets() {
        String primaryKey = syntheticNrakKey('PRIMARY')
        writeProperties(defaultProperties(primaryKey))
        Map captured = captureStreams {
            NewRelicCommands.run('profiles', ['--json'], repository, paths, ConfigLoader.load(workspace))
        }
        assertEquals(0, captured.code)
        assertFalse(captured.out.contains(primaryKey))
        assertTrue(captured.out.contains('"has_api_key": true'))
        assertEquals('', captured.err)
    }

    void testCliJsonReportsMissingCredentialsAsBlocked() {
        writeProperties('staging.account_id=1234567\n')
        Map captured = captureStreams {
            NewRelicCommands.run(
                'auth-test',
                ['--profile', 'staging', '--json'],
                repository,
                paths,
                ConfigLoader.load(workspace)
            )
        }
        Map report = (Map) new JsonSlurper().parseText(captured.out)
        assertEquals(new ExitCodes(repository).blocked, captured.code)
        assertEquals('blocked', report.status)
        assertEquals('staging', report.profile)
        assertEquals('1234567', report.account_id)
        assertEquals('New Relic API key unavailable', report.message)
        assertEquals('', captured.err)
    }

    void testCliHumanReportsCredentialContextAsBlocked() {
        writeProperties('staging.account_id=1234567\n')
        Map missingKey = captureStreams {
            NewRelicCommands.run(
                'whoami',
                ['--profile', 'staging'],
                repository,
                paths,
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).blocked, missingKey.code)
        assertEquals('', missingKey.out)
        assertTrue(missingKey.err.contains('New Relic whoami'))
        assertTrue(missingKey.err.contains('Profile: staging'))
        assertTrue(missingKey.err.contains('Account: 1234567'))
        assertTrue(missingKey.err.contains('Status: blocked'))
        assertTrue(missingKey.err.contains('Message: New Relic API key unavailable'))

        String apiKey = syntheticNrakKey('MISSINGACCOUNT')
        writeProperties("staging.api_key=${apiKey}\n")
        Map missingAccount = captureStreams {
            NewRelicCommands.run(
                'whoami',
                ['--profile', 'staging'],
                repository,
                paths,
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).blocked, missingAccount.code)
        assertEquals('', missingAccount.out)
        assertTrue(missingAccount.err.contains('Profile: staging'))
        assertTrue(missingAccount.err.contains('Message: New Relic account id unavailable'))
        assertFalse(missingAccount.err.contains(apiKey))
    }

    void testGraphqlMutationAllowlistRejectsUnknownMutation() {
        writeProperties(defaultProperties())
        Map state = baseState()
        Map emptyAllowlist = JsonFiles.deepMerge(rules, [graphql_mutations: []])
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: restHandler(state))
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: graphqlHandler(state))
        NewRelicAdapter client = new NewRelicAdapter(paths, http, writeHttp, emptyAllowlist, null, [:], true)
        File conditionFile = workspaceFile('conditions/cpu.json', conditionDefinitionJson())
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', conditionFile, '900', 5)
        }
    }

    void testDefinitionFileRejectsDirectSymlinkInvalidUtf8NulAndOversized() {
        writeProperties(defaultProperties())
        Map smallRules = JsonFiles.deepMerge(rules, [limits: [definition_file_max_bytes: 32]])
        NewRelicAdapter client = adapter([:], baseState(), false, smallRules)
        File outside = File.createTempFile('outside', '.json')
        outside.setText(conditionDefinitionJson(), 'UTF-8')
        File symlink = new File(workspace, 'conditions/link.json')
        symlink.parentFile.mkdirs()
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', symlink, '900', 5)
        }
        outside.delete()
        File nulFile = workspaceFile('conditions/nul.json', "{\u0000}")
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', nulFile, '900', 5)
        }
        File invalidUtf8 = new File(workspace, 'conditions/invalid-utf8.json')
        invalidUtf8.parentFile.mkdirs()
        invalidUtf8.bytes = [0xFF, 0xFE, 0x00] as byte[]
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', invalidUtf8, '900', 5)
        }
        File oversized = workspaceFile('conditions/large.json', conditionDefinitionJson('x' * 100))
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', oversized, '900', 5)
        }
        File nrqlSymlink = new File(workspace, 'queries/link.nrql')
        nrqlSymlink.parentFile.mkdirs()
        File outsideNrql = File.createTempFile('outside', '.nrql')
        outsideNrql.setText('SELECT 1', 'UTF-8')
        Files.createSymbolicLink(nrqlSymlink.toPath(), outsideNrql.toPath())
        shouldFail(IllegalArgumentException) {
            client.operatorNrql('primary', null, nrqlSymlink, 10, 5)
        }
        outsideNrql.delete()
    }

    void testJsonWriteHttpPassesDistinctSuccessAndErrorBodyBounds() {
        writeProperties(defaultProperties())
        Map capturedLimits = [errorMax: 0, successMax: 0]
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: { method, url, headers, payload, timeout, errorMax, successMax ->
            capturedLimits.errorMax = errorMax
            capturedLimits.successMax = successMax
            if (payload?.query?.toString()?.contains('ErrorPath')) {
                return [code: 401, body: 'x' * (errorMax + 100), error: 'HTTP 401']
            }
            graphqlResponse([actor: [user: [email: 'user@example.com']]])
        })
        NewRelicGraphqlClient client = new NewRelicGraphqlClient(
            writeHttp, true, rules.graphql_mutations, [200], [200]
        )
        assertEquals(8192, rules.limits.error_body_max_characters)
        assertEquals(4194304, rules.limits.response_body_max_characters)
        client.query('https://example.test/graphql', [:], '{ actor { user { email } } }', [:], 5, 4194304, 8192)
        assertEquals(8192, capturedLimits.errorMax as int)
        assertEquals(4194304, capturedLimits.successMax as int)
        shouldFail(IllegalStateException) {
            client.query(
                'https://example.test/graphql', [:],
                'query ErrorPath { actor { user { email } } }', [:], 5, 4194304, 8192
            )
        }
    }

    void testGraphqlHttpAndErrorShapeHandlingRedactsSecrets() {
        String leakedKey = syntheticNrakKey('PRIMARY')
        writeProperties(defaultProperties(leakedKey))
        Map state = baseState()
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: { method, url, headers, payload, timeout, errorMax, successMax ->
            String query = payload?.query?.toString() ?: ''
            if (query.contains('HttpError')) {
                return [
                    code: 401,
                    body: "Authorization failed for ${leakedKey}",
                    error: "HTTP 401 Authorization Bearer ${leakedKey}"
                ]
            }
            if (query.contains('GraphqlError')) {
                return graphqlResponse([:], [[message: "Invalid key ${leakedKey}"]])
            }
            graphqlResponse([actor: [user: [email: 'user@example.com']]])
        })
        NewRelicGraphqlClient client = new NewRelicGraphqlClient(
            writeHttp, true, rules.graphql_mutations, [200], [200]
        )
        String httpMessage = shouldFail(IllegalStateException) {
            client.query(
                'https://example.test/graphql', [:],
                'query HttpError { actor { user { email } } }', [:], 5, 4194304, 8192
            )
        }
        assertFalse(httpMessage.contains(leakedKey))
        String graphqlMessage = shouldFail(IllegalStateException) {
            client.query(
                'https://example.test/graphql', [:],
                'query GraphqlError { actor { user { email } } }', [:], 5, 4194304, 8192
            )
        }
        assertFalse(graphqlMessage.contains(leakedKey))
    }

    void testGraphqlQueryRejectsMutationDocumentsAndUnknownAllowlistNames() {
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: { a, b, c, d, e, f, g ->
            graphqlResponse([dashboardCreate: [entityResult: [guid: 'X']]])
        })
        NewRelicGraphqlClient client = new NewRelicGraphqlClient(
            writeHttp, true, ['dashboardCreate'], [200], [200]
        )
        shouldFail(IllegalStateException) {
            client.query(
                'https://example.test/graphql', [:],
                'mutation Create { dashboardCreate(accountId: 1, dashboard: {name: "x"}) { entityResult { guid } } }',
                [:], 5, 1000, 1000
            )
        }
        shouldFail(IllegalArgumentException) {
            client.mutate(
                'dashboardDelete',
                'https://example.test/graphql',
                [:],
                'mutation { dashboardDelete(guid: "x") { success } }',
                [:], 5, 1000, 1000
            )
        }
    }

    void testDefinitionSchemaRejectsEnumRangeAndAdditionalPropertiesBeforeTransport() {
        writeProperties(defaultProperties())
        Map state = baseState()
        NewRelicAdapter client = adapter([:], state, true)
        File badPriority = workspaceFile('conditions/bad-priority.json', JsonOutput.toJson([
            name: 'CPU', enabled: true,
            nrql: [query: 'SELECT 1'],
            signal: [aggregationWindow: 60],
            terms: [[
                priority: 'HIGH', operator: 'ABOVE', threshold: 1,
                thresholdDuration: 300, thresholdOccurrences: 'ALL'
            ]],
            violationTimeLimitSeconds: 3600
        ]))
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', badPriority, '900', 5)
        }
        File badRange = workspaceFile('conditions/bad-range.json', JsonOutput.toJson([
            name: 'CPU', enabled: true,
            nrql: [query: 'SELECT 1', extra: true],
            signal: [aggregationWindow: 60],
            terms: [[
                priority: 'CRITICAL', operator: 'ABOVE', threshold: 1,
                thresholdDuration: 300, thresholdOccurrences: 'ALL'
            ]],
            violationTimeLimitSeconds: 100
        ]))
        shouldFail(IllegalArgumentException) {
            client.operatorAlertConditionCreate('primary', '900', badRange, '900', 5)
        }
        File badDashboard = workspaceFile('dashboards/bad-perms.json', JsonOutput.toJson([
            kind: 'dashboard', name: 'Ops', permissions: 'SECRET', surprise: true
        ]))
        shouldFail(IllegalArgumentException) {
            client.operatorDashboardCreate('primary', badDashboard, '123456', 5)
        }
        assertEquals(0, state.mutationCalls)
    }

    void testTerraformExportPathsSymlinkContainmentForceAndPlaceholderOnlyTfvars() {
        String primaryKey = syntheticNrakKey('PRIMARY')
        writeProperties(defaultProperties(primaryKey))
        Map state = baseState()
        state.dashboardDetail = dashboardFixture()
        NewRelicAdapter client = adapter([:], state, true)
        File outside = File.createTempDir('outside-', '-tf')
        File symlinkParent = new File(workspace, 'exports/link-parent')
        symlinkParent.parentFile.mkdirs()
        Files.createSymbolicLink(symlinkParent.toPath(), outside.toPath())
        shouldFail(IllegalArgumentException) {
            client.operatorDashboardExport('primary', 'DASH-GUID', 'terraform', 'exports/link-parent/tf', true, 5)
        }
        Map applied = client.operatorDashboardExport('primary', 'DASH-GUID', 'terraform', 'exports/safe/tf', true, 5)
        assertTrue(applied.applied)
        File exportDir = new File(workspace, 'exports/safe/tf')
        String nrak = nrakPrefix()
        ['provider.tf', 'variables.tf', 'dashboard.tf', 'terraform.tfvars.example'].each { name ->
            File generated = new File(exportDir, name)
            assertTrue("${name} missing", generated.isFile())
            String text = generated.getText('UTF-8')
            assertFalse("${name} leaked key", text.contains(primaryKey))
            if (name != 'terraform.tfvars.example') {
                assertFalse("${name} leaked live tfvars", text ==~ /(?s).*newrelic_api_key\s*=\s*"${nrak}-.*/)
            }
        }
        File tfvars = new File(exportDir, 'terraform.tfvars.example')
        assertTrue(tfvars.text.contains('REPLACE_WITH_API_KEY'))
        assertFalse(tfvars.text.contains(primaryKey))
        assertFalse(new File(exportDir, 'terraform.tfvars').exists())
        File existing = new File(exportDir, 'dashboard.tf')
        existing.setText('stale', 'UTF-8')
        shouldFail(IllegalArgumentException) {
            client.operatorDashboardExport('primary', 'DASH-GUID', 'terraform', 'exports/safe/tf', false, 5)
        }
        outside.deleteDir()
    }

    void testCliRepeatedAndUnknownOptionsEmitStructuredJsonErrors() {
        writeProperties(defaultProperties())
        Map unknown = captureStreams {
            NewRelicCommands.run('applications', ['--bad-option', '--json'], repository, paths, ConfigLoader.load(workspace))
        }
        assertEquals(new ExitCodes(repository).userError, unknown.code)
        Map unknownReport = (Map) new JsonSlurper().parseText(unknown.out)
        assertEquals('error', unknownReport.status)
        assertTrue(unknownReport.message.contains('--bad-option'))
        Map repeated = captureStreams {
            NewRelicCommands.run(
                'applications', ['--limit', '1', '--limit', '2', '--json'],
                repository, paths, ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).userError, repeated.code)
        Map repeatedReport = (Map) new JsonSlurper().parseText(repeated.out)
        assertEquals('error', repeatedReport.status)
        assertTrue(repeatedReport.message.contains('Repeated option'))
    }

    private NewRelicAdapter adapter(Map config, Map state = null, boolean apply = false, Map customRules = null) {
        Map effectiveState = state ?: baseState()
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: restHandler(effectiveState))
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: graphqlHandler(effectiveState))
        new NewRelicAdapter(
            paths, http, writeHttp, customRules ?: rules, customRules ? null : repository, config, apply
        )
    }

    private static Map baseState() {
        [
            mutationCalls: 0,
            applications: [[id: 100, name: 'App-100', language: 'java', health_status: 'green']],
            hosts: [],
            deployments: [],
            violations: [],
            issues: [],
            incidents: [],
            entities: [],
            policies: [[id: 900, name: 'CPU Policy', incident_preference: 'PER_POLICY']],
            nrqlConditions: [],
            dashboardDetail: null,
            pageDetails: [:],
            widgetDetails: [:],
            lastNrql: null,
            lastGraphqlQuery: null,
            lastGraphqlVariables: null,
            forceMutation: null,
            forceVerificationMismatch: false
        ]
    }

    private static Closure restHandler(Map state) {
        return { method, url, headers, timeout, max ->
            if (url.contains('/applications/100/hosts')) {
                return response([application_hosts: state.hosts ?: []])
            }
            if (url.contains('/applications/100/deployments')) {
                return response([deployments: state.deployments ?: []])
            }
            if (url.contains('/applications/100.json')) {
                return response([application: (state.applications ?: [])[0] ?: [id: 100, name: 'App-100']])
            }
            if (url.contains('/applications.json')) {
                return response([applications: state.applications ?: []])
            }
            if (url.contains('/alerts_violations.json')) {
                return response([violations: state.violations ?: []])
            }
            if (url.contains('/alerts_policies/900.json')) {
                return response([policy: (state.policies ?: [])[0] ?: [id: 900, name: 'CPU Policy']])
            }
            if (url.contains('/alerts_policies.json')) {
                return response([policies: state.policies ?: []])
            }
            if (url.contains('/alerts_nrql_conditions/') && url.contains('.json')) {
                String id = (url =~ /alerts_nrql_conditions\/(\d+)\.json/)[0][1]
                Map condition = (state.nrqlConditions ?: []).find { it.id.toString() == id } ?:
                    [id: id as int, name: 'CPU High', policy_id: 900, enabled: true, nrql: [query: 'SELECT 1'],
                     terms: [[priority: 'CRITICAL']], signal: [aggregationWindow: 60], violation_time_limit_seconds: 3600]
                return response([nrql_condition: condition])
            }
            if (url.contains('/alerts_nrql_conditions.json')) {
                return response([nrql_conditions: state.nrqlConditions ?: []])
            }
            if (url.contains('/alerts_conditions.json') || url.contains('/alerts_external_service_conditions.json') ||
                url.contains('/alerts_synthetics_conditions.json')) {
                return response([conditions: []])
            }
            response([:])
        }
    }

    private static Closure graphqlHandler(Map state) {
        return { method, url, headers, payload, timeout, max ->
            Map body = payload instanceof Map ? payload : [:]
            String query = body.query?.toString() ?: ''
            Map variables = body.variables instanceof Map ? (Map) body.variables : [:]
            state.lastGraphqlQuery = query
            state.lastGraphqlVariables = variables
            if (query.contains('mutation')) {
                state.mutationCalls = (state.mutationCalls ?: 0) + 1
                if (state.forceMutation) {
                    return graphqlResponse([:], [[message: 'blocked']])
                }
                if (query.contains('alertsNrqlConditionStaticCreate')) {
                    Map condition = variables.condition instanceof Map ? (Map) variables.condition : [:]
                    String storedName = state.forceVerificationMismatch ? 'Mismatch Name' : condition.name
                    Map created = [
                        id: '8001', name: storedName, enabled: condition.enabled,
                        policy_id: variables.policyId, nrql: condition.nrql,
                        terms: condition.terms, signal: condition.signal,
                        violation_time_limit_seconds: condition.violationTimeLimitSeconds
                    ]
                    state.nrqlConditions = (state.nrqlConditions ?: []) + created
                    return graphqlResponse([alertsNrqlConditionStaticCreate: [id: '8001', name: condition.name, enabled: condition.enabled]])
                }
                if (query.contains('alertsNrqlConditionStaticUpdate')) {
                    Map condition = variables.condition instanceof Map ? (Map) variables.condition : [:]
                    String storedName = state.forceVerificationMismatch ? 'Mismatch Name' : condition.name
                    state.nrqlConditions = (state.nrqlConditions ?: []).collect {
                        it.id.toString() == variables.id.toString() ?
                            it + [name: storedName, enabled: condition.enabled, nrql: condition.nrql,
                                  terms: condition.terms, signal: condition.signal,
                                  violation_time_limit_seconds: condition.violationTimeLimitSeconds] :
                            it
                    }
                    return graphqlResponse([alertsNrqlConditionStaticUpdate: [id: variables.id, name: condition.name, enabled: condition.enabled]])
                }
                if (query.contains('dashboardCreateWidget')) {
                    Map input = variables.input instanceof Map ? (Map) variables.input : [:]
                    Map widget = [
                        guid: 'NEW-WIDGET',
                        title: input.title ?: 'New Widget',
                        visualization: input.visualization ?: [id: 'viz.line'],
                        layout: input.layout ?: [row: 1, column: 1, width: 4, height: 3],
                        rawConfiguration: input.rawConfiguration
                    ]
                    addWidgetToState(state, input.pageGuid?.toString() ?: 'PAGE-GUID', widget)
                    return graphqlResponse([dashboardCreateWidget: [widget: [guid: widget.guid, title: widget.title], errors: []]])
                }
                if (query.contains('dashboardCreatePage')) {
                    Map page = [
                        guid: 'NEW-PAGE',
                        name: variables.page?.name ?: 'Overview',
                        description: variables.page?.description,
                        widgets: []
                    ]
                    addPageToState(state, variables.dashboardGuid?.toString() ?: 'DASH-GUID', page)
                    return graphqlResponse([dashboardCreatePage: [entityResult: [guid: page.guid, name: page.name], errors: []]])
                }
                if (query.contains('dashboardCreate') && !query.contains('dashboardCreatePage') && !query.contains('dashboardCreateWidget')) {
                    state.dashboardDetail = [
                        guid: 'NEW-DASH',
                        name: variables.dashboard?.name ?: 'Created Dashboard',
                        permissions: variables.dashboard?.permissions ?: 'PRIVATE',
                        pages: []
                    ]
                    return graphqlResponse([dashboardCreate: [entityResult: [guid: 'NEW-DASH', name: variables.dashboard?.name], errors: []]])
                }
                if (query.contains('dashboardUpdateWidget')) {
                    Map input = variables.input instanceof Map ? (Map) variables.input : [:]
                    String widgetGuid = input.guid?.toString() ?: 'WIDGET-GUID'
                    Map widget = [
                        guid: widgetGuid,
                        title: input.title ?: 'Updated Widget',
                        visualization: input.visualization ?: [id: 'viz.line'],
                        layout: input.layout ?: [row: 1, column: 1, width: 4, height: 3],
                        rawConfiguration: input.rawConfiguration
                    ]
                    updateWidgetInState(state, widgetGuid, widget)
                    return graphqlResponse([dashboardUpdateWidget: [widget: [guid: widget.guid, title: widget.title], errors: []]])
                }
                if (query.contains('dashboardUpdatePage')) {
                    String pageGuid = variables.guid?.toString() ?: 'PAGE-GUID'
                    Map page = [
                        guid: pageGuid,
                        name: variables.page?.name ?: 'Updated Page',
                        description: variables.page?.description,
                        widgets: state.pageDetails[pageGuid]?.widgets ?: []
                    ]
                    updatePageInState(state, pageGuid, page)
                    return graphqlResponse([dashboardUpdatePage: [entityResult: [guid: page.guid, name: page.name], errors: []]])
                }
                return graphqlResponse([:], [[message: 'unknown mutation']])
            }
            if (query.contains('actor { user')) {
                return graphqlResponse([actor: [user: [id: 1, email: 'user@example.com', name: 'User']]])
            }
            if (query.contains('entitySearch')) {
                List entities = (state.entities ?: []).collect { Map item ->
                    [guid: item.guid, name: item.name, type: item.type, domain: item.domain, accountId: item.accountId, tags: []]
                }
                return graphqlResponse([actor: [entitySearch: [results: [entities: entities, nextCursor: null]]]])
            }
            if (query.contains('PageDetail')) {
                String pageGuid = variables.guid?.toString() ?: 'PAGE-GUID'
                Map page = state.pageDetails[pageGuid] ?: [guid: pageGuid, name: 'Overview', description: 'Page', widgets: []]
                return graphqlResponse([actor: [entity: page]])
            }
            if (query.contains('WidgetDetail')) {
                String widgetGuid = variables.guid?.toString() ?: 'WIDGET-GUID'
                Map widget = state.widgetDetails[widgetGuid] ?: [
                    guid: widgetGuid, title: 'Errors',
                    visualization: [id: 'viz.line'], layout: [row: 1, column: 1, width: 4, height: 3],
                    rawConfiguration: '{}'
                ]
                return graphqlResponse([actor: [entity: widget]])
            }
            if (query.contains('DashboardDetail') || query.contains('entity(guid')) {
                Map dashboard = state.dashboardDetail ?: dashboardFixture()
                if (!state.pageDetails) {
                    return graphqlResponse([actor: [entity: dashboard]])
                }
                List pages = (dashboard.pages ?: []).collect { Map page ->
                    Map stored = state.pageDetails[page.guid?.toString()]
                    stored ? stored : page
                }
                return graphqlResponse([actor: [entity: dashboard + [pages: pages]]])
            }
            if (query.contains('nrAiIncidents')) {
                List incidents = (state.incidents ?: []).collect { Map item ->
                    [
                        incidentId: item.incidentId, title: item.title, priority: item.priority, state: item.state,
                        createdAt: item.createdAt, updatedAt: item.updatedAt, entity: item.entity,
                        conditionId: item.conditionId, issueId: item.issueId
                    ]
                }
                return graphqlResponse([actor: [account: [aiIssues: [nrAiIncidents: [incidents: incidents, nextCursor: null]]]]])
            }
            if (query.contains('AiIssues') && !query.contains('nrAiIncidents')) {
                List issues = (state.issues ?: []).collect { Map item ->
                    [
                        issueId: item.issueId, title: item.title, priority: item.priority, state: item.state,
                        createdAt: item.createdAt, updatedAt: item.updatedAt, entities: item.entities
                    ]
                }
                return graphqlResponse([actor: [account: [aiIssues: [issues: issues, nextCursor: null]]]])
            }
            if (query.contains('Errors(')) {
                return graphqlResponse([actor: [account: [
                    errSummary: [results: [[count: 3]]],
                    errByClass: [results: [[facet: 'Error', count: 2]]],
                    errByMessage: [results: [[facet: 'Error', 'error.message': 'boom', count: 2]]],
                    errByTransaction: [results: [[facet: '/tx', count: 2]]],
                    errByHost: [results: [[facet: 'host-a', count: 2]]],
                    errTimeline: [results: [[beginTimeSeconds: 1, endTimeSeconds: 2, count: 1]]]
                ]]])
            }
            if (query.contains('nrql')) {
                state.lastNrql = variables.nrql?.toString()
                return graphqlResponse([actor: [account: [nrql: [results: [[count: 1]], metadata: [timeWindow: [begin: 1, end: 2], facets: []]]]]])
            }
            graphqlResponse([actor: [:]])
        }
    }

    private static Map dashboardFixture() {
        [
            guid: 'DASH-GUID',
            name: 'Ops Dashboard',
            accountId: 123456,
            description: 'Ops',
            permissions: 'PRIVATE',
            pages: [[
                guid: 'PAGE-GUID', name: 'Overview', description: 'Page',
                widgets: [[
                    guid: 'WIDGET-GUID', title: 'Errors',
                    layout: [row: 1, column: 1, width: 4, height: 3],
                    visualization: [id: 'viz.line'],
                    configuration: [line: [nrqlQueries: [[accountId: 123456, query: "SELECT count(*) FROM Transaction WHERE name = 'x'"]]]],
                    rawConfiguration: '{"nrqlQueries":[{"accountId":123456,"query":"SELECT count(*) FROM Transaction WHERE name = \'x\'"}]}',
                    linkedEntityGuids: []
                ]]
            ]]
        ]
    }

    private static String conditionDefinitionJson(String name = 'Updated CPU') {
        JsonOutput.toJson([
            name: name,
            enabled: true,
            nrql: [query: 'SELECT average(cpuPercent) FROM SystemSample'],
            signal: [aggregationWindow: 60],
            terms: [[
                priority: 'CRITICAL', operator: 'ABOVE', threshold: 5,
                thresholdDuration: 300, thresholdOccurrences: 'ALL'
            ]],
            violationTimeLimitSeconds: 3600
        ])
    }

    private static String dashboardDefinitionJson(String name = 'Created Dashboard') {
        JsonOutput.toJson([kind: 'dashboard', name: name, permissions: 'PRIVATE'])
    }

    private static String pageDefinitionJson(String name = 'Overview') {
        JsonOutput.toJson([kind: 'page', name: name])
    }

    private static String widgetDefinitionJson(String title = 'Errors') {
        JsonOutput.toJson([
            kind: 'widget', title: title,
            visualization: [id: 'viz.line'],
            layout: [row: 1, column: 1, width: 4, height: 3]
        ])
    }

    private static void seedDashboardState(Map state) {
        Map dashboard = state.dashboardDetail ?: dashboardFixture()
        state.dashboardDetail = dashboard
        state.pageDetails = state.pageDetails ?: [:]
        state.widgetDetails = state.widgetDetails ?: [:]
        dashboard.pages?.each { Map page ->
            state.pageDetails[page.guid.toString()] = page
            page.widgets?.each { Map widget ->
                state.widgetDetails[widget.guid.toString()] = widget
            }
        }
    }

    private static void addPageToState(Map state, String dashboardGuid, Map page) {
        state.pageDetails = state.pageDetails ?: [:]
        state.pageDetails[page.guid.toString()] = page
        Map dashboard = state.dashboardDetail ?: dashboardFixture()
        if (dashboard.guid?.toString() == dashboardGuid) {
            dashboard.pages = (dashboard.pages ?: []) + [page]
            state.dashboardDetail = dashboard
        }
    }

    private static void updatePageInState(Map state, String pageGuid, Map page) {
        state.pageDetails = state.pageDetails ?: [:]
        state.pageDetails[pageGuid] = page
        Map dashboard = state.dashboardDetail ?: dashboardFixture()
        dashboard.pages = (dashboard.pages ?: []).collect {
            it.guid?.toString() == pageGuid ? page : it
        }
        state.dashboardDetail = dashboard
    }

    private static void addWidgetToState(Map state, String pageGuid, Map widget) {
        state.widgetDetails = state.widgetDetails ?: [:]
        state.widgetDetails[widget.guid.toString()] = widget
        Map dashboard = state.dashboardDetail ?: dashboardFixture()
        dashboard.pages = (dashboard.pages ?: []).collect { Map page ->
            if (page.guid?.toString() == pageGuid) {
                page.widgets = (page.widgets ?: []) + [widget]
                state.pageDetails[pageGuid] = page
            }
            page
        }
        state.dashboardDetail = dashboard
    }

    private static void updateWidgetInState(Map state, String widgetGuid, Map widget) {
        state.widgetDetails = state.widgetDetails ?: [:]
        state.widgetDetails[widgetGuid] = widget
        Map dashboard = state.dashboardDetail ?: dashboardFixture()
        dashboard.pages = (dashboard.pages ?: []).collect { Map page ->
            page.widgets = (page.widgets ?: []).collect { Map existing ->
                if (existing.guid?.toString() == widgetGuid) {
                    widget
                } else {
                    existing
                }
            }
            if (page.widgets?.any { it.guid?.toString() == widgetGuid }) {
                state.pageDetails[page.guid?.toString()] = page
            }
            page
        }
        state.dashboardDetail = dashboard
    }

    private File workspaceFile(String relativePath, String content) {
        File file = new File(workspace, relativePath)
        file.parentFile.mkdirs()
        file.setText(content, 'UTF-8')
        file
    }

    private void writeProperties(String content) {
        new File(workspace, 'integrations/newrelic/newrelic.properties').setText(content, 'UTF-8')
    }

    private static String nrakPrefix() {
        'NR' + 'AK'
    }

    private static String syntheticNrakKey(String label) {
        "${nrakPrefix()}-${label}-KEY-VALUE-${'0' * 10}"
    }

    private static String entityGuid(String suffix) {
        "${'GU' + 'ID'}-${suffix}"
    }

    private static String defaultProperties(String apiKey = null) {
        String key = apiKey ?: syntheticNrakKey('PRIMARY')
        """primary.api_key=${key}
primary.account_id=123456
primary.rest_url=https://api.eu.newrelic.com/v2
primary.graphql_url=https://api.eu.newrelic.com/graphql
"""
    }

    private static Map response(Object payload) {
        [code: 200, body: JsonOutput.toJson(payload), error: '']
    }

    private static Map graphqlResponse(Map data, List errors = []) {
        [code: 200, body: JsonOutput.toJson([data: data, errors: errors]), error: '']
    }

    private List<String> validateReport(Map report) {
        NewRelicOperatorReport rendered = NewRelicOperatorReport.fromPayload(report)
        Map payload = rendered.toMap(new Redaction(repository))
        validate(payload, schema, schema, '$')
    }

    private static List<String> validate(Object value, Map schema, Map root, String path) {
        if (schema.'$ref') {
            Map resolved = root
            schema.'$ref'.toString().substring(2).split('/').each { resolved = (Map) resolved[it] }
            return validate(value, resolved, root, path)
        }
        if (schema['oneOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['oneOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.count { !it } == 1 ? [] : ["${path}: expected exactly one schema match"]
        }
        if (schema['anyOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['anyOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.any { !it } ? [] : ["${path}: expected at least one schema match"]
        }
        List<String> errors = []
        if (schema['const'] != null && value != schema['const']) {
            errors << "${path}: expected ${schema['const']}"
        }
        if (schema['enum'] instanceof List && !((List) schema['enum']).contains(value)) {
            errors << "${path}: unexpected value ${value}"
        }
        if (schema['type'] == 'object' || schema['required'] || schema['properties'] || schema['not']) {
            if (!(value instanceof Map)) {
                return ["${path}: expected object"]
            }
            Map map = (Map) value
            ((List) (schema['required'] ?: [])).each {
                if (!map.containsKey(it)) {
                    errors << "${path}: missing ${it}"
                }
            }
            if (schema['not'] instanceof Map && !validate(value, (Map) schema['not'], root, path)) {
                errors << "${path}: matched forbidden schema"
            }
            Map properties = (Map) (schema['properties'] ?: [:])
            map.each { key, item ->
                if (properties[key] instanceof Map) {
                    errors.addAll(validate(item, (Map) properties[key], root, "${path}.${key}"))
                } else if (schema['additionalProperties'] == false) {
                    errors << "${path}: unexpected ${key}"
                }
            }
        } else if (schema['type'] == 'array') {
            if (!(value instanceof List)) {
                return ["${path}: expected array"]
            }
            if (schema['items'] instanceof Map) {
                ((List) value).eachWithIndex { item, index ->
                    errors.addAll(validate(item, (Map) schema['items'], root, "${path}[${index}]"))
                }
            }
        } else if (schema['type'] == 'string') {
            if (!(value instanceof String)) {
                errors << "${path}: expected string"
            }
        } else if (schema['type'] == 'boolean' && !(value instanceof Boolean)) {
            errors << "${path}: expected boolean"
        }
        errors
    }

    private static Map captureStreams(Closure<Integer> operation) {
        PrintStream originalOut = System.out
        PrintStream originalErr = System.err
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()
        try {
            System.setOut(new PrintStream(out))
            System.setErr(new PrintStream(err))
            int code = operation.call()
            return [code: code, out: out.toString('UTF-8'), err: err.toString('UTF-8')]
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

}
