package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

class JiraAdapter {
    final FrameworkPaths paths
    final ReadOnlyHttp http
    final Map rules

    JiraAdapter(FrameworkPaths paths, ReadOnlyHttp http, Map rules) {
        this.paths = paths
        this.http = http
        this.rules = rules
    }

    List<Observation> observe(String ticketKey) {
        Map credentials = loadCredentials()
        if (!credentials.url || !credentials.token) {
            return [new Observation(
                system: 'jira',
                source: 'jira',
                status: Status.UNKNOWN,
                message: 'Jira credentials unavailable'
            )]
        }
        String url = "${credentials.url.replaceAll(/\/+$/, '')}/rest/api/2/issue/${ticketKey}?fields=summary,status,assignee,created,updated"
        Map response = http.get(url, [
            Authorization: "Bearer ${credentials.token}",
            Accept: 'application/json'
        ], timeout())
        if (response.code == 0) {
            return [new Observation(
                system: 'jira',
                source: 'jira',
                status: Status.DEGRADED,
                message: 'Jira request failed'
            )]
        }
        Object payload
        try {
            payload = response.body?.trim() ? new JsonSlurper().parseText(response.body) : null
        } catch (Exception ignored) {
            payload = null
        }
        if (!(payload instanceof Map)) {
            return [new Observation(
                system: 'jira',
                source: 'jira',
                status: Status.ERROR,
                message: 'Malformed Jira response'
            )]
        }
        if (response.code >= 400) {
            return [new Observation(
                system: 'jira',
                source: 'jira',
                status: Status.DEGRADED,
                message: "Jira returned HTTP ${response.code}"
            )]
        }
        Map fieldsMap = payload.fields instanceof Map ? (Map) payload.fields : [:]
        Map status = fieldsMap.status instanceof Map ? (Map) fieldsMap.status : [:]
        Map category = status.statusCategory instanceof Map ? (Map) status.statusCategory : [:]
        Map assignee = fieldsMap.assignee instanceof Map ? (Map) fieldsMap.assignee : [:]
        [new Observation(
            system: 'jira',
            source: 'jira',
            status: Status.READY,
            message: 'Jira issue fetched',
            details: [
                summary: fieldsMap.summary?.toString() ?: '',
                status: status.name?.toString() ?: '',
                status_category: category.key?.toString() ?: '',
                assignee: assignee.displayName?.toString() ?: assignee.name?.toString() ?: '',
                created: fieldsMap.created?.toString() ?: '',
                updated: fieldsMap.updated?.toString() ?: ''
            ]
        )]
    }

    Map fetchCurrentUser() {
        Map credentials = loadCredentials()
        if (!credentials.url || !credentials.token) {
            return null
        }
        String url = "${credentials.url.replaceAll(/\/+$/, '')}/rest/api/2/myself"
        Map response = http.get(url, [
            Authorization: "Bearer ${credentials.token}",
            Accept: 'application/json'
        ], timeout())
        if (response.code != 200 || !response.body?.trim()) {
            return null
        }
        try {
            (Map) new JsonSlurper().parseText(response.body)
        } catch (Exception ignored) {
            null
        }
    }

    Map loadCredentials() {
        File properties = new File(paths.serviceDir('jira'), 'jira.properties')
        if (!properties.isFile()) {
            return [:]
        }
        Map values = PropertiesSupport.load(properties)
        [
            url: values['jira.url']?.toString(),
            token: values['jira.token']?.toString()
        ]
    }

    private int timeout() {
        ((Map) (rules.timeouts ?: [:])).http_seconds ?: 10
    }
}
