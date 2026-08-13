package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

class ArgoCdAdapter {
    final ReadOnlyProcess process
    final Map rules

    ArgoCdAdapter(ReadOnlyProcess process, Map rules) {
        this.process = process
        this.rules = rules
    }

    List<Observation> observe(Map state, List<String> applications) {
        if (!commandAvailable('argocd')) {
            return [new Observation(
                system: 'argocd',
                source: 'argocd',
                status: Status.UNKNOWN,
                message: 'ArgoCD CLI unavailable'
            )]
        }
        if (!applications) {
            String storedState = state.synchronization?.state?.toString() ?: 'unknown'
            if (storedState != 'unknown') {
                return [new Observation(
                    system: 'argocd',
                    source: 'argocd',
                    status: Status.UNKNOWN,
                    message: 'Synchronization tracked but no ArgoCD application configured'
                )]
            }
            return [new Observation(
                system: 'argocd',
                source: 'argocd',
                status: Status.UNKNOWN,
                message: 'No ArgoCD applications configured'
            )]
        }
        List<Observation> observations = []
        applications.each { appName ->
            String source = "argocd:${appName}"
            try {
                ai.worklog.framework.core.FrameworkPaths.validateComponent(appName, 'ArgoCD application name')
            } catch (IllegalArgumentException exception) {
                observations << new Observation(
                    system: 'argocd',
                    source: 'argocd',
                    status: Status.ERROR,
                    message: exception.message
                )
                return
            }
            Map result = process.execute(['argocd', 'app', 'get', appName, '-o', 'json'], timeout())
            if (result.code != 0) {
                observations << new Observation(
                    system: 'argocd',
                    source: source,
                    status: Status.DEGRADED,
                    message: 'ArgoCD query failed',
                    details: [stderr: result.err ?: '']
                )
                return
            }
            try {
                Map payload = (Map) new JsonSlurper().parseText(result.out ?: '{}')
                Map status = payload.status instanceof Map ? (Map) payload.status : [:]
                Map sync = status.sync instanceof Map ? (Map) status.sync : [:]
                Map health = status.health instanceof Map ? (Map) status.health : [:]
                List history = status.history instanceof List ? (List) status.history : []
                String liveRevision = history ? history[0]?.revision?.toString() ?: '' : ''
                observations << new Observation(
                    system: 'argocd',
                    source: source,
                    status: Status.READY,
                    message: 'ArgoCD application fetched',
                    details: [
                        application: appName,
                        sync_status: sync.status?.toString() ?: '',
                        health_status: health.status?.toString() ?: '',
                        revision: sync.revision?.toString() ?: liveRevision
                    ]
                )
            } catch (Exception ignored) {
                observations << new Observation(
                    system: 'argocd',
                    source: source,
                    status: Status.ERROR,
                    message: 'Malformed ArgoCD response'
                )
            }
        }
        observations
    }

    private static boolean commandAvailable(String binary) {
        try {
            boolean isWindows = System.getProperty("os.name")?.toLowerCase()?.contains("win")
            List<String> cmd = isWindows ? ['where.exe', binary] : ['which', binary]
            ReadOnlyProcess.validateArgv(cmd)
            new ProcessBuilder(cmd).start().waitFor() == 0
        } catch (Exception ignored) {
            false
        }
    }

    private int timeout() {
        ((Map) (rules.timeouts ?: [:])).process_seconds ?: 15
    }
}
