package ai.worklog.framework

import ai.worklog.framework.setup.SetupRules
import groovy.json.JsonOutput
import groovy.test.GroovyTestCase

class SetupRulesTest extends GroovyTestCase {
    private File vault
    private File workspace
    private Closure<Map> originalRunner
    private List<List<String>> commands

    void setUp() {
        vault = File.createTempDir('ai-worklog-', '-vault')
        workspace = File.createTempDir('ai-worklog-', '-workspace')
        new File(vault, 'scripts').mkdirs()
        new File(vault, SetupRules.INSTALLER).text = ''
        originalRunner = SetupRules.runner
        commands = []
    }

    void tearDown() {
        SetupRules.runner = originalRunner
        vault.deleteDir()
        workspace.deleteDir()
    }

    void testPreviewWithPendingFilesIsDegraded() {
        stub(0, [actions: [action('create'), action('unchanged')]])
        Map rules = SetupRules.run(vault, workspace, false)
        assertEquals('degraded', rules.status)
        assertEquals('1 workspace rule file(s) missing or outdated', rules.message)
        assertEquals(['--workspace-rules', workspace.canonicalPath], commands[0][2..3])
        assertFalse(commands[0].contains('--apply'))
    }

    void testApplyInstallsAndReportsReady() {
        stub(0, [actions: [action('create'), action('adopt')]])
        Map rules = SetupRules.run(vault, workspace, true)
        assertEquals('ready', rules.status)
        assertEquals('Installed 2 workspace rule file(s)', rules.message)
        assertTrue(commands[0].contains('--apply'))
    }

    void testCurrentRulesAreReady() {
        stub(0, [actions: [action('unchanged')]])
        Map check = SetupRules.check(vault, workspace)
        assertEquals([layer: 'rules', status: 'ready', message: 'Workspace rules current'], check)
    }

    void testRefusalIsBlocked() {
        stub(2, [error: 'refusing to replace /w/AGENTS.md'])
        Map rules = SetupRules.run(vault, workspace, true)
        assertEquals('blocked', rules.status)
        assertTrue(rules.message.contains('refusing to replace'))
    }

    void testMissingInstallerOrBadOutputIsUnknownAndChecksDegraded() {
        new File(vault, SetupRules.INSTALLER).delete()
        assertEquals('unknown', SetupRules.run(vault, workspace, false).status)
        assertEquals('degraded', SetupRules.check(vault, workspace).status)
        new File(vault, SetupRules.INSTALLER).text = ''
        SetupRules.runner = { List<String> command -> [code: 1, out: 'Traceback'] }
        assertEquals('unknown', SetupRules.run(vault, workspace, false).status)
        SetupRules.runner = { List<String> command -> throw new IOException('python3 not found') }
        assertTrue(SetupRules.run(vault, workspace, false).message.contains('python3 not found'))
    }

    private void stub(int code, Map payload) {
        SetupRules.runner = { List<String> command ->
            commands << command
            [code: code, out: JsonOutput.toJson(payload)]
        }
    }

    private static Map action(String status) {
        [kind: 'symlink', target: '/w/AGENTS.md', status: status, relative: 'x', source: 'y']
    }
}
