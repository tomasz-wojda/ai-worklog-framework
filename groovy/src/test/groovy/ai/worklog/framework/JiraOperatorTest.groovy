package ai.worklog.framework

import ai.worklog.framework.adapters.JiraOperatorAdapter
import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.TempoOperatorAdapter
import ai.worklog.framework.commands.JiraCommands
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.jira.JiraOperatorReport
import ai.worklog.framework.jira.JiraWorklogVerifier
import groovy.json.JsonOutput
import groovy.test.GroovyTestCase

class JiraOperatorTest extends GroovyTestCase {
    private File repository
    private File workspace
    private FrameworkPaths paths
    private Map rules

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-jira-test')
        new File(workspace, 'integrations/jira').mkdirs()
        new File(workspace, 'worklog/done').mkdirs()
        new File(workspace, 'integrations/jira/jira.properties').text =
            'jira.url=https://jira.example\njira.token=top-secret-token\n'
        paths = new FrameworkPaths(workspace)
        rules = JiraCommands.loadRules(repository, paths)
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testWhoamiReturnsSafeIdentity() {
        JiraOperatorAdapter adapter = adapter { method, url, headers, timeout ->
            assertTrue(headers.Authorization.startsWith('Bearer '))
            response([name: 'user', displayName: 'Test User', active: true])
        }
        Map report = adapter.whoami()
        assertEquals(Status.READY, report.status)
        assertEquals('Test User', report.items[0].display_name)
        assertFalse(JsonOutput.toJson(report).contains('top-secret-token'))
    }

    void testTicketIncludesCommentsLinksWorklogsAndAssignment() {
        JiraOperatorAdapter adapter = adapter { method, url, headers, timeout ->
            if (url.contains('/worklog?')) {
                return response([
                    total: 1,
                    worklogs: [[
                        author: [displayName: 'Worker'],
                        started: '2026-09-09T08:00:00.000+0000',
                        timeSpent: '1h',
                        timeSpentSeconds: 3600,
                        comment: 'Investigation'
                    ]]
                ])
            }
            if (url.contains('/comment?')) {
                return response([
                    total: 1,
                    comments: [[
                        author: [displayName: 'Commenter'],
                        body: 'Decision',
                        created: '2026-09-02'
                    ]]
                ])
            }
            if (url.contains('expand=changelog')) {
                return response([
                    changelog: [histories: [[
                        created: '2026-09-08T10:00:00.000+0000',
                        author: [displayName: 'Lead'],
                        items: [[field: 'assignee', fromString: 'Old', toString: 'New']]
                    ]]]
                ])
            }
            response(issuePayload())
        }
        Map report = adapter.ticket('PROJ-1')
        assertEquals(Status.READY, report.status)
        Map item = report.items[0]
        assertEquals('Parent ticket', item.parent_summary)
        assertEquals('Related ticket', item.links[0].summary)
        assertEquals('Decision', item.comments[0].body)
        assertEquals(3600L, item.worklogs[0].time_spent_seconds)
        assertEquals('2026-09-08', item.assigned_date)
    }

    void testSummaryUsesConfiguredBoardMap() {
        rules.board.status_ids = ['3': 'W trakcie']
        JiraOperatorAdapter adapter = adapter { method, url, headers, timeout ->
            if (url.contains('expand=changelog')) {
                return response([changelog: [histories: []]])
            }
            response([
                total: 1,
                issues: [[
                    key: 'PROJ-1',
                    fields: [
                        summary: 'Work',
                        status: [id: '3', name: 'Doing', statusCategory: [name: 'W toku']],
                        project: [key: 'PROJ'],
                        timespent: 120
                    ]
                ]]
            ])
        }
        Map report = adapter.summary(10)
        assertEquals('W trakcie', report.items[0].board_column)
        assertEquals(1, report.totals.columns['W trakcie'])
        assertEquals(120L, report.totals.time_spent_seconds)
    }

