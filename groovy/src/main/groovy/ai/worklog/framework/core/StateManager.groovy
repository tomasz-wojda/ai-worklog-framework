package ai.worklog.framework.core

import java.time.Instant

class StateManager {
    final File frameworkRoot
    final FrameworkPaths paths

    StateManager(File frameworkRoot, FrameworkPaths paths) {
        this.frameworkRoot = frameworkRoot
        this.paths = paths
    }

    Map load(String ticketKey) {
        File stateFile = paths.ticketStateFile(ticketKey)
        if (stateFile.isFile()) {
            Object loaded = JsonFiles.read(stateFile, [:])
            if (loaded instanceof Map) {
                return (Map) loaded
            }
        }
        defaultState(ticketKey)
    }

    Map defaultState(String ticketKey) {
        Map state = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/ticket-state-defaults.json'),
            [:]
        )
        String now = Instant.now().toString()
        state.ticket_key = ticketKey
        state.created_at = now
        state.updated_at = now
        state
    }

    void save(Map state) {
        state.updated_at = Instant.now().toString()
        JsonFiles.write(paths.ticketStateFile(state.ticket_key.toString()), state)
    }

    static void addBlocker(Map state, String description, String owner = '') {
        ((List) state.blockers) << [
            description: description,
            status: 'active',
            owner: owner,
            since: Instant.now().toString()
        ]
        state.updated_at = Instant.now().toString()
    }

    static void resolveBlocker(Map state, int index) {
        ((List) state.blockers)[index].status = 'resolved'
        state.updated_at = Instant.now().toString()
    }

    static void addDecision(
        Map state, String id, String description, String owner = ''
    ) {
        ((List) state.decisions) << [
            id: id,
            description: description,
            status: 'open',
            resolution: '',
            owner: owner
        ]
        state.updated_at = Instant.now().toString()
    }

    static void resolveDecision(Map state, String id, String resolution) {
        Map decision = ((List<Map>) state.decisions).find { it.id == id }
        decision.status = 'resolved'
        decision.resolution = resolution
        state.updated_at = Instant.now().toString()
    }

    List<String> activeTickets() {
        if (!paths.stateDir.isDirectory()) {
            return []
        }
        paths.stateDir.listFiles()
            ?.findAll { it.isFile() && it.name.endsWith('.json') }
            ?.sort { it.name }
            ?.collect { it.name.substring(0, it.name.length() - 5) } ?: []
    }
}
