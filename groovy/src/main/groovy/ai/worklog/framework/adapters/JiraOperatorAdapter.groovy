package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Status
import groovy.json.JsonSlurper

import java.time.Instant
import java.time.LocalDate

class JiraOperatorAdapter {
    final FrameworkPaths paths
    final ReadOnlyHttp http
    final Map rules

    JiraOperatorAdapter(FrameworkPaths paths, ReadOnlyHttp http, Map rules) {
        this.paths = paths
        this.http = http
        this.rules = rules
    }

    Map whoami() {
        Map credentials = credentials()
        if (!credentials) {
            return blocked('whoami', 'Jira credentials unavailable')
        }
        Map result = get('/rest/api/2/myself', credentials)
        if (result.failure) {
            return failure('whoami', result)
        }
        Map user = (Map) result.data
        report('whoami', Status.READY, [[
            username: user.name?.toString() ?: user.key?.toString() ?: '',
            display_name: user.displayName?.toString() ?: '',
            email: user.emailAddress?.toString() ?: '',
            active: user.active == null ? true : user.active as boolean
        ]])
    }

    Map ticket(String ticketKey) {
        Map credentials = credentials()
        if (!credentials) {
            return blocked('ticket', 'Jira credentials unavailable', [ticket_key: ticketKey])
        }
        Map issueResult = get("${apiPath('issue')}/${ticketKey}", credentials)
        if (issueResult.failure) {
            return failure('ticket', issueResult, [ticket_key: ticketKey])
        }
        Map issue = normalizeIssue((Map) issueResult.data)
        Map worklogs = fetchWorklogs(ticketKey, credentials)
        Map comments = fetchComments(ticketKey, credentials)
        Map assignment = fetchAssignment(ticketKey, credentials)
        issue.worklogs = worklogs.items
        if (!comments.error) {
            issue.comments = comments.items
        }
        issue.assigned_date = assignment.date ?: ''
        issue.assignment = assignment.assignment ?: [:]
        List<String> failures = []
        if (worklogs.error) {
            failures << worklogs.error.toString()
        }
        if (comments.error) {
            failures << comments.error.toString()
        }
        if (assignment.error) {
            failures << assignment.error.toString()
        }
        report(
            'ticket',
            failures ? Status.DEGRADED : Status.READY,
            [issue],
            [
                ticket_key: ticketKey,
                message: failures.join('; '),
                truncated: (worklogs.truncated || comments.truncated) as boolean
            ]
        )
    }

    Map summary(int limit) {
        Map credentials = credentials()
        if (!credentials) {
            return blocked('summary', 'Jira credentials unavailable')
        }
        Map searched = search(
            rules.summary_jql.toString(),
            (List<String>) rules.summary_fields,
            limit,
            credentials
        )
        if (searched.failure) {
            return failure('summary', searched)
        }
        Map statusIds = (Map) (((Map) rules.board).status_ids ?: [:])
        List<Map> items = ((List<Map>) searched.items).collect { Map raw ->
            Map item = normalizeSummaryIssue(raw)
            item.board_column = resolveBoardColumn(
                item.status_id.toString(),
                item.status_category.toString(),
                statusIds
            )
            item
        }
        Set<String> assignmentColumns = [
            'W trakcie', 'Zablokowane', 'Do zrobienia', 'Testy'
        ] as Set
        LocalDate cutoff = LocalDate.now().minusDays(30)
        int assignmentFailures = 0
        items.findAll {
            assignmentColumns.contains(it.board_column) ||
                (it.board_column == 'Gotowe' && parseDate(it.resolution_date?.toString())?.isAfter(cutoff.minusDays(1)))
        }.each { Map item ->
            Map assignment = fetchAssignment(item.key.toString(), credentials)
            item.assigned_date = assignment.date ?: ''
            if (assignment.error) {
                assignmentFailures++
            }
        }
        Map grouped = items.groupBy { it.board_column }
        Map totals = [
            tickets: items.size(),
            time_spent_seconds: items.sum { (it.time_spent_seconds ?: 0) as long } ?: 0L,
            columns: ((List) rules.board.columns).collectEntries {
                [(it.toString()): ((List) (grouped[it.toString()] ?: [])).size()]
            }
        ]
        report(
            'summary',
            assignmentFailures ? Status.DEGRADED : Status.READY,
            items,
            [
                totals: totals,
                truncated: searched.truncated as boolean,
                message: assignmentFailures ?
                    "${assignmentFailures} assignment histories unavailable" :
                    ''
            ]
        )
    }

