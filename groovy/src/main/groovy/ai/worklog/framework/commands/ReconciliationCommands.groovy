package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.reconciliation.ReconciliationEngine
import ai.worklog.framework.reconciliation.ReconciliationReport

class ReconciliationCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths,
        Map config,
        CatalogLoader catalog,
        StateManager states
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        if (action != 'status') {
            println 'Usage: ai-worklog reconcile status <TICKET-KEY> [--system SYS]... [--json]'
            return exitCodes.userError
        }
        try {
            return runStatus(args, frameworkRoot, paths, config, catalog, states, exitCodes)
        } catch (IllegalArgumentException exception) {
            println exception.message
            return exitCodes.userError
        } catch (Exception exception) {
            println "Reconciliation failed: ${exception.message ?: exception.class.simpleName}"
            return exitCodes.systemError
        }
    }

    private static int runStatus(
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths,
        Map config,
        CatalogLoader catalog,
        StateManager states,
        ExitCodes exitCodes
    ) {
        boolean json
        List<String> systems
        String ticketKey
        (json, systems, ticketKey) = parseStatusArgs(args)
        FrameworkPaths.validateComponent(ticketKey, 'ticket key')
        if (!paths.ticketStateFile(ticketKey).isFile()) {
            println "State not found: ${ticketKey}"
            return exitCodes.userError
        }
        ReconciliationEngine engine = new ReconciliationEngine(
            frameworkRoot,
            paths,
            config,
            catalog.load(),
            states
        )
        Map rules = engine.workspaceRules()
        List<String> selected = systems ?: ((List) rules.systems).collect { it.toString() }
        List<String> invalid = selected.findAll { !ReconciliationEngine.SYSTEMS.contains(it) }
        if (invalid) {
            println "Invalid system(s): ${invalid.sort().join(', ')}"
            return exitCodes.userError
        }
        ReconciliationReport report = engine.run(ticketKey, selected.unique().sort())
        Redaction redaction = new Redaction(frameworkRoot)
        print json ? report.renderJson(redaction) : report.renderHuman()
        if (report.hasErrorObservation()) {
            return exitCodes.systemError
        }
        if (report.hasBlockingContradiction()) {
            return exitCodes.blocked
        }
        exitCodes.success
    }

    private static List parseStatusArgs(List<String> args) {
        List<String> remaining = new ArrayList<>(args)
        boolean json = remaining.remove('--json')
        List<String> systems = []
        int index = 0
        while (index < remaining.size()) {
            if (remaining[index] == '--system') {
                if (index + 1 >= remaining.size() || remaining[index + 1].startsWith('--')) {
                    throw new IllegalArgumentException('Missing value for --system')
                }
                systems << remaining[index + 1]
                remaining.remove(index + 1)
                remaining.remove(index)
                continue
            }
            index++
        }
        String ticketKey = remaining.find { !it.startsWith('--') }
        if (!ticketKey || remaining.any { !it.startsWith('--') && it != ticketKey }) {
            throw new IllegalArgumentException(
                'Usage: ai-worklog reconcile status <TICKET-KEY> [--system SYS]... [--json]'
            )
        }
        [json, systems, ticketKey]
    }
}
