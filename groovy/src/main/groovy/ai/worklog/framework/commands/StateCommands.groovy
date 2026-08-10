package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.StateManager
import ai.worklog.framework.core.StatePatch
import ai.worklog.framework.core.TicketStateValidator

class StateCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        StateManager manager
    ) {
        if (action == 'list') {
            List<String> tickets = manager.activeTickets()
            println "Ticket states (${tickets.size()}):"
            tickets.each { println "  ${it}" }
            return 0
        }
        if (!args) {
            println 'Usage: ai-worklog state {list|init|show|set|blocker|decision}'
            return 1
        }
        if (action == 'show') {
            String key = args[0]
            if (!manager.paths.ticketStateFile(key).isFile()) {
                println "State not found: ${key}"
                return 1
            }
            render(manager.load(key), frameworkRoot)
            return 0
        }
        if (action == 'init') {
            String key = args[0]
            if (manager.paths.ticketStateFile(key).exists()) {
                println "State already exists: ${key}"
                return 1
            }
            Map state = manager.defaultState(key)
            state.summary = option(args, '--summary') ?: ''
            state.services = optionValues(args, '--service')
            state.governance_mode = option(args, '--governance-mode') ?: 'research'
            return persist(state, args.contains('--apply'), frameworkRoot, manager)
        }

        String operation = action in ['blocker', 'decision'] ? args.remove(0) : null
        if (!args) {
            println 'Missing ticket key'
            return 1
        }
        String key = args.remove(0)
        if (!manager.paths.ticketStateFile(key).isFile()) {
            println "State not found: ${key}. Run 'ai-worklog state init ${key} --apply'."
            return 1
        }
        Map state = manager.load(key)
        try {
            switch (action) {
                case 'set':
                    String path = requiredOption(args, '--path')
                    String value = requiredOption(args, '--value')
                    new StatePatch(frameworkRoot).applyPath(
                        state,
                        path,
                        StatePatch.parseValue(value)
                    )
                    break
                case 'blocker':
                    if (operation == 'add') {
                        StateManager.addBlocker(
                            state,
                            requiredOption(args, '--description'),
                            option(args, '--owner') ?: ''
                        )
                    } else if (operation == 'resolve') {
                        int index = requiredOption(args, '--index') as int
                        if (index < 0 || index >= ((List) state.blockers).size()) {
                            throw new IllegalArgumentException("Blocker index out of range: ${index}")
                        }
                        StateManager.resolveBlocker(state, index)
                    } else {
                        throw new IllegalArgumentException('Unknown blocker operation')
                    }
                    break
                case 'decision':
                    String id = requiredOption(args, '--id')
                    if (operation == 'add') {
                        if (((List) state.decisions).any { it.id == id }) {
                            throw new IllegalArgumentException("Decision already exists: ${id}")
                        }
                        StateManager.addDecision(
                            state,
                            id,
                            requiredOption(args, '--description'),
                            option(args, '--owner') ?: ''
                        )
                    } else if (operation == 'resolve') {
                        if (!((List) state.decisions).any { it.id == id }) {
                            throw new IllegalArgumentException("Decision not found: ${id}")
                        }
                        StateManager.resolveDecision(
                            state,
                            id,
                            requiredOption(args, '--resolution')
                        )
                    } else {
                        throw new IllegalArgumentException('Unknown decision operation')
                    }
                    break
                default:
                    throw new IllegalArgumentException(
                        'Usage: ai-worklog state {list|init|show|set|blocker|decision}'
                    )
            }
        } catch (IllegalArgumentException exception) {
            println exception.message
            return 1
        }
        persist(state, args.contains('--apply'), frameworkRoot, manager)
    }

    static int persist(
        Map state,
        boolean applying,
        File frameworkRoot,
        StateManager manager
    ) {
        List<String> errors = new TicketStateValidator(frameworkRoot).validate(state)
        if (errors) {
            errors.each { println "Validation error: ${it}" }
            return 1
        }
        render(state, frameworkRoot)
        if (!applying) {
            println 'Dry run only. Re-run with --apply to save state.'
            return 0
        }
        try {
            manager.save(state)
        } catch (IOException exception) {
            println "State write failed: ${exception.message}"
            return 2
        }
        println "Saved: ${manager.paths.ticketStateFile(state.ticket_key.toString())}"
        0
    }

    static void render(Map state, File frameworkRoot) {
        println CatalogLoader.pretty(new Redaction(frameworkRoot).redact(state))
    }

    static String option(List<String> args, String name) {
        int index = args.indexOf(name)
        index >= 0 && index + 1 < args.size() ? args[index + 1] : null
    }

    static String requiredOption(List<String> args, String name) {
        String value = option(args, name)
        if (value == null) {
            throw new IllegalArgumentException("Missing value for ${name}")
        }
        value
    }

    static List<String> optionValues(List<String> args, String name) {
        List<String> values = []
        args.eachWithIndex { value, index ->
            if (value == name && index + 1 < args.size()) {
                values << args[index + 1]
            }
        }
        values
    }
}