    Map rejected(int limit) {
        Map credentials = credentials()
        if (!credentials) {
            return blocked('rejected', 'Jira credentials unavailable')
        }
        Map board = (Map) rules.board
        String rejectedColumn = board.rejected_column.toString()
        List<String> ids = ((Map) board.status_ids).findAll {
            it.value?.toString() == rejectedColumn
        }.keySet()*.toString().sort()
        if (!ids) {
            return blocked('rejected', "No status IDs configured for '${rejectedColumn}'")
        }
        String jql = "assignee = currentUser() AND status in (${ids.join(', ')}) ORDER BY updated DESC"
        Map searched = search(jql, (List<String>) rules.rejected_fields, limit, credentials)
        if (searched.failure) {
            return failure('rejected', searched)
        }
        report(
            'rejected',
            Status.READY,
            ((List<Map>) searched.items).collect { normalizeSummaryIssue(it) },
            [truncated: searched.truncated as boolean]
        )
    }

    Map reporter(String displayName, int limit) {
        Map credentials = credentials()
        if (!credentials) {
            return blocked('reporter', 'Jira credentials unavailable', [reporter: displayName])
        }
        String escaped = displayName.replace('\\', '\\\\').replace('"', '\\"')
        String jql = "reporter = \"${escaped}\" ORDER BY created DESC"
        Map searched = search(jql, (List<String>) rules.reporter_fields, limit, credentials)
        if (searched.failure) {
            return failure('reporter', searched, [reporter: displayName])
        }
        report(
            'reporter',
            Status.READY,
            ((List<Map>) searched.items).collect { normalizeSummaryIssue(it) },
            [reporter: displayName, truncated: searched.truncated as boolean]
        )
    }

    Map credentials() {
        File properties = new File(paths.serviceDir('jira'), 'jira.properties')
        if (!properties.isFile()) {
            return [:]
        }
        Map values = PropertiesSupport.load(properties)
        Map loaded = [
            url: values['jira.url']?.toString(),
            token: values['jira.token']?.toString()
        ]
        loaded.url && loaded.token ? loaded : [:]
    }

    Map get(String path, Map credentials) {
        String url = "${credentials.url.replaceAll(/\/+$/, '')}${path}"
        Map response = http.get(url, headers(credentials), timeout())
        parseResponse(response)
    }

    Map headers(Map credentials) {
        [Authorization: "Bearer ${credentials.token}", Accept: 'application/json']
    }

    int timeout() {
        ((Map) rules.timeouts).http_seconds as int
    }

    String apiPath(String name) {
        ((Map) rules.api_paths)[name].toString()
    }

    static Map report(
        String operation,
        Status status,
        List<Map> items = [],
        Map context = [:]
    ) {
        [
            operation: operation,
            fetched_at: Instant.now().toString(),
            status: status,
            items: items
        ] + context
    }

    static Map blocked(String operation, String message, Map context = [:]) {
        report(operation, Status.BLOCKED, [], [message: message] + context)
    }

    static Map failure(String operation, Map result, Map context = [:]) {
        Status status = (result.code in [401, 403]) ? Status.BLOCKED : Status.ERROR
        String message
        if (result.code == 404) {
            return report(operation, status, [], [message: 'Jira item not found', error_kind: 'user'] + context)
        }
        if (result.code) {
            message = "Jira returned HTTP ${result.code}"
        } else {
            message = 'Jira request failed'
        }
        report(operation, status, [], [message: message] + context)
    }

    private Map search(String jql, List<String> fields, int limit, Map credentials) {
        List<Map> items = []
        int startAt = 0
        int pageSize = Math.min(((Map) rules.limits).page_size as int, limit)
        int total = Integer.MAX_VALUE
        while (startAt < total && items.size() < limit) {
            int requested = Math.min(pageSize, limit - items.size())
            String query = [
                jql: jql,
                startAt: startAt.toString(),
                maxResults: requested.toString(),
                fields: fields.join(',')
            ].collect { key, value ->
                "${URLEncoder.encode(key.toString(), 'UTF-8')}=${URLEncoder.encode(value.toString(), 'UTF-8')}"
            }.join('&')
            Map result = get("${apiPath('search')}?${query}", credentials)
            if (result.failure) {
                return result
            }
            Map payload = (Map) result.data
            if (!(payload.issues instanceof List) || !(payload.total instanceof Number)) {
                return [failure: true, code: 0]
            }
            total = payload.total as int
            List<Map> page = (List<Map>) payload.issues
            items.addAll(page)
            if (!page) {
                break
            }
            startAt += page.size()
        }
        [items: items.take(limit), truncated: total > items.size(), total: total]
    }

