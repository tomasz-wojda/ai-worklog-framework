package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status

class JiraBoardAdapter {
    static final String MALFORMED = 'Malformed Jira board response'

    final JiraOperatorAdapter jira
    final Map rules

    JiraBoardAdapter(JiraOperatorAdapter jira, Map rules) {
        this.jira = jira
        this.rules = rules
    }

    Map boards(String project, String name, int limit) {
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('boards', 'Jira credentials unavailable')
        }
        int bounded = Math.min(limit, limitValue('boards_max'))
        int pageSize = limitValue('board_page_size')
        List<Map> items = []
        int startAt = 0
        boolean last = false
        while (items.size() < bounded) {
            Map result = jira.get(boardsUrl(startAt, pageSize, project, name), credentials)
            if (result.failure) {
                return JiraOperatorAdapter.failure('boards', result)
            }
            Map page = result.data instanceof Map ? (Map) result.data : [:]
            if (!(page.values instanceof List)) {
                return malformed('boards', [:])
            }
            List<Map> values = (List<Map>) page.values
            items.addAll(values.collect { Map board -> boardSummary(board) })
            last = page.isLast == null ? values.size() < pageSize : page.isLast as boolean
            if (last || !values) {
                break
            }
            startAt += values.size()
        }
        boolean truncated = items.size() > bounded || (items.size() >= bounded && !last)
        JiraOperatorAdapter.report(
            'boards',
            Status.READY,
            items.take(bounded),
            truncated ? [truncated: true] : [:]
        )
    }

    Map board(long boardId) {
        Map context = [board_id: boardId]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('board', 'Jira credentials unavailable', context)
        }
        String base = "${jira.apiPath('agile_board')}/${boardId}"
        Map boardResult = jira.get(base, credentials)
        if (boardResult.failure) {
            return JiraOperatorAdapter.failure('board', boardResult, context)
        }
        Map configResult = jira.get("${base}/configuration", credentials)
        if (configResult.failure) {
            return JiraOperatorAdapter.failure('board', configResult, context)
        }
        Map statusResult = jira.get(jira.apiPath('status'), credentials)
        if (statusResult.failure) {
            return JiraOperatorAdapter.failure('board', statusResult, context)
        }
        Map board = boardResult.data instanceof Map ? (Map) boardResult.data : [:]
        Map configuration = configResult.data instanceof Map ? (Map) configResult.data : [:]
        Map columnConfig = configuration.columnConfig instanceof Map ? (Map) configuration.columnConfig : [:]
        if (!(columnConfig.columns instanceof List) || !(statusResult.data instanceof List)) {
            return malformed('board', context)
        }
        Map<String, Map> statuses = statusIndex((List<Map>) statusResult.data)
        List<Map> sprints = []
        if (board.type?.toString() == 'scrum') {
            Map sprintResult = jira.get("${base}/sprint?state=active", credentials)
            if (sprintResult.failure) {
                return JiraOperatorAdapter.failure('board', sprintResult, context)
            }
            Map sprintPage = sprintResult.data instanceof Map ? (Map) sprintResult.data : [:]
            sprints = sprintPage.values instanceof List ?
                ((List<Map>) sprintPage.values).collect { Map sprint -> sprintItem(sprint) } : []
        }
        List<Map> columns = ((List<Map>) columnConfig.columns).collect { Map column ->
            [
                name: column.name?.toString() ?: '',
                statuses: (column.statuses instanceof List ? (List<Map>) column.statuses : []).collect { Map status ->
                    String id = status.id?.toString() ?: ''
                    [id: id] + (statuses[id] ?: [name: '', category: ''])
                }
            ]
        }
        Map statusIds = [:]
        columns.each { Map column ->
            ((List<Map>) column.statuses).each { Map status -> statusIds[status.id] = column.name }
        }
        Map filter = configuration.filter instanceof Map ? (Map) configuration.filter : [:]
        Map item = boardSummary(board) + [
            filter_id: filter.id?.toString() ?: '',
            columns: columns,
            status_ids: statusIds,
            active_sprints: sprints
        ]
        JiraOperatorAdapter.report('board', Status.READY, [item], context)
    }

    private String boardsUrl(int startAt, int pageSize, String project, String name) {
        StringBuilder url = new StringBuilder("${jira.apiPath('agile_board')}?startAt=${startAt}&maxResults=${pageSize}")
        if (project) {
            url.append('&projectKeyOrId=').append(encode(project))
        }
        if (name) {
            url.append('&name=').append(encode(name))
        }
        url.toString()
    }

    private static Map boardSummary(Map board) {
        Map location = board.location instanceof Map ? (Map) board.location : [:]
        [
            id: board.id == null ? 0L : board.id as long,
            name: board.name?.toString() ?: '',
            type: board.type?.toString() ?: '',
            project_key: location.projectKey?.toString() ?: '',
            project_name: location.projectName?.toString() ?: ''
        ]
    }

    private static Map<String, Map> statusIndex(List<Map> statuses) {
        statuses.collectEntries { Map status ->
            Map category = status.statusCategory instanceof Map ? (Map) status.statusCategory : [:]
            [(status.id?.toString() ?: ''): [
                name: status.name?.toString() ?: '',
                category: category.key?.toString() ?: ''
            ]]
        }
    }

    private static Map sprintItem(Map sprint) {
        [
            id: sprint.id == null ? 0L : sprint.id as long,
            name: sprint.name?.toString() ?: '',
            state: sprint.state?.toString() ?: '',
            start_date: sprint.startDate?.toString() ?: '',
            end_date: sprint.endDate?.toString() ?: '',
            goal: sprint.goal?.toString() ?: ''
        ]
    }

    private static String encode(String value) {
        URLEncoder.encode(value, 'UTF-8').replace('+', '%20')
    }

    private static Map malformed(String operation, Map context) {
        JiraOperatorAdapter.report(operation, Status.ERROR, [], context + [message: MALFORMED])
    }

    private int limitValue(String name) {
        ((Map) rules.limits)[name] as int
    }
}
