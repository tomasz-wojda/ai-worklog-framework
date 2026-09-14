package ai.worklog.framework.core

import java.sql.Connection
import java.sql.DriverManager

class JournalValidation {

    static Map rules(File frameworkRoot) {
        JsonFiles.read(
            new File(frameworkRoot, 'shared/journal-validation.json'),
            [
                journal_db_subpath: 'repos/ai-memory-ingester/data/journal.db',
                legacy_journal_subpath: 'prompt.log',
                required_tables: ['journal_observations'],
                minimum_size_bytes: 1
            ]
        )
    }

    static File journalDbPath(File root, File frameworkRoot) {
        Map config = rules(frameworkRoot)
        new File(root, config.journal_db_subpath.toString())
    }

    static File legacyJournalPath(File root, File frameworkRoot) {
        Map config = rules(frameworkRoot)
        new File(root, config.legacy_journal_subpath.toString())
    }

    static boolean journalDbValid(File path, File frameworkRoot) {
        Map config = rules(frameworkRoot)
        int minimumSize = (config.minimum_size_bytes ?: 1) as int
        List<String> requiredTables = ((List) (config.required_tables ?: ['journal_observations']))*.toString()
        if (!path.isFile() || path.length() < minimumSize) {
            return false
        }
        Connection connection = null
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
            def quickCheck = connection.createStatement().executeQuery('PRAGMA quick_check(1)')
            if (!quickCheck.next() || quickCheck.getString(1) != 'ok') {
                return false
            }
            for (String table : requiredTables) {
                def row = connection.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='${table.replace("'", "''")}'"
                )
                if (!row.next()) {
                    return false
                }
            }
            return true
        } catch (Exception ignored) {
            return false
        } finally {
            connection?.close()
        }
    }

    static List<Boolean> workspaceAuditState(File root, File frameworkRoot) {
        [
            legacyJournalPath(root, frameworkRoot).exists(),
            journalDbValid(journalDbPath(root, frameworkRoot), frameworkRoot)
        ]
    }
}
