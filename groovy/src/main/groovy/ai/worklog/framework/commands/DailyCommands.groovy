package ai.worklog.framework.commands

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.StateManager

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DailyCommands {
    static int run(String action, FrameworkPaths paths, StateManager states) {
        switch (action) {
            case 'start':
                dayStart(paths, states)
                return 0
            case 'end':
                dayEnd(states)
                return 0
            default:
                println 'Usage: ai-worklog day {start|end}'
                return 1
        }
    }

    static void dayStart(FrameworkPaths paths, StateManager states) {
        println '=' * 72
        println '  DAY START REPORT'
        println "  ${timestamp()}"
        println '=' * 72
        println()

        List<String> tickets = states.activeTickets()
        if (tickets) {
            println "ACTIVE TICKETS (${tickets.size()}):"
            tickets.each { key ->
                Map state = states.load(key)
                List<String> parts = ["mode=${state.governance_mode ?: '?'}"]
                int blockers = ((List) (state.blockers ?: [])).count { it.status == 'active' }
                if (blockers) parts << "BLOCKED(${blockers})"
                if (state.implementation?.uncommitted) parts << 'UNCOMMITTED'
                println "  ${key}: ${parts.join(', ')}"
                if (state.next_action) println "    next: ${state.next_action}"
            }
            println()
        } else {
            println 'No active ticket state files found.'
            println()
        }

        List<File> worklogs = paths.worklog.isDirectory() ?
            paths.worklog.listFiles()
                ?.findAll { it.isFile() && it.name.endsWith('.log') && it.name != 'tickets.log' }
                ?.sort { it.name } ?: [] : []
        if (worklogs) {
            println "ACTIVE WORKLOGS (${worklogs.size()}):"
            worklogs.each { println "  - ${it.name}" }
            println()
        }

        println 'RECOMMENDATIONS:'
        println "  - Run 'ai-worklog preflight' to verify environment readiness"
        println '  - Run Jira summary to check board state'
        println "  - Review Tempo for today's logged hours"
        println()
        println '=' * 72
    }

    static void dayEnd(StateManager states) {
        println '=' * 72
        println '  DAY END SUMMARY'
        println "  ${timestamp()}"
        println '=' * 72
        println()

        List<String> uncommitted = []
        List<List<String>> blockers = []
        List<List<String>> continuation = []
        states.activeTickets().each { key ->
            Map state = states.load(key)
            if (state.implementation?.uncommitted) uncommitted << key
            ((List) (state.blockers ?: [])).findAll { it.status == 'active' }.each {
                blockers << [key, it.description?.toString() ?: '']
            }
            if (state.next_action) continuation << [key, state.next_action.toString()]
        }

        if (uncommitted) {
            println 'UNCOMMITTED WORK:'
            uncommitted.each { println "  - ${it}" }
            println()
        }
        if (blockers) {
            println 'UNRESOLVED BLOCKERS:'
            blockers.each { println "  - [${it[0]}] ${it[1]}" }
            println()
        }

        println 'CONTINUATION CAPSULE:'
        if (continuation) {
            continuation.each { println "  ${it[0]}: ${it[1]}" }
        } else {
            println '  No explicit next actions recorded.'
        }
        println()
        println 'REMINDERS:'
        println '  - Verify Tempo hours are logged for today'
        println '  - Check for open PRs requiring review'
        println '  - Archive completed worklogs to done/'
        println()
        println '=' * 72
    }

    static String timestamp() {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm'))
    }
}
