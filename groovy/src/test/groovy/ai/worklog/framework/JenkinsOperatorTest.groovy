package ai.worklog.framework

import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.adapters.BinaryDownloadClient
import ai.worklog.framework.adapters.PropertiesSupport
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
import ai.worklog.framework.commands.JenkinsCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.jenkins.JenkinsOperatorReport
import ai.worklog.framework.reconciliation.Observation
import ai.worklog.framework.reconciliation.ReconciliationComparators
import ai.worklog.framework.reconciliation.ReconciliationEngine
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

class JenkinsOperatorTest extends GroovyTestCase {
    private File repository
    private File workspace
    private File home
    private String originalTestHome

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-jenkins-', '-test')
        home = File.createTempDir('ai-worklog-jenkins-home-', '-test')
        originalTestHome = System.getProperty('ai.worklog.test.home')
        System.setProperty('ai.worklog.test.home', home.path)
        new File(workspace, 'integrations/jenkins').mkdirs()
    }

    void tearDown() {
        if (originalTestHome) {
            System.setProperty('ai.worklog.test.home', originalTestHome)
        } else {
            System.clearProperty('ai.worklog.test.home')
        }
        workspace.deleteDir()
        home.deleteDir()
    }

    void testEncodeJobPathNested() {
        assertEquals('job/folder/job/sub/job/job', JenkinsAdapter.encodeJobPath('folder/sub/job'))
    }

    void testControllerPublicInfoRedactsSecrets() {
        writeProperties('primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret-token\n')
        Map controllers = PropertiesSupport.controllers(new File(workspace, 'integrations/jenkins/jenkins.properties'))
        List publicInfo = JenkinsAdapter.controllerPublicInfo(controllers)
        assertEquals([[id: 'primary', url: 'https://jenkins.example', has_user: true, has_token: true]], publicInfo)
        assertFalse JsonOutputWrapper.json(publicInfo).contains('secret-token')
        assertFalse JsonOutputWrapper.json(publicInfo).contains('bot')
    }

    void testOperatorControllersNoNetwork() {
        writeProperties('alpha.url=https://a.example\nalpha.user=u\nalpha.token=t\n')
        Map report = adapterWithMocks([:]).operatorControllers()
        assertEquals(Status.READY, report.status)
        assertEquals('alpha', report.items[0].id)
        assertNotNull report.fetched_at
    }

    void testOperatorControllersBlockedWithoutProperties() {
        Map report = adapterWithMocks([:]).operatorControllers()
        assertEquals(Status.BLOCKED, report.status)
        assertEquals(3, exitCode(report))
    }

    void testOperatorHealthQuietingDown() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.startsWith('https://jenkins.example/api/json')
            [code: 200, body: '{"mode":"NORMAL","quietingDown":true,"numExecutors":2,"nodeDescription":"controller"}', error: '']
        }
        Map report = adapter.operatorHealth('primary', 5)
        assertEquals(Status.DEGRADED, report.status)
        assertTrue report.items[0].quieting_down
        assertEquals(0, exitCode(report))
    }

    void testOperatorHealthMissingController() {
        Map report = adapterWithMocks([:]).operatorHealth('missing', 5)
        assertEquals(Status.ERROR, report.status)
    }

    void testOperatorHealthMalformedResponse() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout -> [code: 200, body: '[]', error: ''] }
        Map report = adapter.operatorHealth('primary', 5)
        assertEquals(Status.ERROR, report.status)
        assertEquals(2, exitCode(report))
    }

    void testOperatorHealthAccessBlocked() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout -> [code: 403, body: '', error: ''] }
        Map report = adapter.operatorHealth('primary', 5)
        assertEquals(Status.BLOCKED, report.status)
    }

    void testValidateJobNameAllowsLeadingUnderscore() {
        JenkinsAdapter.validateJobName('_seed')
        shouldFail(IllegalArgumentException) {
            JenkinsAdapter.validateJobName('../bad')
        }
    }

    void testValidateJobNameAllowsLeadingTilde() {
        JenkinsAdapter.validateJobName('~seed-job')
    }

    void testOperatorJobRecentBuildLimit() {
        writeProperties(defaultProperties())
        List<String> captured = []
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            captured << url
            [code: 200, body: '''
            {
              "name": "Demo",
              "url": "https://jenkins.example/job/Demo/",
              "color": "blue",
              "buildable": true,
              "inQueue": false,
              "lastBuild": {"number": 3, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": false},
              "builds": [
                {"number": 3, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": false},
                {"number": 2, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": false},
                {"number": 1, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": false}
              ]
            }
            ''', error: '']
        }
        Map report = adapter.operatorJob('primary', 'folder/sub/job', 2, false, 5)
        assertTrue captured[0].contains('job/folder/job/sub/job/job')
        assertEquals(2, report.items[0].recent_builds.size())
        assertEquals('blue', report.items[0].color)
    }

    void testOperatorJobParametersValuePresentOnly() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: '''
            {
              "name": "Demo",
              "url": "https://jenkins.example/job/Demo/",
              "color": "blue",
              "buildable": true,
              "inQueue": false,
              "actions": [{"parameterDefinitions": [{"name": "BRANCH"}, {"name": "API_TOKEN"}]}],
              "lastBuild": {
                "number": 1,
                "result": "SUCCESS",
                "actions": [{"parameters": [{"name": "BRANCH", "value": "main"}, {"name": "API_TOKEN", "value": "secret-value"}]}]
              },
              "builds": []
            }
            ''', error: '']
        }
        Map report = adapter.operatorJob('primary', 'Demo', 1, true, 5)
        List parameters = report.items[0].parameters
        assertTrue parameters.every { !it.containsKey('value') }
        assertEquals(['BRANCH', '***REDACTED***'] as Set, parameters.collect { it.name } as Set)
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('secret-value')
    }

    void testOperatorPluginsRequiredBlocked() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: '{"plugins":[{"shortName":"workflow-job","version":"1.0","active":true,"enabled":true}]}', error: '']
        }
        Map report = adapter.operatorPlugins('primary', ['workflow-job', 'missing'], 5)
        assertEquals(Status.BLOCKED, report.status)
        assertEquals(['missing'], report.required.missing)
        assertEquals(3, exitCode(report))
    }

    void testOperatorCredentialsProjection() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('/credentials/store/system/domain/_/')
            [code: 200, body: '''
            {
              "credentials": [{
                "id": "github-token",
                "typeName": "StringCredentialsImpl",
                "displayName": "github-token",
                "description": "GitHub token",
                "secretValue": "must-not-appear"
              }]
            }
            ''', error: '']
        }
        Map report = adapter.operatorCredentials('primary', '_', 5)
        Map item = report.items[0]
        assertEquals(['id', 'type_name', 'display_name', 'description'] as Set, item.keySet())
        assertEquals('_', report.domain)
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('must-not-appear')
    }

    void testOperatorSeedRecentFailure() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: '''
            {
              "name": "Seed",
              "url": "https://jenkins.example/job/Seed/",
              "color": "red",
              "buildable": true,
              "inQueue": false,
              "lastBuild": {"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": false},
              "builds": [{"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": false}]
            }
            ''', error: '']
        }
        Map report = adapter.operatorSeed('primary', 'Seed', 5, 3)
        assertEquals('seed', report.operation)
        assertTrue report.items[0].recent_failure
        assertEquals(Status.DEGRADED, report.status)
    }

    void testOperatorSyntaxCheckSuccess() {
        File script = new File(workspace, 'syntax_check.sh')
        script.setText('#!/bin/sh\nexit 0\n', 'UTF-8')
        script.setExecutable(true)
        File target = new File(workspace, 'Jenkinsfile.groovy')
        target.setText('pipeline { agent any; stages {} }', 'UTF-8')
        JsonFiles.write(new File(workspace, '.ai-worklog/config.json'), [
            adapters: [jenkins: [syntax_check_script: script.absolutePath]]
        ])
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.process.executeHandler = { command, timeout ->
            assert new File(command[0] as String).canonicalFile == script.canonicalFile
            [code: 0, out: 'SYNTAX OK', err: '']
        }
        Map report = adapter.operatorSyntaxCheck([target.absolutePath], 5)
        assertEquals(Status.READY, report.status)
    }

    void testOperatorSyntaxCheckMissingScript() {
        File target = new File(workspace, 'Jenkinsfile.groovy')
        target.setText('pipeline {}', 'UTF-8')
        Map report = adapterWithMocks([:]).operatorSyntaxCheck([target.absolutePath], 5)
        assertEquals(Status.BLOCKED, report.status)
    }

    void testOperatorSyntaxCheckTimeout() {
        File script = new File(workspace, 'syntax_check.sh')
        script.setText('#!/bin/sh\nexit 0\n', 'UTF-8')
        script.setExecutable(true)
        File target = new File(workspace, 'Jenkinsfile.groovy')
        target.setText('pipeline {}', 'UTF-8')
        JsonFiles.write(new File(workspace, '.ai-worklog/config.json'), [
            adapters: [jenkins: [syntax_check_script: script.absolutePath]]
        ])
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.process.executeHandler = { command, timeout -> [code: 124, out: '', err: 'Timed out'] }
        Map report = adapter.operatorSyntaxCheck([target.absolutePath], 1)
        assertEquals(Status.BLOCKED, report.status)
        assertEquals('Syntax check timed out', report.message)
    }

    void testReportJsonRedactsSecrets() {
        Map payload = [
            operation: 'job',
            controller: 'primary',
            fetched_at: '2026-01-01T00:00:00Z',
            status: Status.READY,
            items: [[token: 'abc123-secret-value']]
        ]
        String rendered = JenkinsOperatorReport.fromPayload(payload).renderJson(new Redaction(repository))
        assertFalse rendered.contains('abc123-secret-value')
    }

    void testHumanOutputFormat() {
        Map payload = [
            operation: 'health',
            controller: 'primary',
            fetched_at: '2026-01-01T00:00:00Z',
            status: Status.READY,
            message: 'Controller is reachable',
            items: [[mode: 'NORMAL', quieting_down: false]]
        ]
        String output = JenkinsOperatorReport.fromPayload(payload).renderHuman(new Redaction(repository))
        assertTrue output.startsWith('Jenkins health\n')
        assertTrue output.contains('  Controller: primary\n')
        assertTrue output.contains('  Fetched: 2026-01-01T00:00:00Z\n')
        assertTrue output.contains('  Status: ready\n')
        assertTrue output.contains('  Message: Controller is reachable\n')
        assertTrue output.contains("  - {'mode': 'NORMAL', 'quieting_down': False}\n")
    }

    void testHumanOutputRedactsEmbeddedSecrets() {
        Map payload = [
            operation: 'syntax-check',
            fetched_at: '2026-01-01T00:00:00Z',
            status: Status.ERROR,
            message: 'token=message-secret',
            items: [[stdout: 'password=output-secret']]
        ]
        String output = JenkinsOperatorReport.fromPayload(payload).renderHuman(new Redaction(repository))
        assertFalse output.contains('message-secret')
        assertFalse output.contains('output-secret')
        assertTrue output.contains('***REDACTED***')
    }

    void testObserveJenkinsEnrichedDetails() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = new JenkinsAdapter(new FrameworkPaths(workspace), new ReadOnlyHttp(), ReconciliationEngine.defaultRules())
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('job/folder/job/job')
            [code: 200, body: '''
            {
              "color": "blue",
              "builds": [{"number": 10, "result": "SUCCESS", "timestamp": 100, "duration": 50, "building": false}],
              "lastBuild": {"number": 10, "result": "SUCCESS", "timestamp": 100, "duration": 50, "building": false}
            }
            ''', error: '']
        }
        Observation observation = adapter.observe(
            [builds: [[controller: 'primary', job: 'folder/job', number: 10, result: 'SUCCESS']]],
            [[controller: 'primary', job: 'folder/job']]
        )[0]
        assertEquals(Status.READY, observation.status)
        assertEquals('blue', observation.details.color)
        assertTrue observation.details.fetched_at.endsWith('Z')
        assertEquals(100, observation.details.last_build.timestamp)
        assertFalse observation.details.last_build.building
    }

    void testObserveUnresolvedContradiction() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = new JenkinsAdapter(new FrameworkPaths(workspace), new ReadOnlyHttp(), ReconciliationEngine.defaultRules())
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 404, body: '{"error":"not found"}', error: '']
        }
        List<Observation> observations = adapter.observe(
            [builds: [[controller: 'primary', job: 'Missing_Job', number: 1, result: 'SUCCESS']]],
            [[controller: 'primary', job: 'Missing_Job']]
        )
        assertEquals(Status.DEGRADED, observations[0].status)
        List contradictions = ReconciliationComparators.compareState(
            [builds: [[job: 'Missing_Job', number: 1, result: 'SUCCESS']]],
            observations,
            ReconciliationEngine.defaultRules()
        )
        assertEquals('jenkins_job_unresolved', contradictions[0].code)
    }

    void testCliControllersJson() {
        writeProperties('primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n')
        int code = JenkinsCommands.run('controllers', ['--json'], repository, new FrameworkPaths(workspace), ConfigLoader.load(workspace))
        assertEquals(0, code)
    }

    void testCliInvalidControllerExitCode() {
        int code = JenkinsCommands.run('health', ['../bad'], repository, new FrameworkPaths(workspace), ConfigLoader.load(workspace))
        assertEquals(1, code)
    }

    void testCliInvalidJobJsonEnvelope() {
        writeProperties(defaultProperties())
        int code = JenkinsCommands.run('job', ['primary', '../bad', '--json'], repository, new FrameworkPaths(workspace), ConfigLoader.load(workspace))
        assertEquals(1, code)
    }

    void testOperatorNodesProjection() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('/computer/api/json')
            [code: 200, body: '''
            {
              "computer": [{
                "displayName": "agent-1",
                "description": "Linux agent",
                "numExecutors": 2,
                "idle": false,
                "offline": false,
                "temporarilyOffline": false,
                "busyExecutors": 1,
                "assignedLabels": [{"name": "linux"}, {"name": "docker"}],
                "monitorData": {"secret": "must-not-appear"},
                "executors": [{"currentExecutable": {"secret": "hidden"}}]
              }]
            }
            ''', error: '']
        }
        Map report = adapter.operatorNodes('primary', 5)
        Map item = report.items[0]
        assertEquals(['display_name', 'description', 'num_executors', 'idle', 'offline', 'temporarily_offline', 'busy_executors', 'assigned_labels'] as Set, item.keySet())
        assertEquals(['docker', 'linux'], item.assigned_labels)
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('must-not-appear')
        assertEquals(Status.READY, report.status)
    }

    void testOperatorQueueLimitAndSort() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('/queue/api/json')
            [code: 200, body: '''
            {
              "items": [
                {"id": 2, "why": "Waiting", "stuck": false, "inQueueSince": 200, "blocked": false, "buildable": true,
                 "task": {"name": "Beta", "url": "https://jenkins.example/job/Beta/", "color": "blue"},
                 "actions": [{"parameters": [{"name": "TOKEN", "value": "secret"}]}]},
                {"id": 1, "why": "Blocked", "stuck": true, "inQueueSince": 100, "blocked": true, "buildable": false,
                 "task": {"name": "Alpha", "url": "https://jenkins.example/job/Alpha/", "color": "red"}}
              ]
            }
            ''', error: '']
        }
        Map report = adapter.operatorQueue('primary', 1, 5)
        assertEquals(1, report.items.size())
        assertEquals(1, report.items[0].id)
        assertEquals(Status.DEGRADED, report.status)
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('secret')
    }

    void testOperatorQueueEmptyReady() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: '{"items": []}', error: '']
        }
        Map report = adapter.operatorQueue('primary', 50, 5)
        assertEquals(Status.READY, report.status)
        assertEquals([], report.items)
        assertEquals(0, exitCode(report))
    }

    void testOperatorJobsBrowseAndQuery() {
        writeProperties(defaultProperties())
        List<String> captured = []
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            captured << url
            if (url.contains('/api/json?tree=') && !url.contains('/job/')) {
                return [code: 200, body: '''
                {
                  "jobs": [
                    {"name": "Alpha", "url": "https://jenkins.example/job/Alpha/", "color": "blue", "buildable": true, "inQueue": false, "_class": "hudson.model.FreeStyleProject"},
                    {"name": "folder", "url": "https://jenkins.example/job/folder/", "color": "blue", "buildable": true, "inQueue": false, "_class": "com.cloudbees.hudson.plugins.folder.Folder"}
                  ]
                }
                ''', error: '']
            }
            if (url.contains('/job/folder/api/json')) {
                return [code: 200, body: '''
                {
                  "jobs": [
                    {"name": "Nested", "url": "https://jenkins.example/job/folder/job/Nested/", "color": "blue", "buildable": true, "inQueue": false, "_class": "hudson.model.FreeStyleProject"}
                  ]
                }
                ''', error: '']
            }
            [code: 404, body: '{}', error: '']
        }
        Map browse = adapter.operatorJobs('primary', null, null, 100, 5)
        assertEquals(['Alpha', 'folder/Nested'] as Set, browse.items.collect { it.full_path } as Set)
        Map filtered = adapter.operatorJobs('primary', null, 'nested', 100, 5)
        assertEquals(['folder/Nested'], filtered.items.collect { it.full_path })
        assertEquals('jobs', filtered.operation)
        assertEquals('nested', filtered.query)
    }

    void testOperatorJobsFolderNotFound() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 404, body: '{}', error: '']
        }
        Map report = adapter.operatorJobs('primary', 'missing', null, 100, 5)
        assertEquals(Status.ERROR, report.status)
        assertTrue report.message.contains("Folder 'missing' not found")
        assertEquals(1, exitCode(report))
    }

    void testOperatorJobsInvalidQuery() {
        writeProperties(defaultProperties())
        shouldFail(IllegalArgumentException) {
            adapterWithMocks([:]).operatorJobs('primary', null, 'a' * 129, 100, 5)
        }
    }

    void testOperatorArtifactsSelectors() {
        writeProperties(defaultProperties())
        List<String> captured = []
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            captured << url
            [code: 200, body: '''
            {
              "number": 7,
              "url": "https://jenkins.example/job/Demo/7/",
              "result": "SUCCESS",
              "artifacts": [
                {"fileName": "report.txt", "relativePath": "out/report.txt", "content": "secret-content"}
              ]
            }
            ''', error: '']
        }
        Map byNumber = adapter.operatorArtifacts('primary', 'Demo', '7', 5)
        assertTrue captured[0].contains('/job/Demo/7/api/json')
        assertEquals(7, byNumber.items[0].resolved_build_number)
        assertEquals('7', byNumber.build_selector)
        Map alias = adapter.operatorArtifacts('primary', 'Demo', 'last-successful', 5)
        assertTrue captured[1].contains('/lastSuccessfulBuild/api/json')
        assertFalse JenkinsOperatorReport.fromPayload(byNumber).renderJson(new Redaction(repository)).contains('secret-content')
    }

    void testOperatorArtifactsTruncated() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            List artifacts = (1..201).collect { idx ->
                [fileName: "file-${idx}.txt", relativePath: "out/file-${idx}.txt"]
            }
            [code: 200, body: groovy.json.JsonOutput.toJson([
                number: 1, url: 'https://jenkins.example/job/Demo/1/', result: 'SUCCESS', artifacts: artifacts
            ]), error: '']
        }
        Map report = adapter.operatorArtifacts('primary', 'Demo', '1', 5)
        assertEquals(Status.DEGRADED, report.status)
        assertEquals(200, report.items[0].artifacts.size())
        assertTrue report.items[0].truncated
        assertEquals(201, report.items[0].artifact_count)
    }

    void testDownloadArtifactNumericBuildWritesExpectedDestination() {
        List<String> downloads = []
        BinaryDownloadClient binary = binaryClient([1, 2, 3] as byte[], downloads)
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'report.bin', relativePath: 'out/report.bin']],
            7,
            binary
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.bin',
            true,
            false
        )
        File target = new File(workspace, 'tmp/services/jenkins/primary/Demo/7/out/report.bin')
        assertEquals(Status.READY, report.status)
        assertEquals([1, 2, 3], target.bytes.toList())
        assertEquals('tmp/services/jenkins/primary/Demo/7/out/report.bin', report.items[0].local_path)
        assertEquals(1, downloads.size())
    }

    void testDownloadArtifactAliasAndNestedPathsUseResolvedBuild() {
        List<String> downloads = []
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'result.zip', relativePath: 'dist/packages/result.zip']],
            42,
            binaryClient([4] as byte[], downloads)
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'folder/sub/job',
            'last-successful',
            'dist/packages/result.zip',
            true,
            false
        )
        assertEquals(42, report.items[0].resolved_build_number)
        assertEquals(
            'tmp/services/jenkins/primary/folder/sub/job/42/dist/packages/result.zip',
            report.items[0].local_path
        )
        assertTrue(downloads[0].contains('/job/folder/job/sub/job/job/42/artifact/dist/packages/result.zip'))
    }

    void testDownloadArtifactMatchIsExactAndCaseSensitive() {
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'Report.txt', relativePath: 'out/Report.txt']],
            7,
            binaryClient([1] as byte[], [])
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            false,
            false
        )
        assertEquals(Status.ERROR, report.status)
        assertEquals('Artifact not found: out/report.txt', report.message)
    }

    void testDownloadArtifactCanSelectBeyondReportLimit() {
        List artifacts = (1..201).collect { int index ->
            [fileName: "file-${index}.txt", relativePath: "out/file-${index}.txt"]
        }
        List<String> downloads = []
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            artifacts,
            7,
            binaryClient([1] as byte[], downloads)
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/file-201.txt',
            true,
            false
        )
        assertEquals(Status.READY, report.status)
        assertEquals(1, downloads.size())
    }

    void testDownloadArtifactDryRunDoesNotCreateOrFetchContent() {
        List<String> downloads = []
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            binaryClient([1] as byte[], downloads)
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            false,
            false
        )
        assertEquals(Status.READY, report.status)
        assertTrue(report.items[0].dry_run)
        assertFalse(report.items[0].applied)
        assertEquals([], downloads)
        assertFalse(new File(workspace, 'tmp').exists())
    }

    void testDownloadArtifactRefusesExistingFileWithoutContentGet() {
        File target = new File(workspace, 'tmp/services/jenkins/primary/Demo/7/out/report.txt')
        target.parentFile.mkdirs()
        target.text = 'existing'
        List<String> downloads = []
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            binaryClient([1] as byte[], downloads)
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            true,
            false
        )
        assertEquals(Status.ERROR, report.status)
        assertTrue(report.message.contains('already exists'))
        assertEquals([], downloads)
        assertEquals('existing', target.text)
    }

    void testDownloadArtifactForceReplacesExistingFile() {
        File target = new File(workspace, 'tmp/services/jenkins/primary/Demo/7/out/report.txt')
        target.parentFile.mkdirs()
        target.bytes = [9] as byte[]
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            binaryClient([1, 2] as byte[], [])
        )
        Map report = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            true,
            true
        )
        assertEquals([1, 2], target.bytes.toList())
        assertTrue(report.items[0].replaced)
    }

    void testDownloadArtifactRejectsUnsafePaths() {
        ['/absolute', '../outside', 'out\\file', 'out//file', 'out/../file', "out/\u0000file"].each {
            String unsafe ->
            JenkinsAdapter adapter = adapterWithArtifactResponse(
                [[fileName: 'file', relativePath: unsafe]],
                7,
                binaryClient([1] as byte[], [])
            )
            shouldFail(IllegalArgumentException) {
                adapter.operatorDownloadArtifact('primary', 'Demo', '7', unsafe, false, false)
            }
        }
    }

    void testDownloadArtifactRejectsSymbolicLinkParent() {
        File outside = File.createTempDir('ai-worklog-download-outside-', '-test')
        try {
            File services = new File(workspace, 'tmp/services')
            services.mkdirs()
            java.nio.file.Files.createSymbolicLink(
                new File(services, 'jenkins').toPath(),
                outside.toPath()
            )
            JenkinsAdapter adapter = adapterWithArtifactResponse(
                [[fileName: 'report.txt', relativePath: 'out/report.txt']],
                7,
                binaryClient([1] as byte[], [])
            )
            assertTrue(shouldFail(IllegalArgumentException) {
                adapter.operatorDownloadArtifact(
                    'primary',
                    'Demo',
                    '7',
                    'out/report.txt',
                    false,
                    false
                )
            }.contains('symbolic link'))
        } finally {
            outside.deleteDir()
        }
    }

    void testDownloadArtifactReportJsonPreservesFieldsAndBooleans() {
        JenkinsAdapter adapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            binaryClient([1] as byte[], [])
        )
        Map payload = adapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            false,
            true
        )
        String json = JenkinsOperatorReport.fromPayload(payload).renderJson(new Redaction(repository))
        Map parsed = (Map) new JsonSlurper().parseText(json)
        assertEquals('download-artifact', parsed.operation)
        assertEquals(false, parsed.items[0].applied)
        assertEquals(true, parsed.items[0].dry_run)
        assertEquals(true, parsed.items[0].force)
        assertEquals('tmp/services/jenkins/primary/Demo/7/out/report.txt', parsed.items[0].local_path)
    }

    void testDownloadArtifactMapsAuthenticationAndSizeFailures() {
        BinaryDownloadClient blocked = new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
            [code: 401, error: 'HTTP 401', content_length: -1]
        })
        JenkinsAdapter blockedAdapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            blocked
        )
        Map blockedReport = blockedAdapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            true,
            false
        )
        assertEquals(Status.BLOCKED, blockedReport.status)
        assertEquals(3, exitCode(blockedReport))

        BinaryDownloadClient oversized = new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
            [
                code: 200,
                stream: new ByteArrayInputStream([1] as byte[]),
                content_length: 1073741825L
            ]
        })
        JenkinsAdapter oversizedAdapter = adapterWithArtifactResponse(
            [[fileName: 'report.txt', relativePath: 'out/report.txt']],
            7,
            oversized
        )
        Map oversizedReport = oversizedAdapter.operatorDownloadArtifact(
            'primary',
            'Demo',
            '7',
            'out/report.txt',
            true,
            false
        )
        assertEquals(Status.ERROR, oversizedReport.status)
        assertEquals(2, exitCode(oversizedReport))
        assertFalse(new File(workspace, 'tmp/services/jenkins/primary/Demo/7/out/report.txt').exists())
    }

    void testOperatorViewsListAndDetail() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            if (url.contains('/view/All/api/json')) {
                return [code: 200, body: '''
                {
                  "name": "All",
                  "url": "https://jenkins.example/view/All/",
                  "description": "All jobs",
                  "jobs": [{"name": "Demo", "url": "https://jenkins.example/job/Demo/", "color": "blue", "buildable": true, "inQueue": false}]
                }
                ''', error: '']
            }
            [code: 200, body: '''
            {
              "views": [
                {"name": "All", "url": "https://jenkins.example/view/All/", "description": "All jobs"}
              ]
            }
            ''', error: '']
        }
        Map listed = adapter.operatorViews('primary', null, 5)
        assertEquals('All', listed.items[0].name)
        Map detail = adapter.operatorViews('primary', 'All', 5)
        assertEquals('All', detail.view)
        assertEquals('Demo', detail.items[0].jobs[0].name)
    }

    void testOperatorWhoamiIdentityOnly() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('/whoAmI/api/json')
            [code: 200, body: '''
            {
              "name": "bot",
              "authenticated": true,
              "authorities": ["admin"],
              "anonymous": false
            }
            ''', error: '']
        }
        Map report = adapter.operatorWhoami('primary', 5)
        assertEquals(['name', 'authenticated'] as Set, report.items[0].keySet())
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('admin')
    }

    void testOperatorCredentialDomainsMetadataOnly() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            assert url.contains('/credentials/store/system/api/json')
            [code: 200, body: '''
            {
              "domains": [{
                "domainName": "_",
                "displayName": "Global",
                "description": "Global domain",
                "url": "https://jenkins.example/credentials/store/system/domain/_/",
                "credentials": [{"id": "secret-id", "secretValue": "must-not-appear"}]
              }]
            }
            ''', error: '']
        }
        Map report = adapter.operatorCredentialDomains('primary', 5)
        assertEquals(['domain_name', 'display_name', 'description', 'url'] as Set, report.items[0].keySet())
        assertFalse JenkinsOperatorReport.fromPayload(report).renderJson(new Redaction(repository)).contains('must-not-appear')
    }

    void testOperatorCredentialDomainsMapShape() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [code: 200, body: '''
            {
              "domains": {
                "_": {
                  "_class": "com.cloudbees.plugins.credentials.CredentialsStoreAction$DomainWrapper",
                  "displayName": "Global",
                  "description": "Credentials that should be available everywhere."
                }
              }
            }
            ''', error: '']
        }
        Map report = adapter.operatorCredentialDomains('primary', 5)
        assertEquals 1, report.items.size()
        assertEquals('_', report.items[0].domain_name)
        assertEquals('Global', report.items[0].display_name)
        assertEquals(
            'Credentials that should be available everywhere.',
            report.items[0].description
        )
        assertEquals(['domain_name', 'display_name', 'description', 'url'] as Set, report.items[0].keySet())
    }

    void testOperatorNodesAccessBlocked() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout -> [code: 401, body: '', error: ''] }
        Map report = adapter.operatorNodes('primary', 5)
        assertEquals(Status.BLOCKED, report.status)
        assertEquals(3, exitCode(report))
    }

    void testOperatorNodesMalformed() {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:])
        adapter.http.requestHandler = { method, url, headers, timeout -> [code: 200, body: '[]', error: ''] }
        Map report = adapter.operatorNodes('primary', 5)
        assertEquals(Status.ERROR, report.status)
        assertEquals(2, exitCode(report))
    }

    void testCliMissingControllerJson() {
        Map captured = captureStreams {
            JenkinsCommands.run(
                'nodes',
                ['--json'],
                repository,
                new FrameworkPaths(workspace),
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(1, captured.code)
        assertEquals('error', new JsonSlurper().parseText(captured.out).status)
        assertTrue(captured.err.contains('Missing controller'))
        assertTrue(captured.err.contains('Usage: ai-worklog service jenkins nodes <controller>'))
    }

    void testCliArtifactsInvalidSelector() {
        writeProperties(defaultProperties())
        Map captured = captureStreams {
            JenkinsCommands.run(
                'artifacts',
                ['primary', 'Demo', 'bad-selector', '--json'],
                repository,
                new FrameworkPaths(workspace),
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(1, captured.code)
        assertTrue(captured.err.contains('last-successful | last-completed | BUILD_NUMBER'))
        assertTrue(captured.out.contains('"operation": "artifacts"'))
    }

    void testCliUsageErrorWritesOnlyToStandardError() {
        Map captured = captureStreams {
            JenkinsCommands.run(
                'artifacts',
                [],
                repository,
                new FrameworkPaths(workspace),
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(1, captured.code)
        assertEquals('', captured.out)
        assertTrue(captured.err.contains('Missing controller'))
        assertTrue(captured.err.contains('<controller> <job> <build_selector>'))
    }

    void testValidateBuildSelector() {
        JenkinsAdapter.validateBuildSelector('42')
        JenkinsAdapter.validateBuildSelector('last-successful')
        JenkinsAdapter.validateBuildSelector('last-completed')
        shouldFail(IllegalArgumentException) {
            JenkinsAdapter.validateBuildSelector('0')
        }
        shouldFail(IllegalArgumentException) {
            JenkinsAdapter.validateBuildSelector('latest')
        }
    }

    void testExitCodeMissingInput() {
        Map payload = [
            operation: 'health',
            fetched_at: '2026-01-01T00:00:00Z',
            status: Status.ERROR,
            message: 'Missing controller',
            items: []
        ]
        assertEquals(1, exitCode(payload))
    }

    private JenkinsAdapter adapterWithArtifactResponse(
        List artifacts,
        int buildNumber,
        BinaryDownloadClient binaryDownload
    ) {
        writeProperties(defaultProperties())
        JenkinsAdapter adapter = adapterWithMocks([:], binaryDownload)
        adapter.http.requestHandler = { method, url, headers, timeout ->
            [
                code: 200,
                body: groovy.json.JsonOutput.toJson([
                    number: buildNumber,
                    url: "https://jenkins.example/job/Demo/${buildNumber}/",
                    result: 'SUCCESS',
                    artifacts: artifacts
                ]),
                error: ''
            ]
        }
        adapter
    }

    private static BinaryDownloadClient binaryClient(byte[] content, List<String> downloads) {
        new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
            downloads << url
            [
                code: 200,
                stream: new ByteArrayInputStream(content),
                content_length: content.length,
                error: ''
            ]
        })
    }

    private JenkinsAdapter adapterWithMocks(
        Map config,
        BinaryDownloadClient binaryDownload = null
    ) {
        new JenkinsAdapter(
            new FrameworkPaths(workspace),
            new ReadOnlyHttp(),
            [:],
            repository,
            config ?: ConfigLoader.load(workspace),
            new ReadOnlyProcess(new Redaction(repository)),
            binaryDownload
        )
    }

    private void writeProperties(String content) {
        new File(workspace, 'integrations/jenkins/jenkins.properties').setText(content, 'UTF-8')
    }

    private static String defaultProperties() {
        'primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret-token\n'
    }

    private int exitCode(Map payload) {
        JenkinsOperatorReport.exitCodeFor(JenkinsOperatorReport.fromPayload(payload), new ExitCodes(repository))
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

    private static class JsonOutputWrapper {
        static String json(Object value) {
            groovy.json.JsonOutput.toJson(value)
        }
    }
}