    void testReporterEscapesJqlValue() {
        String requestedUrl
        JiraOperatorAdapter adapter = adapter { method, url, headers, timeout ->
            requestedUrl = url
            response([total: 0, issues: []])
        }
        Map report = adapter.reporter('A "Quoted" User', 20)
        assertEquals(Status.READY, report.status)
        String decoded = URLDecoder.decode(requestedUrl, 'UTF-8')
        assertTrue(decoded.contains('reporter = "A \\"Quoted\\" User"'))
    }

    void testRejectedRequiresConfiguredStatusIds() {
        rules.board.status_ids = [:]
        Map report = adapter { response([:]) }.rejected(20)
        assertEquals(Status.BLOCKED, report.status)
        assertTrue(report.message.contains('No status IDs configured'))
    }

    void testTempoDailyReturnsTotals() {
        TempoOperatorAdapter tempo = tempoAdapter(
            { method, url, headers, timeout ->
                if (url.endsWith('/myself')) {
                    return response([name: 'user', displayName: 'Test User'])
                }
                response([[
                    issue: [key: 'PROJ-1', summary: 'Work'],
                    timeSpentSeconds: 1800,
                    comment: 'Done',
                    dateStarted: '2026-09-09'
                ]])
            },
            null
        )
        Map report = tempo.daily('2026-09-09')
        assertEquals(Status.READY, report.status)
        assertEquals(1800L, report.totals.time_spent_seconds)
        assertEquals('PROJ-1', report.items[0].issue_key)
    }

    void testVerifyUsesWorkspaceWorklogAndExcludesCompanionFiles() {
        new File(workspace, 'worklog/2026-09-09_PROJ-1.log').text = 'work'
        new File(workspace, 'worklog/2026-09-09_PROJ-2_jira.log').text = 'snapshot'
        new File(workspace, 'worklog/done/2026-09-09_PROJ-3.log').text = 'done'
        Map tempo = JiraOperatorAdapter.report(
            'tempo',
            Status.READY,
            [[issue_key: 'PROJ-1', issue_summary: 'Work', time_spent_seconds: 60]],
            [date: '2026-09-09', totals: [time_spent_seconds: 60]]
        )
        Map report = new JiraWorklogVerifier(paths).verify('2026-09-09', tempo)
        assertEquals(Status.READY, report.status)
        assertEquals(['PROJ-1'], report.items*.issue_key)
    }

    void testVerifyBlocksOnMismatch() {
        new File(workspace, 'worklog/2026-09-09_PROJ-1.log').text = 'work'
        Map tempo = JiraOperatorAdapter.report(
            'tempo',
            Status.READY,
            [[issue_key: 'PROJ-2', issue_summary: 'Other', time_spent_seconds: 60]],
            [date: '2026-09-09', totals: [time_spent_seconds: 60]]
        )
        Map report = new JiraWorklogVerifier(paths).verify('2026-09-09', tempo)
        assertEquals(Status.BLOCKED, report.status)
        assertEquals(1, report.totals.missing_from_tempo)
        assertEquals(1, report.totals.missing_from_worklog)
    }

    void testLogTimeDryRunDoesNotPost() {
        int writes = 0
        TempoOperatorAdapter tempo = tempoAdapter(
            null,
            { url, headers, payload, timeout -> writes++; [code: 201, body: '', error: ''] }
        )
        Map report = tempo.logTime('PROJ-1', '2026-09-09', 3600, 'Work', false)
        assertEquals(0, writes)
        assertTrue(report.dry_run)
        assertFalse(report.applied)
    }

    void testLogTimeApplyPostsExactlyOnce() {
        int writes = 0
        Map posted
        TempoOperatorAdapter tempo = tempoAdapter(
            null,
            { url, headers, payload, timeout ->
                writes++
                posted = payload
                [code: 201, body: '{}', error: '']
            }
        )
        Map report = tempo.logTime('PROJ-1', '2026-09-09', 3600, 'Work', true)
        assertEquals(1, writes)
        assertEquals('PROJ-1', posted.issueKey)
        assertEquals(Status.READY, report.status)
        assertTrue(report.applied)
    }

