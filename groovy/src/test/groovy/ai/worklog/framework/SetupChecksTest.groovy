package ai.worklog.framework

import ai.worklog.framework.core.CheckResult
import ai.worklog.framework.core.ResultSet
import ai.worklog.framework.core.Status
import ai.worklog.framework.setup.SetupChecks
import groovy.test.GroovyTestCase

class SetupChecksTest extends GroovyTestCase {
    void testPreflightMessageNamesIssuesInsteadOfFirstLine() {
        ResultSet results = results(
            [Status.READY, 'workspace', 'Structure valid'],
            [Status.DEGRADED, 'aws', 'No active session'],
            [Status.DEGRADED, 'servicenow', 'No cookie file']
        )
        assertEquals(
            '2 issue(s): aws: No active session; servicenow: No cookie file (run ai-worklog preflight)',
            SetupChecks.preflightMessage(results)
        )
    }

    void testPreflightMessageSummarizesReadyAndLongLists() {
        assertEquals('All 1 checks ready', SetupChecks.preflightMessage(results([Status.READY, 'git', 'ok'])))
        assertEquals('No checks', SetupChecks.preflightMessage(new ResultSet()))
        ResultSet many = results(
            [Status.DEGRADED, 'a', '1'], [Status.BLOCKED, 'b', '2'],
            [Status.DEGRADED, 'c', '3'], [Status.DEGRADED, 'd', '4']
        )
        assertEquals('4 issue(s): a: 1; b: 2; c: 3; 1 more (run ai-worklog preflight)', SetupChecks.preflightMessage(many))
    }

    private static ResultSet results(List... rows) {
        ResultSet set = new ResultSet()
        rows.each { List row ->
            set.add(new CheckResult(status: (Status) row[0], source: row[1].toString(), message: row[2].toString()))
        }
        set
    }
}
