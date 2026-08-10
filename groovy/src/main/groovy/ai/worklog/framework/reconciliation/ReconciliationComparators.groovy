package ai.worklog.framework.reconciliation

import ai.worklog.framework.core.Status

class ReconciliationComparators {
    static List<Contradiction> compareState(
        Map state,
        List<Observation> observations,
        Map rules
    ) {
        List<Contradiction> contradictions = []
        Map<String, List<Observation>> bySystem = [:].withDefault { [] }
        observations.each { bySystem[it.system] << it }

        Observation jira = bySystem.jira.find { it.status == Status.READY && it.details }
        if (jira) {
            String storedSummary = state.summary?.toString() ?: ''
            String observedSummary = jira.details.summary?.toString() ?: ''
            if (storedSummary && observedSummary && storedSummary != observedSummary) {
                add(contradictions, rules, 'jira_summary_mismatch', 'jira', severity(rules, 'jira_summary_mismatch'),
                    storedSummary, observedSummary, 'jira', 'Stored summary differs from Jira')
            }
            String category = jira.details.status_category?.toString() ?: ''
            String implState = state.implementation?.state?.toString() ?: ''
            List completeCategories = (List) (rules.jira?.complete_categories ?: ['done'])
            if (completeCategories.contains(category) && implState != 'complete') {
                add(contradictions, rules, 'jira_complete_impl_incomplete', 'jira',
                    severity(rules, 'jira_complete_impl_incomplete'),
                    'implementation.state=complete', "implementation.state=${implState}", 'jira',
                    'Jira is complete but implementation is incomplete')
            }
            Map closeout = state.closeout instanceof Map ? (Map) state.closeout : [:]
            List activeCategories = (List) (rules.jira?.active_categories ?: ['indeterminate', 'new'])
            if (closeout.implementation_complete && activeCategories.contains(category)) {
                add(contradictions, rules, 'closeout_complete_jira_active', 'jira',
                    severity(rules, 'closeout_complete_jira_active'),
                    'Jira complete', jira.details.status?.toString() ?: '', 'jira',
                    'Closeout marked complete while Jira remains active')
            }
        }

        boolean storedUncommitted = state.implementation?.uncommitted == true
        bySystem.git.each { observation ->
            if (!observation.details) {
                return
            }
            if (observation.details.present == false) {
                add(contradictions, rules, 'repo_missing', 'git', severity(rules, 'repo_missing'),
                    'repository present', 'missing', observation.source,
                    'Recorded repository is not cloned')
                return
            }
            boolean dirty = observation.details.dirty == true
            if (dirty != storedUncommitted) {
                add(contradictions, rules, 'uncommitted_mismatch', 'git', severity(rules, 'uncommitted_mismatch'),
                    storedUncommitted.toString(), dirty.toString(), observation.source,
                    'Uncommitted flag disagrees with working tree')
            }
            int ahead = (observation.details.ahead_of_upstream ?: 0) as int
            if (ahead > 0) {
                add(contradictions, rules, 'unpushed_commits', 'git', severity(rules, 'unpushed_commits'),
                    '0 commits ahead', "${ahead} commits ahead", observation.source,
                    'Local branch has unpushed commits')
            }
        }

        List recordedPrs = (List) (state.pull_requests ?: [])
        List externalPrs = []
        bySystem.github.each { observation ->
            if (observation.details?.pull_requests) {
                externalPrs.addAll((List) observation.details.pull_requests)
            }
        }

        recordedPrs.each { recorded ->
            def number = recorded.number
            String repo = recorded.repo?.toString() ?: ''
            String recordedUrl = recorded.url?.toString() ?: ''
            Map match = externalPrs.find { item ->
                item.number == number &&
                    ((recordedUrl && item.url == recordedUrl) ||
                        (!recordedUrl && (!repo || (item.url ?: '').contains(repo)))
                    )
            }
            if (match == null && number != null) {
                add(contradictions, rules, 'pr_missing_external', 'github', severity(rules, 'pr_missing_external'),
                    "PR #${number}", 'missing', "github:${repo ?: 'unknown'}",
                    'Recorded pull request not found in GitHub')
            } else if (match != null) {
                String recordedState = recorded.state?.toString()?.toLowerCase() ?: ''
                String observedState = match.state?.toString()?.toLowerCase() ?: ''
                if (match.isDraft && recordedState != 'draft') {
                    observedState = 'draft'
                }
                if (recordedState && observedState && recordedState != observedState) {
                    add(contradictions, rules, 'pr_state_mismatch', 'github', severity(rules, 'pr_state_mismatch'),
                        recordedState, observedState, "github:${repo ?: match.url ?: 'unknown'}",
                        'Recorded pull request state differs from GitHub')
                }
                String observedUrl = match.url?.toString() ?: ''
                if (recordedUrl && observedUrl && recordedUrl != observedUrl) {
                    add(contradictions, rules, 'pr_url_mismatch', 'github', severity(rules, 'pr_url_mismatch'),
                        recordedUrl, observedUrl, "github:${repo ?: observedUrl}",
                        'Recorded pull request URL differs from GitHub')
                }
            }
        }

        externalPrs.each { external ->
            def number = external.number
            if (number == null) {
                return
            }
            String externalUrl = external.url?.toString() ?: ''
            boolean known = recordedPrs.any { item ->
                item.number == number && (!item.url || item.url == externalUrl)
            }
            if (!known) {
                add(contradictions, rules, 'pr_discovered_not_recorded', 'github',
                    severity(rules, 'pr_discovered_not_recorded'),
                    'recorded in ticket state', "PR #${number}", "github:${externalUrl ?: 'unknown'}",
                    'GitHub pull request missing from structured state')
            }
        }

        bySystem.jenkins.each { observation ->
            if (observation.status == Status.READY) {
                return
            }
            Map details = observation.details instanceof Map ? (Map) observation.details : [:]
            if (!details.job) {
                return
            }
            String expected = "${details.controller ?: ''}/${details.job}"
            add(contradictions, rules, 'jenkins_job_unresolved', 'jenkins', severity(rules, 'jenkins_job_unresolved'),
                expected, observation.message ?: 'unresolved', observation.source,
                'Configured Jenkins job could not be resolved')
        }

        ((List) (state.builds ?: [])).each { recorded ->
            String job = recorded.job?.toString() ?: ''
            def buildNumber = recorded.number
            Observation observation = bySystem.jenkins.find { (it.details ?: [:]).job == job }
            String source = observation?.source ?: "jenkins:${job}"
            if (!observation?.details) {
                return
            }
            List recent = (List) (observation.details.recent_builds ?: [])
            if (observation.details.last_build && !recent) {
                recent = [observation.details.last_build]
            }
            Map match = recent.find { it.number == buildNumber }
            if (buildNumber != null && match == null) {
                add(contradictions, rules, 'build_missing', 'jenkins', severity(rules, 'build_missing'),
                    "build #${buildNumber}", 'missing', source,
                    'Recorded build not found in Jenkins')
            } else if (match != null) {
                String recordedResult = recorded.result?.toString()?.toUpperCase() ?: ''
                String observedResult = match.result?.toString()?.toUpperCase() ?: ''
                if (recordedResult && observedResult && recordedResult != observedResult) {
                    add(contradictions, rules, 'build_result_mismatch', 'jenkins',
                        severity(rules, 'build_result_mismatch'),
                        recordedResult, observedResult, source,
                        'Recorded build result differs from Jenkins')
                }
            }
        }

        List mergedPrs = recordedPrs.findAll { (it.state?.toString()?.toLowerCase() ?: '') == 'merged' }
        List builds = (List) (state.builds ?: [])
        if (mergedPrs && !builds) {
            add(contradictions, rules, 'merged_pr_no_build', 'jenkins', severity(rules, 'merged_pr_no_build'),
                'build recorded', 'none', 'jenkins',
                'Merged pull request without recorded build')
        }

        Map syncState = state.synchronization instanceof Map ? (Map) state.synchronization : [:]
        String storedSync = syncState.state?.toString() ?: ''
        Set syncedStates = ((List) (rules.argocd?.synced_states ?: ['Synced', 'synced']))
            .collect { it.toString() } as Set
        Set outOfSyncStates = ((List) (rules.argocd?.out_of_sync_states ?: ['OutOfSync', 'out_of_sync']))
            .collect { it.toString() } as Set
        bySystem.argocd.each { observation ->
            if (!observation.details) {
                return
            }
            String observedSync = observation.details.sync_status?.toString() ?: ''
            if (storedSync == 'synced' && outOfSyncStates.contains(observedSync)) {
                add(contradictions, rules, 'sync_state_mismatch', 'argocd', severity(rules, 'sync_state_mismatch'),
                    storedSync, observedSync, observation.source,
                    'Stored synchronization state differs from ArgoCD')
            } else if (storedSync == 'out_of_sync' && syncedStates.contains(observedSync)) {
                add(contradictions, rules, 'sync_state_mismatch', 'argocd', severity(rules, 'sync_state_mismatch'),
                    storedSync, observedSync, observation.source,
                    'Stored synchronization state differs from ArgoCD')
            }
            String expectedRevision = syncState.expected_revision?.toString() ?: ''
            String observedRevision = observation.details.revision?.toString() ?: ''
            if (expectedRevision && observedRevision && expectedRevision != observedRevision) {
                add(contradictions, rules, 'revision_mismatch', 'argocd', severity(rules, 'revision_mismatch'),
                    expectedRevision, observedRevision, observation.source,
                    'Expected revision differs from live revision')
            }
        }

        if (state.closeout?.deployment_complete) {
            Observation mismatch = bySystem.argocd.find { observation ->
                String observedSync = observation.details?.sync_status?.toString() ?: ''
                observedSync && !syncedStates.contains(observedSync)
            }
            if (mismatch) {
                add(contradictions, rules, 'deployment_complete_not_synced', 'argocd',
                    severity(rules, 'deployment_complete_not_synced'),
                    'synced', mismatch.details.sync_status?.toString() ?: '', mismatch.source,
                    'Deployment marked complete while ArgoCD is not synchronized')
            }
        }

        Observation tempo = bySystem.tempo.find { it.status == Status.READY && it.details }
        Map closeout = state.closeout instanceof Map ? (Map) state.closeout : [:]
        if (tempo) {
            int observedSeconds = (tempo.details.total_seconds ?: 0) as int
            int storedSeconds = (closeout.tempo_seconds ?: 0) as int
            int tolerance = (rules.tempo?.seconds_tolerance ?: 0) as int
            if (closeout.tempo_logged && observedSeconds == 0) {
                add(contradictions, rules, 'tempo_logged_zero', 'tempo', severity(rules, 'tempo_logged_zero'),
                    'time logged', '0 seconds', 'tempo',
                    'Tempo logged flag set but no time observed')
            } else if (closeout.tempo_logged && Math.abs(observedSeconds - storedSeconds) > tolerance) {
                add(contradictions, rules, 'tempo_seconds_mismatch', 'tempo', severity(rules, 'tempo_seconds_mismatch'),
                    storedSeconds.toString(), observedSeconds.toString(), 'tempo',
                    'Stored Tempo seconds differ from observed total')
            } else if (!closeout.tempo_logged && observedSeconds > 0) {
                add(contradictions, rules, 'tempo_unlogged_has_time', 'tempo', severity(rules, 'tempo_unlogged_has_time'),
                    'tempo_logged=false', "${observedSeconds} seconds", 'tempo',
                    'Tempo contains time but local state says it is not logged')
            }
        }

        contradictions
    }

    private static Status severity(Map rules, String code) {
        Map codes = rules.contradiction_codes instanceof Map ? (Map) rules.contradiction_codes : [:]
        String value = codes[code] instanceof Map ? codes[code].severity?.toString() : 'degraded'
        try {
            Status.valueOf(value.toUpperCase())
        } catch (IllegalArgumentException ignored) {
            Status.DEGRADED
        }
    }

    private static void add(
        List<Contradiction> contradictions,
        Map rules,
        String code,
        String system,
        Status severity,
        String expected,
        String observed,
        String source,
        String message
    ) {
        contradictions << new Contradiction(
            code: code,
            system: system,
            severity: severity,
            expected: expected,
            observed: observed,
            source: source,
            message: message
        )
    }
}
