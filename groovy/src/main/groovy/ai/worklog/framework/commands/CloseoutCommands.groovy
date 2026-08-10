package ai.worklog.framework.commands

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.StateManager

class CloseoutCommands {
    static int run(String action, List<String> args, FrameworkPaths paths, StateManager states) {
        if (action != 'report' || !args) {
            println 'Usage: ai-worklog closeout report <TICKET-KEY>'
            return 1
        }
        report(args[0], paths, states)
        0
    }

    static void report(String key, FrameworkPaths paths, StateManager states) {
        Map data = states.load(key)
        println '=' * 72
        println "  CLOSE-OUT REPORT: ${key}"
        println "  Generated: ${DailyCommands.timestamp()}"
        println '=' * 72
        println()

        if (data.summary) {
            println "SUMMARY: ${data.summary}"
            println()
        }

        List prs = (List) (data.pull_requests ?: [])
        if (prs) {
            println "PULL REQUESTS (${prs.size()}):"
            prs.each {
                String icon = [merged: '[MERGED]', open: '[OPEN]', closed: '[CLOSED]']
                    .get(it.state, '[?]')
                println "  ${icon} ${it.repo ?: '?'} #${it.number ?: '?'}: ${it.url ?: ''}"
            }
            println()
        }

        List builds = (List) (data.builds ?: [])
        if (builds) {
            println "BUILDS (${builds.size()}):"
            builds.each {
                String icon = [success: '[OK]', failure: '[FAIL]'].get(it.result, '[?]')
                println "  ${icon} ${it.job ?: '?'} #${it.number ?: '?'} → ${it.artifact ?: 'n/a'}"
            }
            println()
        }

        Map sync = data.synchronization instanceof Map ? (Map) data.synchronization : [:]
        if (sync.state && sync.state != 'unknown') {
            println "SYNCHRONIZATION: ${sync.state}"
            if (sync.manual_actions) {
                println '  Manual actions:'
                ((List) sync.manual_actions).each { println "    - ${it}" }
            }
            println()
        }

        Map verification = data.verification instanceof Map ? (Map) data.verification : [:]
        List checks = (List) (verification.checks ?: [])
        if (checks) {
            println "VERIFICATION (${checks.size()} checks):"
            checks.each {
                println "  ${it.passed ? '[PASS]' : '[FAIL]'} ${it.name ?: '?'} (${it.timestamp ?: ''})"
            }
            println()
        }

        Map closeout = data.closeout instanceof Map ? (Map) data.closeout : [:]
        println 'CLOSE-OUT STATUS:'
        println "  Implementation complete: ${yesNo(closeout.implementation_complete)}"
        println "  Deployment complete:     ${yesNo(closeout.deployment_complete)}"
        println "  Tempo logged:            ${yesNo(closeout.tempo_logged)}"
        if (closeout.tempo_seconds) {
            println String.format('  Tempo total:             %.1fh', (closeout.tempo_seconds as double) / 3600d)
        }
        println "  Worklog archived:        ${yesNo(closeout.worklog_archived)}"
        println "  Handover generated:      ${yesNo(closeout.handover_generated)}"
        println()

        List decisions = ((List) (data.decisions ?: [])).findAll { it.status == 'open' }
        List blockers = ((List) (data.blockers ?: [])).findAll { it.status == 'active' }
        if (decisions || blockers) {
            println 'UNRESOLVED ITEMS:'
            decisions.each { println "  [DECISION] ${it.id ?: '?'}: ${it.description ?: ''}" }
            blockers.each { println "  [BLOCKER] ${it.description ?: ''}" }
            println()
        }

        List<File> logs = paths.worklog.isDirectory() ?
            paths.worklog.listFiles()
                ?.findAll { it.isFile() && it.name.endsWith('.log') && it.name.contains("_${key}") }
                ?.sort { it.name } ?: [] : []
        if (logs) {
            println 'ARCHIVABLE WORKLOGS:'
            logs.each { println "  - worklog/${it.name} → worklog/done/${it.name}" }
            println()
        }
        println '=' * 72
    }

    static String yesNo(Object value) {
        value ? 'Yes' : 'No'
    }
}
