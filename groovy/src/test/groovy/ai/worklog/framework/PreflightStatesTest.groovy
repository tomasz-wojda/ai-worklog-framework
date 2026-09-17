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
        File newrelicDir = service('newrelic')
        new File(newrelicDir, 'newrelic.properties').setText('primary.api_key=x', 'UTF-8')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceDirectory(results, paths, 'datadog')
        PreflightCommands.checkServiceFile(results, paths, 'newrelic', 'newrelic.properties')
        assertEquals(Status.READY, results.overallStatus())
        assertEquals([], results.actionable())
    }

    void testNewRelicMissingPropertiesFileIsDegradedWhenDirectoryPopulated() {
        service('newrelic', true)
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceFile(results, paths, 'newrelic', 'newrelic.properties')
        CheckResult result = only(results)
        assertEquals(Status.DEGRADED, result.status)
        assertEquals('newrelic.properties missing', result.message)
    }

    void testNewRelicPropertiesPresentIsReady() {
        File directory = service('newrelic')
        new File(directory, 'newrelic.properties').setText('primary.api_key=x', 'UTF-8')
        ResultSet results = new ResultSet()
        PreflightCommands.checkServiceFile(results, paths, 'newrelic', 'newrelic.properties')
        assertEquals(Status.READY, only(results).status)
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

    void testArtifactoryMissingDirectoryIsBlocked() {
        ResultSet results = new ResultSet()
        PreflightCommands.checkArtifactory(results, paths, [:])
        assertEquals(Status.BLOCKED, only(results).status)
    }

    void testArtifactoryEmptyDirectoryIsNotConfigured() {
        service('artifactory')
        ResultSet results = new ResultSet()
        PreflightCommands.checkArtifactory(results, paths, [:])
        assertEquals(Status.NOT_CONFIGURED, only(results).status)
    }

    void testArtifactoryRecognizesEachCredentialFilenameWithoutReadingIt() {
        ['artifactory.properties', 'credentials', 'creds'].each { filename ->
            File directory = service("artifactory-${filename.replace('.', '-')}")
            File canonical = new File(workspace, 'integrations/artifactory')
            canonical.deleteDir()
            assertTrue(directory.renameTo(canonical))
            new File(canonical, filename).setText('not valid credential syntax', 'UTF-8')
            ResultSet results = new ResultSet()
            PreflightCommands.checkArtifactory(results, paths, [:])
            CheckResult result = only(results)
            assertEquals(Status.READY, result.status)
            assertEquals('Credential source present (file)', result.message)
            canonical.deleteDir()
        }
    }

    void testArtifactoryRequiresBothEnvironmentValues() {
        service('artifactory')
        ResultSet incomplete = new ResultSet()
        PreflightCommands.checkArtifactory(
            incomplete,
            paths,
            [ARTIFACTORY_URL: 'https://example.invalid']
        )
        assertEquals(Status.NOT_CONFIGURED, only(incomplete).status)

        ResultSet configured = new ResultSet()
        PreflightCommands.checkArtifactory(
            configured,
            paths,
            [ARTIFACTORY_URL: 'https://example.invalid', ARTIFACTORY_TOKEN: 'secret']
        )
        assertEquals(Status.READY, only(configured).status)
        assertEquals('Credential source present (environment)', only(configured).message)
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