    void testErrorKindDrivesExitCodes() {
        ExitCodes exitCodes = new ExitCodes(repository)
        Map notFound = adapter { method, url, headers, timeout -> [code: 404, body: '', error: ''] }.ticket('PROJ-404')
        assertEquals('user', notFound.error_kind)
        assertEquals(exitCodes.userError, exitCode(notFound, exitCodes))
        Map serverError = adapter { method, url, headers, timeout -> [code: 500, body: '', error: ''] }.ticket('PROJ-500')
        assertNull(serverError.error_kind)
        assertEquals(exitCodes.systemError, exitCode(serverError, exitCodes))
        Map comment = tempoAdapter(null, null).logTime('PROJ-1', '2026-09-09', 60, '', false)
        assertEquals('user', comment.error_kind)
        assertEquals(exitCodes.userError, exitCode(comment, exitCodes))
        Map keywordOnly = JiraOperatorAdapter.report('ticket', Status.ERROR, [], [message: 'Thing not found'])
        assertEquals(exitCodes.systemError, exitCode(keywordOnly, exitCodes))
        assertTrue(
            JiraOperatorReport.fromPayload(notFound).renderJson(new Redaction(repository))
                .contains('"error_kind": "user"')
        )
    }

    private static int exitCode(Map payload, ExitCodes exitCodes) {
        JiraOperatorReport.exitCodeFor(JiraOperatorReport.fromPayload(payload), exitCodes)
    }

    void testAuthenticationFailureIsBlocked() {
        JiraOperatorAdapter adapter = adapter { method, url, headers, timeout ->
            [code: 401, body: '', error: 'unauthorized']
        }
        assertEquals(Status.BLOCKED, adapter.whoami().status)
    }

    void testReportRedactsSensitiveValues() {
        JiraOperatorReport report = JiraOperatorReport.fromPayload(
            JiraOperatorAdapter.report(
                'ticket',
                Status.READY,
                [[
                    token: 'top-secret-token',
                    authorization: 'top-secret-token',
                    author: 'Visible Author',
                    comment: 'Bearer top-secret-token'
                ]],
                [ticket_key: 'PROJ-1']
            )
        )
        String output = report.renderJson(new Redaction(repository))
        assertFalse(output.contains('top-secret-token'))
        assertTrue(output.contains('REDACTED'))
        assertTrue(output.contains('Visible Author'))
    }

    private JiraOperatorAdapter adapter(Closure<Map> handler) {
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: handler)
        new JiraOperatorAdapter(paths, http, rules)
    }

    private TempoOperatorAdapter tempoAdapter(Closure<Map> read, Closure<Map> write) {
        JiraOperatorAdapter jira = adapter(read ?: { method, url, headers, timeout -> response([:]) })
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: write)
        new TempoOperatorAdapter(jira, writeHttp, rules)
    }

    private static Map response(Object payload) {
        [code: 200, body: JsonOutput.toJson(payload), error: '']
    }

    private static Map issuePayload() {
        [
            key: 'PROJ-1',
            fields: [
                summary: 'Ticket',
                status: [name: 'Doing', statusCategory: [name: 'W toku']],
                issuetype: [name: 'Task'],
                priority: [name: 'High'],
                assignee: [displayName: 'Assignee'],
                reporter: [displayName: 'Reporter'],
                creator: [displayName: 'Creator'],
                project: [key: 'PROJ', name: 'Project'],
                parent: [key: 'PROJ-0', fields: [summary: 'Parent ticket']],
                components: [[name: 'DevOps']],
                labels: ['label'],
                description: 'Description',
                created: '2026-09-01',
                updated: '2026-09-09',
                resolution: null,
                resolutiondate: null,
                timetracking: [timeSpent: '1h'],
                timespent: 3600,
                issuelinks: [[
                    type: [outward: 'relates to'],
                    outwardIssue: [key: 'PROJ-2', fields: [summary: 'Related ticket']]
                ]],
                comment: [comments: [[
                    author: [displayName: 'Commenter'],
                    body: 'Decision',
                    created: '2026-09-02'
                ]]]
            ]
        ]
    }
}
