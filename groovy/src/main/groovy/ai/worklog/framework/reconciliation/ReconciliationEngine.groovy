package ai.worklog.framework.reconciliation

import ai.worklog.framework.adapters.ArgoCdAdapter
import ai.worklog.framework.adapters.GitAdapter
import ai.worklog.framework.adapters.GitHubAdapter
import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.adapters.JiraAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
import ai.worklog.framework.adapters.TempoAdapter
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.core.Status

import java.time.OffsetDateTime
import java.time.ZoneOffset

class ReconciliationEngine {
    static final List<String> SYSTEMS = ['jira', 'git', 'github', 'jenkins', 'argocd', 'tempo']

    final File frameworkRoot
    final FrameworkPaths paths
    final Map config
    final Map catalog
    final StateManager states
    ReadOnlyHttp http = new ReadOnlyHttp()
    ReadOnlyProcess process

    ReconciliationEngine(
        File frameworkRoot,
        FrameworkPaths paths,
        Map config,
        Map catalog,
        StateManager states
    ) {
        this.frameworkRoot = frameworkRoot
        this.paths = paths
        this.config = config
        this.catalog = catalog
        this.states = states
        this.process = new ReadOnlyProcess(new Redaction(frameworkRoot))
    }

    ReconciliationReport run(String ticketKey, List<String> systems) {
        Map rules = workspaceRules()
        List<String> selected = systems ?: ((List) rules.systems).collect { it.toString() }
        Map state = states.load(ticketKey)
        List<Observation> observations = []
        JiraAdapter jiraAdapter = new JiraAdapter(paths, http, rules)
        List<Observation> jiraObservations = []

        if (selected.contains('jira')) {
            jiraObservations = jiraAdapter.observe(ticketKey)
            observations.addAll(jiraObservations)
        }
        if (selected.contains('git')) {
            observations.addAll(new GitAdapter(paths, process, rules).observe(state, resolveRepositories(state)))
        }
        if (selected.contains('github')) {
            observations.addAll(new GitHubAdapter(process, rules).observe(ticketKey, resolveRepoUrls(state)))
        }
        if (selected.contains('jenkins')) {
            observations.addAll(new JenkinsAdapter(paths, http, rules).observe(state, resolveJobTargets(state, rules)))
        }
        if (selected.contains('argocd')) {
            observations.addAll(new ArgoCdAdapter(process, rules).observe(state, resolveApplications(state)))
        }
        if (selected.contains('tempo')) {
            observations.addAll(new TempoAdapter(http, rules, jiraAdapter).observe(ticketKey, state, jiraObservations))
        }

        List<Contradiction> contradictions = ReconciliationComparators.compareState(state, observations, rules)
        ReconciliationReport report = new ReconciliationReport(
            ticketKey: ticketKey,
            timestamp: OffsetDateTime.now(ZoneOffset.UTC).toString(),
            observations: observations.sort { a, b ->
                int bySystem = a.system <=> b.system
                bySystem != 0 ? bySystem : a.source <=> b.source
            },
            contradictions: contradictions.sort { a, b ->
                int bySystem = a.system <=> b.system
                if (bySystem != 0) {
                    return bySystem
                }
                int bySource = a.source <=> b.source
                bySource != 0 ? bySource : a.code <=> b.code
            }
        )
        report.overallStatus = overallStatus(report)
        report
    }

    Map workspaceRules() {
        Map rules = JsonFiles.deepMerge(defaultRules(), loadRules(frameworkRoot))
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map override = adapters.reconciliation instanceof Map ? (Map) adapters.reconciliation : [:]
        if (override.enabled_systems != null) {
            rules.systems = override.enabled_systems
        }
        if (override.http_timeout_seconds != null) {
            rules.timeouts = ((Map) (rules.timeouts ?: [:])).clone()
            rules.timeouts.http_seconds = override.http_timeout_seconds
        }
        if (override.process_timeout_seconds != null) {
            rules.timeouts = ((Map) (rules.timeouts ?: [:])).clone()
            rules.timeouts.process_seconds = override.process_timeout_seconds
        }
        if (override.repositories_root != null) {
            rules.repositories_root = override.repositories_root
        }
        if (override.jenkins_max_builds != null) {
            rules.jenkins_max_builds = override.jenkins_max_builds
        }
        rules
    }

    static Map loadRules(File frameworkRoot) {
        JsonFiles.read(new File(frameworkRoot, 'shared/reconciliation-rules.json'), [:])
    }

