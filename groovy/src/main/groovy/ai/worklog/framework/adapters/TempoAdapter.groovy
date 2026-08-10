package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

import java.time.LocalDate

class TempoAdapter {
    final ReadOnlyHttp http
    final Map rules
    final JiraAdapter jiraAdapter

    TempoAdapter(ReadOnlyHttp http, Map rules, JiraAdapter jiraAdapter) {
        this.http = http
        this.rules = rules
        this.jiraAdapter = jiraAdapter
    }

    List<Observation> observe(String ticketKey, Map state, List<Observation> jiraObservations) {
        Map credentials = jiraAdapter.loadCredentials()
        if (!credentials.url || !credentials.token) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.UNKNOWN,
                message: 'Tempo credentials unavailable'
            )]
        }
        Map user = jiraAdapter.fetchCurrentUser()
        if (!user) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.UNKNOWN,
                message: 'Current Jira user unavailable'
            )]
        }
        LocalDate created = parseCreated(state.created_at?.toString())
        jiraObservations.each { observation ->
            if (observation.details?.created) {
                created = parseCreated(observation.details.created.toString())
            }
        }
        String username = URLEncoder.encode(user.name?.toString() ?: user.key?.toString() ?: '', 'UTF-8')
        String apiPath = rules.tempo?.api_path ?: '/rest/tempo-timesheets/3/worklogs'
        String url = "${credentials.url.replaceAll(/\/+$/, '')}${apiPath}?dateFrom=${created}&dateTo=${LocalDate.now()}&username=${username}"
        Map response = http.get(url, [
            Authorization: "Bearer ${credentials.token}",
            Accept: 'application/json'
        ], timeout())
        if (response.code == 0) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.DEGRADED,
                message: 'Tempo request failed'
            )]
        }
        Object payload
        try {
            payload = response.body?.trim() ? new JsonSlurper().parseText(response.body) : null
        } catch (Exception ignored) {
            payload = null
        }
        if (payload == null) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.ERROR,
                message: 'Malformed Tempo response'
            )]
        }
        if (response.code >= 400) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.DEGRADED,
                message: "Tempo returned HTTP ${response.code}"
            )]
        }
        if (!(payload instanceof List)) {
            return [new Observation(
                system: 'tempo',
                source: 'tempo',
                status: Status.ERROR,
                message: 'Malformed Tempo response'
            )]
        }
        int totalSeconds = 0
        int matched = 0
        payload.each { entry ->
            String issueKey = entry.issue?.key?.toString() ?: ''
            if (issueKey == ticketKey) {
                matched++
                totalSeconds += (entry.timeSpentSeconds ?: 0) as int
            }
        }
        [new Observation(
            system: 'tempo',
            source: 'tempo',
            status: Status.READY,
            message: "${matched} Tempo worklog(s) for ticket",
            details: [
                total_seconds: totalSeconds,
                entry_count: matched,
                username: user.displayName?.toString() ?: user.name?.toString() ?: ''
            ]
        )]
    }

    private static LocalDate parseCreated(String value) {
        if (!value) {
            return LocalDate.now()
        }
        try {
            if (value.contains('T')) {
                return LocalDate.parse(value.substring(0, 10))
            }
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())))
        } catch (Exception ignored) {
            LocalDate.now()
        }
    }

    private int timeout() {
        ((Map) (rules.timeouts ?: [:])).http_seconds ?: 10
    }
}
