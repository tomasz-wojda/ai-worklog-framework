package ai.worklog.framework.jira

import ai.worklog.framework.adapters.JiraOperatorAdapter
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Status

class JiraWorklogVerifier {
    final FrameworkPaths paths

    JiraWorklogVerifier(FrameworkPaths paths) {
        this.paths = paths
    }

    Map verify(String date, Map tempoReport) {
        if (tempoReport.status != Status.READY) {
            return JiraOperatorAdapter.report(
                'verify',
                (Status) tempoReport.status,
                [],
                [date: date, message: tempoReport.message?.toString() ?: 'Tempo unavailable']
            )
        }
        Map<String, List<String>> local = scan(date)
        Map<String, Map> tempo = [:]
        ((List<Map>) tempoReport.items).each { Map entry ->
            String key = entry.issue_key?.toString() ?: ''
            if (key) {
                if (!tempo[key]) {
                    tempo[key] = [
                        issue_key: key,
                        issue_summary: entry.issue_summary?.toString() ?: '',
                        project: entry.project?.toString() ?: '',
                        time_spent_seconds: 0L,
                        comments: []
                    ]
                }
                tempo[key].time_spent_seconds =
                    (tempo[key].time_spent_seconds as long) +
                    ((entry.time_spent_seconds ?: 0) as long)
                if (entry.comment) {
                    ((List) tempo[key].comments) << entry.comment.toString()
                }
            }
        }
        Set<String> localKeys = local.keySet()
        Set<String> tempoKeys = tempo.keySet()
        List<String> matched = localKeys.intersect(tempoKeys).sort()
        List<String> missingTempo = (localKeys - tempoKeys).sort()
        List<String> missingWorklog = (tempoKeys - localKeys).sort()
        List<Map> items = []
        matched.each { String key ->
            items << [
                issue_key: key,
                result: 'matched',
                files: local[key].sort(),
                time_spent_seconds: tempo[key].time_spent_seconds,
                issue_summary: tempo[key].issue_summary
            ]
        }
        missingTempo.each { String key ->
            items << [
                issue_key: key,
                result: 'missing_from_tempo',
                files: local[key].sort(),
                time_spent_seconds: 0L
            ]
        }
        missingWorklog.each { String key ->
            items << [
                issue_key: key,
                result: 'missing_from_worklog',
                files: [],
                time_spent_seconds: tempo[key].time_spent_seconds,
                issue_summary: tempo[key].issue_summary
            ]
        }
        boolean mismatch = missingTempo || missingWorklog
        JiraOperatorAdapter.report(
            'verify',
            mismatch ? Status.BLOCKED : Status.READY,
            items,
            [
                date: date,
                message: mismatch ? 'Worklog and Tempo entries do not match' : 'Worklog and Tempo entries match',
                totals: [
                    worklog_tickets: localKeys.size(),
                    tempo_tickets: tempoKeys.size(),
                    matched: matched.size(),
                    missing_from_tempo: missingTempo.size(),
                    missing_from_worklog: missingWorklog.size(),
                    tempo_seconds: ((Map) tempoReport.totals).time_spent_seconds ?: 0L
                ]
            ]
        )
    }

    Map<String, List<String>> scan(String date) {
        Map<String, List<String>> tickets = [:]
        if (!paths.worklog.isDirectory()) {
            return tickets
        }
        String prefix = "${date}_"
        def pattern = ~/^\d{4}-\d{2}-\d{2}_([A-Z][A-Z0-9_]*-[1-9][0-9]*)(?:[._-].*)?\.log$/
        paths.worklog.listFiles()
            ?.findAll {
                it.isFile() &&
                    it.name.startsWith(prefix) &&
                    it.name.endsWith('.log') &&
                    !it.name.endsWith('_jira.log') &&
                    !it.name.endsWith('_raw.log')
            }
            ?.sort { it.name }
            ?.each { File file ->
                def matcher = pattern.matcher(file.name)
                if (matcher.matches()) {
                    String key = matcher.group(1)
                    if (!tickets[key]) {
                        tickets[key] = []
                    }
                    tickets[key] << file.name
                }
            }
        tickets
    }
}