    private Map fetchWorklogs(String ticketKey, Map credentials) {
        List<Map> items = []
        int startAt = 0
        int pageSize = ((Map) rules.limits).page_size as int
        int maximum = ((Map) rules.limits).worklogs_max as int
        int total = Integer.MAX_VALUE
        while (startAt < total && items.size() < maximum) {
            Map result = get(
                "${apiPath('issue')}/${ticketKey}/worklog?startAt=${startAt}&maxResults=${pageSize}",
                credentials
            )
            if (result.failure) {
                return [items: items, error: 'Failed to fetch Jira worklogs', truncated: false]
            }
            Map payload = (Map) result.data
            if (!(payload.worklogs instanceof List) || !(payload.total instanceof Number)) {
                return [items: items, error: 'Malformed Jira worklog response', truncated: false]
            }
            total = payload.total as int
            List<Map> page = (List<Map>) payload.worklogs
            items.addAll(page.collect { Map worklog ->
                [
                    author: worklog.author?.displayName?.toString() ?: '',
                    started: worklog.started?.toString() ?: '',
                    time_spent: worklog.timeSpent?.toString() ?: '',
                    time_spent_seconds: (worklog.timeSpentSeconds ?: 0) as long,
                    comment: worklog.comment?.toString() ?: ''
                ]
            })
            if (!page) {
                break
            }
            startAt += page.size()
        }
        [items: items.take(maximum).sort { it.started }, truncated: total > items.size()]
    }

    private Map fetchComments(String ticketKey, Map credentials) {
        List<Map> items = []
        int startAt = 0
        int pageSize = ((Map) rules.limits).page_size as int
        int maximum = ((Map) rules.limits).comments_max as int
        int total = Integer.MAX_VALUE
        while (startAt < total && items.size() < maximum) {
            Map result = get(
                "${apiPath('issue')}/${ticketKey}/comment?startAt=${startAt}&maxResults=${pageSize}",
                credentials
            )
            if (result.failure) {
                return [items: items, error: 'Failed to fetch Jira comments', truncated: false]
            }
            Map payload = (Map) result.data
            if (!(payload.comments instanceof List) || !(payload.total instanceof Number)) {
                return [items: items, error: 'Malformed Jira comment response', truncated: false]
            }
            total = payload.total as int
            List<Map> page = (List<Map>) payload.comments
            items.addAll(page.collect { Map comment ->
                [
                    author: comment.author?.displayName?.toString() ?: '',
                    body: comment.body?.toString() ?: '',
                    created: comment.created?.toString() ?: ''
                ]
            })
            if (!page) {
                break
            }
            startAt += page.size()
        }
        [items: items.take(maximum).sort { it.created }, truncated: total > items.size()]
    }

    private Map fetchAssignment(String ticketKey, Map credentials) {
        Map result = get(
            "${apiPath('issue')}/${ticketKey}?expand=changelog&fields=assignee",
            credentials
        )
        if (result.failure) {
            return [error: 'Failed to fetch assignment history']
        }
        List<Map> histories = (List<Map>) (((Map) result.data).changelog?.histories ?: [])
        Map assignment
        histories.each { Map history ->
            ((List<Map>) (history.items ?: [])).findAll { it.field == 'assignee' }.each {
                assignment = [
                    date: history.created?.toString() ?: '',
                    by: history.author?.displayName?.toString() ?: '',
                    from: it.fromString?.toString() ?: '',
                    to: it['toString']?.toString() ?: ''
                ]
            }
        }
        [
            date: assignment?.date ? assignment.date.toString().take(10) : '',
            assignment: assignment ?: [:]
        ]
    }

    private static Map parseResponse(Map response) {
        int code = (response.code ?: 0) as int
        if (code == 0 || code >= 400) {
            return [failure: true, code: code]
        }
        try {
            Object data = response.body?.trim() ?
                new JsonSlurper().parseText(response.body.toString()) :
                null
            data instanceof Map || data instanceof List ?
                [failure: false, code: code, data: data] :
                [failure: true, code: 0]
        } catch (Exception ignored) {
            [failure: true, code: 0]
        }
    }

