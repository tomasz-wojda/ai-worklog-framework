package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status

class TempoOperatorAdapter {
    final JiraOperatorAdapter jira
    final JsonWriteHttp writeHttp
    final Map rules

    TempoOperatorAdapter(JiraOperatorAdapter jira, JsonWriteHttp writeHttp, Map rules) {
        this.jira = jira
        this.writeHttp = writeHttp
        this.rules = rules
    }

    Map daily(String date) {
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked(
                'tempo',
                'Jira credentials unavailable',
                [date: date]
            )
        }
        Map userResult = jira.get(jira.apiPath('myself'), credentials)
        if (userResult.failure) {
            return JiraOperatorAdapter.failure('tempo', userResult, [date: date])
        }
        Map user = (Map) userResult.data
        String username = user.name?.toString() ?: user.key?.toString() ?: ''
        if (!username) {
            return JiraOperatorAdapter.report(
                'tempo',
                Status.ERROR,
                [],
                [date: date, message: 'Jira user has no username']
            )
        }
        String query = [
            dateFrom: date,
            dateTo: date,
            username: username
        ].collect { key, value ->
            "${URLEncoder.encode(key.toString(), 'UTF-8')}=${URLEncoder.encode(value.toString(), 'UTF-8')}"
        }.join('&')
        Map result = jira.get("${jira.apiPath('tempo_worklogs')}?${query}", credentials)
        if (result.failure) {
            return JiraOperatorAdapter.failure('tempo', result, [date: date])
        }
        if (!(result.data instanceof List)) {
            return JiraOperatorAdapter.report(
                'tempo',
                Status.ERROR,
                [],
                [date: date, message: 'Malformed Tempo response']
            )
        }
        List<Map> items = ((List<Map>) result.data).collect { Map worklog ->
            String issueKey = worklog.issue?.key?.toString() ?: ''
            [
                issue_key: issueKey,
                issue_summary: worklog.issue?.summary?.toString() ?: '',
                project: issueKey.contains('-') ? issueKey.split('-')[0] : '',
                time_spent_seconds: (worklog.timeSpentSeconds ?: 0) as long,
                comment: worklog.comment?.toString() ?: '',
                started: worklog.dateStarted?.toString() ?: ''
            ]
        }.sort { it.started }
        long total = items.sum { (it.time_spent_seconds ?: 0) as long } ?: 0L
        JiraOperatorAdapter.report(
            'tempo',
            Status.READY,
            items,
            [
                date: date,
                totals: [
                    entries: items.size(),
                    tickets: items*.issue_key.toSet().size(),
                    time_spent_seconds: total
                ]
            ]
        )
    }

    Map logTime(
        String ticketKey,
        String date,
        long seconds,
        String comment,
        boolean apply
    ) {
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked(
                'log-time',
                'Jira credentials unavailable',
                [
                    ticket_key: ticketKey,
                    date: date,
                    dry_run: !apply,
                    applied: false
                ]
            )
        }
        int maximum = ((Map) rules.limits).comment_max_characters as int
        if (!comment || comment.size() > maximum) {
            return JiraOperatorAdapter.report(
                'log-time',
                Status.ERROR,
                [],
                [
                    ticket_key: ticketKey,
                    date: date,
                    dry_run: !apply,
                    applied: false,
                    message: "Comment must contain 1-${maximum} characters",
                    error_kind: 'user'
                ]
            )
        }
        Map item = [
            issue_key: ticketKey,
            date_started: date,
            time_spent_seconds: seconds,
            comment: comment
        ]
        if (!apply) {
            return JiraOperatorAdapter.report(
                'log-time',
                Status.READY,
                [item],
                [
                    ticket_key: ticketKey,
                    date: date,
                    dry_run: true,
                    applied: false,
                    message: 'Dry-run only; no Tempo worklog created'
                ]
            )
        }
        String url = "${credentials.url.replaceAll(/\/+$/, '')}${jira.apiPath('tempo_worklogs')}"
        Map response = writeHttp.post(
            url,
            jira.headers(credentials),
            [
                issueKey: ticketKey,
                dateStarted: date,
                timeSpentSeconds: seconds,
                comment: comment
            ],
            jira.timeout(),
            ((Map) rules.limits).error_body_max_characters as int
        )
        int code = (response.code ?: 0) as int
        if (code < 200 || code >= 300) {
            Status status = code in [401, 403] ? Status.BLOCKED : Status.ERROR
            return JiraOperatorAdapter.report(
                'log-time',
                status,
                [item],
                [
                    ticket_key: ticketKey,
                    date: date,
                    dry_run: false,
                    applied: false,
                    message: code ? "Tempo returned HTTP ${code}" : 'Tempo request failed'
                ]
            )
        }
        JiraOperatorAdapter.report(
            'log-time',
            Status.READY,
            [item],
            [
                ticket_key: ticketKey,
                date: date,
                dry_run: false,
                applied: true,
                message: 'Tempo worklog created'
            ]
        )
    }
}