    static Map defaultRules() {
        [
            systems: SYSTEMS,
            timeouts: [http_seconds: 10, process_seconds: 15],
            repositories_root: 'repos',
            jenkins_max_builds: 5,
            jira: [complete_categories: ['done'], active_categories: ['indeterminate', 'new']],
            jenkins: [success_results: ['SUCCESS', 'success'], failure_results: ['FAILURE', 'failure']],
            argocd: [synced_states: ['synced', 'Synced'], out_of_sync_states: ['OutOfSync', 'out_of_sync']],
            tempo: [seconds_tolerance: 0, api_path: '/rest/tempo-timesheets/3/worklogs'],
            contradiction_codes: [
                jira_summary_mismatch: [severity: 'degraded'],
                jira_complete_impl_incomplete: [severity: 'blocked'],
                closeout_complete_jira_active: [severity: 'blocked'],
                repo_missing: [severity: 'blocked'],
                uncommitted_mismatch: [severity: 'degraded'],
                unpushed_commits: [severity: 'degraded'],
                pr_missing_external: [severity: 'blocked'],
                pr_state_mismatch: [severity: 'degraded'],
                pr_discovered_not_recorded: [severity: 'degraded'],
                pr_url_mismatch: [severity: 'degraded'],
                build_missing: [severity: 'blocked'],
                build_result_mismatch: [severity: 'blocked'],
                merged_pr_no_build: [severity: 'degraded'],
                jenkins_job_unresolved: [severity: 'degraded'],
                sync_state_mismatch: [severity: 'blocked'],
                revision_mismatch: [severity: 'blocked'],
                argocd_app_mismatch: [severity: 'degraded'],
                deployment_complete_not_synced: [severity: 'blocked'],
                tempo_logged_zero: [severity: 'blocked'],
                tempo_seconds_mismatch: [severity: 'degraded'],
                tempo_unlogged_has_time: [severity: 'degraded']
            ]
        ]
    }

    static Map mergeRules(Map loaded, Map defaults) {
        JsonFiles.deepMerge(defaults, loaded)
    }

    List<String> resolveRepositories(Map state) {
        Set<String> repos = new LinkedHashSet<>()
        ((List) (state.repositories ?: [])).each { item ->
            if (item instanceof String) {
                repos.add(item)
            } else if (item instanceof Map && item.local_dir) {
                repos.add(item.local_dir.toString())
            }
        }
        ((List) (state.services ?: [])).each { serviceId ->
            ((List) (catalog[serviceId.toString()]?.repositories ?: [])).each { repo ->
                if (repo instanceof Map && repo.local_dir) {
                    repos.add(repo.local_dir.toString())
                }
            }
        }
        repos.sort().toList()
    }

    List<String> resolveRepoUrls(Map state) {
        Set<String> urls = new LinkedHashSet<>()
        ((List) (state.repositories ?: [])).each { item ->
            if (item instanceof Map && item.url) {
                urls.add(item.url.toString())
            }
        }
        ((List) (state.services ?: [])).each { serviceId ->
            ((List) (catalog[serviceId.toString()]?.repositories ?: [])).each { repo ->
                if (repo instanceof Map && repo.url) {
                    urls.add(repo.url.toString())
                }
            }
        }
        urls.sort().toList()
    }

    List<Map> resolveJobTargets(Map state, Map rules) {
        Map controllers = [:]
        File properties = new File(paths.serviceDir('jenkins'), 'jenkins.properties')
        if (properties.isFile()) {
            controllers = ai.worklog.framework.adapters.PropertiesSupport.controllers(properties)
        }
        String defaultController = controllers.size() == 1 ? controllers.keySet().first() : ''
        LinkedHashMap<String, Map> targets = new LinkedHashMap<>()
        ((List) (state.builds ?: [])).each { build ->
            String controller = build.controller?.toString() ?: defaultController
            String job = build.job?.toString() ?: ''
            if (controller && job) {
                targets["${controller}:${job}"] = [controller: controller, job: job]
            }
        }
        ((List) (state.services ?: [])).each { serviceId ->
            Map jenkins = catalog[serviceId.toString()]?.jenkins
            if (jenkins instanceof Map) {
                String controller = jenkins.controller?.toString() ?: ''
                ((List) (jenkins.jobs ?: [])).each { job ->
                    String name = job.name?.toString() ?: ''
                    if (controller && name) {
                        targets["${controller}:${name}"] = [controller: controller, job: name]
                    }
                }
            }
        }
        targets.values().sort { "${it.controller}:${it.job}" }.toList()
    }

    List<String> resolveApplications(Map state) {
        Set<String> apps = new LinkedHashSet<>()
        String stored = state.synchronization?.argocd_app?.toString()
        if (stored) {
            apps.add(stored)
        }
        ((List) (state.services ?: [])).each { serviceId ->
            ((List) (catalog[serviceId.toString()]?.argocd?.applications ?: [])).each { app ->
                if (app instanceof Map && app.name) {
                    apps.add(app.name.toString())
                }
            }
        }
        apps.sort().toList()
    }

    private static Status overallStatus(ReconciliationReport report) {
        List<Status> priority = [Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.UNKNOWN, Status.READY]
        List<Status> statuses = report.observations.collect { it.status }
        statuses.addAll(report.contradictions.collect { it.severity })
        priority.find { level -> statuses.any { it == level } } ?: Status.UNKNOWN
    }
}