    private static Map normalizeIssue(Map issue) {
        Map fields = issue.fields instanceof Map ? (Map) issue.fields : [:]
        Map status = fields.status instanceof Map ? (Map) fields.status : [:]
        Map category = status.statusCategory instanceof Map ? (Map) status.statusCategory : [:]
        Map parent = fields.parent instanceof Map ? (Map) fields.parent : [:]
        List<Map> comments = (List<Map>) (fields.comment?.comments ?: [])
        List<Map> links = (List<Map>) (fields.issuelinks ?: [])
        [
            key: issue.key?.toString() ?: '',
            summary: fields.summary?.toString() ?: '',
            status: status.name?.toString() ?: '',
            status_category: category.name?.toString() ?: category.key?.toString() ?: '',
            type: fields.issuetype?.name?.toString() ?: '',
            priority: fields.priority?.name?.toString() ?: '',
            assignee: fields.assignee?.displayName?.toString() ?: 'Unassigned',
            reporter: fields.reporter?.displayName?.toString() ?: '',
            creator: fields.creator?.displayName?.toString() ?: '',
            project_key: fields.project?.key?.toString() ?: '',
            project_name: fields.project?.name?.toString() ?: '',
            parent_key: parent.key?.toString() ?: '',
            parent_summary: parent.fields?.summary?.toString() ?: '',
            components: ((List) (fields.components ?: [])).collect { it.name?.toString() ?: '' },
            labels: ((List) (fields.labels ?: []))*.toString(),
            description: fields.description?.toString() ?: '',
            created: fields.created?.toString() ?: '',
            updated: fields.updated?.toString() ?: '',
            resolution: fields.resolution?.name?.toString() ?: '',
            resolution_date: fields.resolutiondate?.toString() ?: '',
            time_spent: fields.timetracking?.timeSpent?.toString() ?: '',
            time_spent_seconds: (fields.timespent ?: 0) as long,
            links: links.collect { Map link ->
                Map linked = (Map) (link.inwardIssue ?: link.outwardIssue ?: [:])
                [
                    key: linked.key?.toString() ?: '',
                    summary: linked.fields?.summary?.toString() ?: '',
                    relationship: link.inwardIssue ?
                        link.type?.inward?.toString() :
                        link.type?.outward?.toString()
                ]
            },
            comments: comments.collect { Map comment ->
                [
                    author: comment.author?.displayName?.toString() ?: '',
                    body: comment.body?.toString() ?: '',
                    created: comment.created?.toString() ?: ''
                ]
            }
        ]
    }

    private static Map normalizeSummaryIssue(Map issue) {
        Map fields = issue.fields instanceof Map ? (Map) issue.fields : [:]
        Map status = fields.status instanceof Map ? (Map) fields.status : [:]
        Map category = status.statusCategory instanceof Map ? (Map) status.statusCategory : [:]
        [
            key: issue.key?.toString() ?: '',
            summary: fields.summary?.toString() ?: '',
            status: status.name?.toString() ?: '',
            status_id: status.id?.toString() ?: '',
            status_category: category.name?.toString() ?: category.key?.toString() ?: '',
            type: fields.issuetype?.name?.toString() ?: '',
            priority: fields.priority?.name?.toString() ?: '',
            project: fields.project?.key?.toString() ?: '',
            created: fields.created?.toString() ?: '',
            updated: fields.updated?.toString() ?: '',
            resolution_date: fields.resolutiondate?.toString() ?: '',
            time_spent: fields.timetracking?.timeSpent?.toString() ?: '',
            time_spent_seconds: (fields.timespent ?: 0) as long
        ]
    }

    private static String resolveBoardColumn(String statusId, String category, Map statusIds) {
        if (statusIds[statusId]) {
            return statusIds[statusId].toString()
        }
        switch (category) {
            case 'Do zrobienia':
            case 'new':
                return 'Do zrobienia'
            case 'W toku':
            case 'indeterminate':
                return 'W trakcie'
            case 'Gotowe':
            case 'done':
                return 'Gotowe'
            default:
                return 'Inne'
        }
    }

    private static LocalDate parseDate(String value) {
        if (!value) {
            return null
        }
        try {
            LocalDate.parse(value.take(10))
        } catch (Exception ignored) {
            null
        }
    }
}
