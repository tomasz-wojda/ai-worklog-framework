package ai.worklog.framework

import ai.worklog.framework.adapters.JiraIssueWriteAdapter
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
                        id: '10001',
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
        assertEquals('10001', item.comments[0].id)
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

    void testCommentDryRunResolvesMentionsAndDoesNotPost() {
        int writes = 0
        List<String> urls = []
        JiraIssueWriteAdapter writer = commentAdapter(
            commentRouter(urls, [(FIRST_USER): activeUser('First Last'), (SECOND_USER): activeUser('Second')], []),
            { method, url, headers, payload, timeout, errorMax, responseMax -> writes++; [code: 201, body: '', error: ''] }
        )
        Map report = writer.comment('PROJ-1', COMMENT_BODY + '\n\n', false)
        assertEquals(Status.READY, report.status)
        assertTrue(report.dry_run)
        assertFalse(report.applied)
        assertEquals(0, writes)
        Map item = report.items[0]
        assertEquals([FIRST_USER, SECOND_USER], item.mentions*.username)
        assertEquals(['First Last', 'Second'], item.mentions*.display_name)
        assertEquals(COMMENT_BODY.size(), item.body_characters)
        assertEquals('Ticket', item.summary)
        assertTrue(item.first_line.startsWith('Hej [~first.last@example.internal]'))
        assertTrue(urls.any { it.endsWith('/rest/api/2/user?username=first.last%40example.internal') })
        String rendered = JiraOperatorReport.fromPayload(report).renderJson(new Redaction(repository))
        assertTrue(rendered.contains('"dry_run": true'))
        assertTrue(rendered.contains('"applied": false'))
    }

    void testCommentRefusesUnresolvedMentionAndDuplicateWithoutPost() {
        int writes = 0
        Closure write = { method, url, headers, payload, timeout, errorMax, responseMax ->
            writes++
            [code: 201, body: '', error: '']
        }
        Map missing = commentAdapter(commentRouter([], [(FIRST_USER): activeUser('First Last')], []), write)
            .comment('PROJ-1', COMMENT_BODY, true)
        assertEquals(Status.ERROR, missing.status)
        assertEquals('user', missing.error_kind)
        assertEquals("Unresolved mentions: ${SECOND_USER}".toString(), missing.message)
        Map inactive = commentAdapter(
            commentRouter([], [(FIRST_USER): activeUser('First Last'), (SECOND_USER): [displayName: 'Second', active: false]], []),
            write
        ).comment('PROJ-1', COMMENT_BODY, true)
        assertTrue(inactive.message.contains(SECOND_USER))
        Map duplicate = commentAdapter(
            commentRouter([], [(FIRST_USER): activeUser('First Last'), (SECOND_USER): activeUser('Second')],
                [[id: '10001', body: COMMENT_BODY.replace('\n', '\r\n') + '\r\n']]),
            write
        ).comment('PROJ-1', COMMENT_BODY, true)
        assertEquals('user', duplicate.error_kind)
        assertEquals('Identical comment already exists: 10001', duplicate.message)
        assertEquals(0, writes)
    }

    void testCommentApplyPostsExactBodyOnceAndVerifies() {
        int writes = 0
        String postedUrl
        Map posted
        int responseLimit = 0
        Closure read = commentRouter([], [(FIRST_USER): activeUser('First Last'), (SECOND_USER): activeUser('Second')], [])
        Map report = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            writes++
            postedUrl = url
            posted = payload
            responseLimit = responseMax
            response([id: '10002', body: payload.body.replace('\n', '\r\n')]) + [code: 201]
        }).comment('PROJ-1', COMMENT_BODY, true)
        assertEquals(Status.READY, report.status)
        assertTrue(report.applied)
        assertFalse(report.dry_run)
        assertEquals(1, writes)
        assertEquals('https://jira.example/rest/api/2/issue/PROJ-1/comment', postedUrl)
        assertEquals([body: COMMENT_BODY], posted)
        assertEquals(65536, responseLimit)
        assertEquals('10002', report.items[0].comment_id)
        assertEquals(
            'https://jira.example/browse/PROJ-1?focusedCommentId=10002#comment-10002',
            report.items[0].comment_url
        )
        Map mismatch = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            response([id: '10003', body: 'other']) + [code: 201]
        }).comment('PROJ-1', COMMENT_BODY, true)
        assertEquals(Status.ERROR, mismatch.status)
        assertEquals('Comment verification failed', mismatch.message)
        assertFalse(mismatch.applied)
        assertEquals('10003', mismatch.items[0].comment_id)
    }

    void testCommentLengthLimitAndAuthFailure() {
        Closure read = commentRouter([], [(FIRST_USER): activeUser('First Last'), (SECOND_USER): activeUser('Second')], [])
        JiraIssueWriteAdapter writer = commentAdapter(read, null)
        Map oversized = writer.comment('PROJ-1', 'x' * 32768, false)
        assertEquals('user', oversized.error_kind)
        assertEquals('Comment must contain 1-32767 characters', oversized.message)
        assertEquals('user', writer.comment('PROJ-1', '  \n', false).error_kind)
        Map unauthorized = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            [code: 401, body: '', error: 'HTTP 401']
        }).comment('PROJ-1', COMMENT_BODY, true)
        assertEquals(Status.BLOCKED, unauthorized.status)
        assertFalse(unauthorized.applied)
        Map notFound = commentAdapter({ method, url, headers, timeout -> [code: 404, body: '', error: ''] }, null)
            .comment('PROJ-404', 'Text', false)
        assertEquals('user', notFound.error_kind)
        assertEquals('Jira item not found', notFound.message)
    }

    void testCommentCliRequiresExactlyOneBodySource() {
        ExitCodes exitCodes = new ExitCodes(repository)
        File body = new File(workspace, 'comment.txt')
        body.setText('Text', 'UTF-8')
        File invalid = new File(workspace, 'invalid.txt')
        invalid.bytes = [0xC3, 0x28] as byte[]
        [
            ['PROJ-1'],
            ['PROJ-1', 'Text', '--file', body.path],
            ['PROJ-1', '--file', new File(workspace, 'missing.txt').path],
            ['PROJ-1', '--file', invalid.path]
        ].each { List<String> args ->
            assertEquals(exitCodes.userError, JiraCommands.run('comment', args, repository, paths))
        }
    }

    void testCommentEditDryRunReportsOldAndNewBodyWithoutPut() {
        int writes = 0
        List<String> urls = []
        Map report = commentAdapter(
            editRouter(urls, mentionUsers(), [id: '10001', body: OLD_BODY, author: OWN_AUTHOR], OWN_AUTHOR),
            { method, url, headers, payload, timeout, errorMax, responseMax -> writes++; [code: 200, body: '', error: ''] }
        ).editComment('PROJ-1', '10001', COMMENT_BODY, false)
        assertEquals(Status.READY, report.status)
        assertEquals('Dry-run only; Jira comment not changed', report.message)
        assertEquals(0, writes)
        Map item = report.items[0]
        assertEquals('10001', item.comment_id)
        assertEquals('https://jira.example/browse/PROJ-1?focusedCommentId=10001#comment-10001', item.comment_url)
        assertEquals(OLD_BODY.size(), item.old_body_characters)
        assertEquals(COMMENT_BODY.size(), item.body_characters)
        assertEquals(64, item.old_body_sha256.size())
        assertFalse(item.old_body_sha256 == item.body_sha256)
        assertTrue(urls.any { it.endsWith('/rest/api/2/issue/PROJ-1/comment/10001') })
        assertTrue(urls.any { it.endsWith('/rest/api/2/myself') })
        String rendered = JiraOperatorReport.fromPayload(report).renderJson(new Redaction(repository))
        assertTrue(rendered.contains('"operation": "comment-edit"'))
        assertTrue(rendered.contains('"dry_run": true'))
        assertTrue(rendered.contains('"applied": false'))
    }

    void testCommentEditRefusesNoOpForeignAuthorAndUnresolvedMention() {
        int writes = 0
        Closure write = { method, url, headers, payload, timeout, errorMax, responseMax ->
            writes++
            [code: 200, body: '', error: '']
        }
        Map foreign = commentAdapter(
            editRouter([], mentionUsers(), [id: '10001', body: OLD_BODY, author: OTHER_AUTHOR], OWN_AUTHOR),
            write
        ).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals('user', foreign.error_kind)
        assertEquals('Comment 10001 belongs to Other User; only own comments can be edited', foreign.message)
        Map unchanged = commentAdapter(
            editRouter([], mentionUsers(), [
                id: '10001',
                body: COMMENT_BODY.replace('\n', '\r\n') + '\r\n',
                author: OWN_AUTHOR
            ], OWN_AUTHOR),
            write
        ).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals('user', unchanged.error_kind)
        assertEquals('Comment 10001 already has this body', unchanged.message)
        Map unresolved = commentAdapter(
            editRouter([], [(FIRST_USER): activeUser('First Last')], [id: '10001', body: OLD_BODY, author: OWN_AUTHOR], OWN_AUTHOR),
            write
        ).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals('user', unresolved.error_kind)
        assertEquals("Unresolved mentions: ${SECOND_USER}".toString(), unresolved.message)
        assertEquals(0, writes)
    }

    void testCommentEditApplyPutsExactBodyOnceAndVerifies() {
        int writes = 0
        String usedMethod
        String putUrl
        Map sent
        int responseLimit = 0
        Closure read = editRouter([], mentionUsers(), [id: '10001', body: OLD_BODY, author: OWN_AUTHOR], OWN_AUTHOR)
        Map report = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            writes++
            usedMethod = method
            putUrl = url
            sent = payload
            responseLimit = responseMax
            response([id: '10001', body: payload.body.replace('\n', '\r\n')])
        }).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals(Status.READY, report.status)
        assertTrue(report.applied)
        assertFalse(report.dry_run)
        assertEquals('Jira comment updated', report.message)
        assertEquals(1, writes)
        assertEquals('PUT', usedMethod)
        assertEquals('https://jira.example/rest/api/2/issue/PROJ-1/comment/10001', putUrl)
        assertEquals([body: COMMENT_BODY], sent)
        assertEquals(65536, responseLimit)
        assertEquals('10001', report.items[0].comment_id)
    }

    void testCommentEditVerificationAndAuthFailures() {
        Closure read = editRouter([], mentionUsers(), [id: '10001', body: OLD_BODY, author: OWN_AUTHOR], OWN_AUTHOR)
        Map wrongBody = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            response([id: '10001', body: 'other'])
        }).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals(Status.ERROR, wrongBody.status)
        assertEquals('Comment verification failed', wrongBody.message)
        assertFalse(wrongBody.applied)
        Map wrongId = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            response([id: '10002', body: payload.body])
        }).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals(Status.ERROR, wrongId.status)
        assertEquals('Comment verification failed', wrongId.message)
        Map forbidden = commentAdapter(read, { method, url, headers, payload, timeout, errorMax, responseMax ->
            [code: 403, body: '', error: 'HTTP 403']
        }).editComment('PROJ-1', '10001', COMMENT_BODY, true)
        assertEquals(Status.BLOCKED, forbidden.status)
        assertFalse(forbidden.applied)
        Map missing = commentAdapter(editRouter([], mentionUsers(), null, OWN_AUTHOR), null)
            .editComment('PROJ-1', '10009', COMMENT_BODY, false)
        assertEquals('user', missing.error_kind)
        assertEquals('Jira item not found', missing.message)
    }

    void testCommentEditCliRequiresBodySourceAndNumericCommentId() {
        ExitCodes exitCodes = new ExitCodes(repository)
        File body = new File(workspace, 'edit.txt')
        body.setText('Text', 'UTF-8')
        PrintStream original = System.err
        ByteArrayOutputStream captured = new ByteArrayOutputStream()
        System.setErr(new PrintStream(captured, true, 'UTF-8'))
        try {
            [
                ['PROJ-1', '10001'],
                ['PROJ-1', '10001', 'Text', '--file', body.path],
                ['PROJ-1', 'abc', '--file', body.path]
            ].each { List<String> args ->
                assertEquals(exitCodes.userError, JiraCommands.run('comment-edit', args, repository, paths))
            }
        } finally {
            System.setErr(original)
        }
        assertTrue(captured.toString('UTF-8').contains('comment-edit requires exactly one of <text...> or --file'))
    }

    private static final String FIRST_USER = 'first.last@example.internal'
    private static final String SECOND_USER = 'second@example.internal'
    private static final String COMMENT_BODY =
        "Hej [~${FIRST_USER}], [~${SECOND_USER}], review please\n|| Repo || PR ||\n| repo | [#1|https://example.internal/1] |"
            .toString()
    private static final String OLD_BODY = 'Old body'
    private static final Map OWN_AUTHOR = [name: 'own.user@example.internal', key: 'JIRAUSER1', displayName: 'Own User']
    private static final Map OTHER_AUTHOR = [name: 'other.user@example.internal', key: 'JIRAUSER2', displayName: 'Other User']

    private static Map mentionUsers() {
        [(FIRST_USER): activeUser('First Last'), (SECOND_USER): activeUser('Second')]
    }

    private static Closure<Map> editRouter(List<String> urls, Map users, Map comment, Map me) {
        return { method, url, headers, timeout ->
            urls << url
            if (url.contains('/rest/api/2/user?username=')) {
                String username = URLDecoder.decode(url.substring(url.indexOf('username=') + 9), 'UTF-8')
                return users[username] ? response(users[username]) : [code: 404, body: '', error: '']
            }
            if (url.endsWith('/rest/api/2/myself')) {
                return response(me)
            }
            if (url.contains('/comment/')) {
                return comment == null ? [code: 404, body: '', error: ''] : response(comment)
            }
            response([key: 'PROJ-1', fields: [summary: 'Ticket']])
        }
    }

    private JiraIssueWriteAdapter commentAdapter(Closure<Map> read, Closure<Map> write) {
        new JiraIssueWriteAdapter(adapter(read), new JsonWriteHttp(requestHandler: write), rules)
    }

    private static Closure<Map> commentRouter(List<String> urls, Map users, List<Map> comments) {
        return { method, url, headers, timeout ->
            urls << url
            if (url.contains('/rest/api/2/user?username=')) {
                String username = URLDecoder.decode(url.substring(url.indexOf('username=') + 9), 'UTF-8')
                return users[username] ? response(users[username]) : [code: 404, body: '', error: '']
            }
            if (url.contains('/comment?')) {
                return response([total: comments.size(), comments: comments])
            }
            response([key: 'PROJ-1', fields: [summary: 'Ticket']])
        }
    }

    private static Map activeUser(String displayName) {
        [displayName: displayName, active: true]
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
