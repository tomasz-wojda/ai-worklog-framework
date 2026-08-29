package ai.worklog.framework

import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.core.CheckResult
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status
import groovy.test.GroovyTestCase

class PreflightStatesTest extends GroovyTestCase {
    File workspace
    FrameworkPaths paths

    void setUp() {
        workspace = File.createTempDir('ai-worklog-', '-preflight')
        new File(workspace, 'worklog').mkdirs()
        paths = new FrameworkPaths(workspace)
    }

    void tearDown() {
        workspace.deleteDir()
    }

    private File service(String name, boolean populated = false) {
        File directory = new File(workspace, "integrations/${name}")
        directory.mkdirs()
        if (populated) {
            new File(directory, 'config').setText('x', 'UTF-8')
        }
        directory
    }

    private CheckResult only(ResultSet results) {
        assertEquals(1, results.results.size())
        results.results[0]
    }

    void testMissingDirectoryIsBlocked() {
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        assertEquals(Status.BLOCKED, only(results).status)
    }

    void testEmptyDirectoryIsNotConfigured() {
        service('datadog')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        CheckResult result = only(results)
        assertEquals(Status.NOT_CONFIGURED, result.status)
        assertEquals('Not configured', result.message)
    }

    void testPopulatedDirectoryIsReady() {
        service('datadog', true)
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        assertEquals(Status.READY, only(results).status)
    }

    void testNotConfiguredDoesNotBlockOverall() {
        service('datadog')
        service('newrelic', true)
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        PreflightCommands.checkServiceDirectory(results, paths, 'newrelic')
        assertEquals(Status.READY, results.overallStatus())
        assertEquals([], results.actionable())
    }

    void testAllNotConfiguredIsStillReady() {
        service('datadog')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        assertEquals(Status.READY, results.overallStatus())
    }

    void testEmptyDirectoryWithRequiredFileIsNotConfigured() {
        service('jenkins')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceFile(results, paths, 'jenkins', 'jenkins.properties')
        assertEquals(Status.NOT_CONFIGURED, only(results).status)
    }

    void testPopulatedDirectoryMissingRequiredFileIsDegraded() {
        service('jenkins', true)
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceFile(results, paths, 'jenkins', 'jenkins.properties')
        CheckResult result = only(results)
        assertEquals(Status.DEGRADED, result.status)
        assertEquals('jenkins.properties missing', result.message)
    }

    void testRequiredFilePresentIsReady() {
        File directory = service('jenkins')
        new File(directory, 'jenkins.properties').setText('a=b', 'UTF-8')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceFile(results, paths, 'jenkins', 'jenkins.properties')
        assertEquals(Status.READY, only(results).status)
    }

    void testJiraFollowsTheSameFourStates() {
        File directory = service('jira')

        ResultSet empty = new ResultSet()
        PreflightCommands.checkJira(empty, paths)
        assertEquals(Status.NOT_CONFIGURED, only(empty).status)

        new File(directory, 'notes').setText('x', 'UTF-8')
        ResultSet populated = new ResultSet()
        PreflightCommands.checkJira(populated, paths)
        assertEquals(Status.DEGRADED, only(populated).status)

        new File(directory, 'jira.properties').setText('a=b', 'UTF-8')
        ResultSet configured = new ResultSet()
        PreflightCommands.checkJira(configured, paths)
        assertEquals(Status.READY, only(configured).status)
    }

    void testSummaryLabelsNotConfigured() {
        service('datadog')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        assertTrue(results.summary().contains('[NOT CONFIGURED] datadog: Not configured'))
    }
}
