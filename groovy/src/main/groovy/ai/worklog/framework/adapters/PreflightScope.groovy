package ai.worklog.framework.adapters

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.StateManager

class PreflightScope {
    Set<String> checks
    List<String> serviceIds
    Map<String, Map> catalog

    static PreflightScope resolve(
        File frameworkRoot,
        FrameworkPaths paths,
        String ticket,
        List<String> services
    ) {
        CatalogLoader loader = new CatalogLoader(frameworkRoot, paths)
        Map<String, Map> catalog = loader.load()
        if (!ticket && !services) {
            return new PreflightScope(checks: null, serviceIds: [], catalog: catalog)
        }
        Map rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/preflight-checks.json'),
            [:]
        )
        Set<String> serviceIds = [] as Set
        Set<String> explicitChecks = [] as Set
        services.each { service ->
            if (catalog.containsKey(service)) {
                serviceIds << service
            } else {
                List mapped = (List) (((Map) rules.service_checks)[service] ?: [service])
                explicitChecks.addAll(mapped.collect { it.toString() })
            }
        }
        if (ticket) {
            StateManager manager = new StateManager(frameworkRoot, paths)
            File stateFile = paths.ticketStateFile(ticket)
            List stateServices = stateFile.isFile() ?
                (List) (manager.load(ticket).services ?: []) : []
            if (stateServices) {
                serviceIds.addAll(stateServices.collect { it.toString() })
            } else {
                String project = ticket.contains('-') ?
                    ticket.substring(0, ticket.lastIndexOf('-')) : ticket
                serviceIds.addAll(loader.findServices(project))
            }
        }

        Set<String> checks = ((List) rules.global_checks).collect { it.toString() } as Set
        checks.addAll(explicitChecks)
        serviceIds.each { id ->
            Map entry = catalog[id] ?: [:]
            if (entry.jenkins) checks << 'jenkins'
            if (entry.argocd) checks << 'argocd'
            if (entry.monitoring?.newrelic_entity_name) checks << 'newrelic'
            if (entry.environments) checks.addAll(['aws', 'kubectl'])
        }
        checks.addAll(((List) rules.derived_checks).collect { it.toString() })
        new PreflightScope(
            checks: checks,
            serviceIds: serviceIds.sort(),
            catalog: catalog
        )
    }
}
