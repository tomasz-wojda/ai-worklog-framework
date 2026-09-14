package ai.worklog.framework

import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JournalValidation
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status
import groovy.test.GroovyTestCase
import java.sql.Connection
import java.sql.DriverManager

class JournalValidationTest extends GroovyTestCase {
    File workspace
    File frameworkRoot

    void setUp() {
        workspace = File.createTempDir('ai-worklog-', '-journal')
        frameworkRoot = FrameworkPaths.resolveFrameworkRoot()
        new File(workspace, 'worklog').mkdirs()
    }

    void tearDown() {
        workspace.deleteDir()
    }

    private void createJournalDb(File db) {
        db.parentFile.mkdirs()
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}")
        try {
            connection.createStatement().execute(
                'CREATE TABLE journal_observations (observation_id TEXT PRIMARY KEY)'
            )
        } finally {
            connection.close()
        }
    }

    void testJournalDbValidRequiresNonEmptySqliteWithSchema() {
        File db = new File(workspace, 'journal.db')
        db.bytes = new byte[0]
        assertFalse(JournalValidation.journalDbValid(db, frameworkRoot))

        createJournalDb(db)
        assertTrue(JournalValidation.journalDbValid(db, frameworkRoot))
    }

    void testWorkspaceAuditStateAcceptsLegacyPromptLogOnly() {
        new File(workspace, 'prompt.log').createNewFile()
        List<Boolean> state = JournalValidation.workspaceAuditState(workspace, frameworkRoot)
        assertTrue(state[0])
        assertFalse(state[1])
    }

    void testWorkspaceAuditStateAcceptsJournalDbOnly() {
        createJournalDb(JournalValidation.journalDbPath(workspace, frameworkRoot))
        List<Boolean> state = JournalValidation.workspaceAuditState(workspace, frameworkRoot)
        assertFalse(state[0])
        assertTrue(state[1])
    }

    void testPreflightWorkspaceReadyWithJournalDbOnly() {
        createJournalDb(JournalValidation.journalDbPath(workspace, frameworkRoot))
        FrameworkPaths paths = new FrameworkPaths(workspace)
        ResultSet results = new ResultSet()
        PreflightCommands.checkWorkspace(results, paths, frameworkRoot)
        assertEquals(1, results.results.size())
        assertEquals(Status.READY, results.results[0].status)
        assertTrue(results.results[0].message.contains('journal.db'))
    }

    void testPreflightWorkspaceReadyWithPromptLogOnly() {
        new File(workspace, 'prompt.log').createNewFile()
        FrameworkPaths paths = new FrameworkPaths(workspace)
        ResultSet results = new ResultSet()
        PreflightCommands.checkWorkspace(results, paths, frameworkRoot)
        assertEquals(Status.READY, results.results[0].status)
        assertTrue(results.results[0].message.contains('legacy prompt.log'))
    }

    void testPreflightWorkspaceDegradedWithoutAuditSource() {
        FrameworkPaths paths = new FrameworkPaths(workspace)
        ResultSet results = new ResultSet()
        PreflightCommands.checkWorkspace(results, paths, frameworkRoot)
        assertEquals(Status.DEGRADED, results.results[0].status)
        assertTrue(results.results[0].message.contains('prompt.log or repos/ai-memory-ingester/data/journal.db'))
    }

    void testWriterContractSharedRulesPresent() {
        Map contract = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/journal-writer-contract.json'), [:])
        assertEquals(1, contract.schema_version)
        assertEquals('json_stdin', contract.transport)
        assertTrue(((List) contract.required_fields).contains('user_text'))
        assertTrue(((List) contract.required_fields).contains('assistant_text'))
        assertFalse(((Map) contract.shadow_period).prompt_log_on_journal_failure)
        assertTrue(((Map) contract.shadow_period).rollback_accepts_either)
    }

    void testWriterContractMatchesAiVaultReferenceWhenPresent() {
        File vaultContract = new File(workspace.parentFile.parentFile, 'ai-vault/skills/worklog-chat-memory/references/journal-writer-contract.json')
        if (!vaultContract.isFile()) {
            File sibling = new File(frameworkRoot.parentFile, 'ai-vault/skills/worklog-chat-memory/references/journal-writer-contract.json')
            vaultContract = sibling.isFile() ? sibling : vaultContract
        }
        if (!vaultContract.isFile()) {
            return
        }
        Map frameworkContract = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/journal-writer-contract.json'), [:])
        Map vault = (Map) JsonFiles.read(vaultContract, [:])
        assertEquals(vault, frameworkContract)
    }
}
