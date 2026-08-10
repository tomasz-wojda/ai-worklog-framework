package ai.worklog.framework.commands

import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.StateManager

class DeliveryCommands {
    static int run(String action, List<String> args, File frameworkRoot, StateManager states) {
        if (action != 'status' || !args) {
            println 'Usage: ai-worklog delivery status <TICKET-KEY>'
            return 1
        }
        status(args[0], frameworkRoot, states)
        0
    }

    static void status(String key, File frameworkRoot, StateManager states) {
        Map data = states.load(key)
        Map rules = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/delivery-rules.json'), [:])
        List<List<String>> stages = [
            ['Investigation', value(data.investigation, 'state', 'unknown')],
            ['Implementation', value(data.implementation, 'state', 'unknown')],
            ['Pull Requests', prSummary((List) (data.pull_requests ?: []))],
            ['Builds', buildSummary((List) (data.builds ?: []))],
            ['GitOps', value(data.gitops, 'state', 'unknown')],
            ['Synchronization', value(data.synchronization, 'state', 'unknown')],
            ['Verification', value(data.verification, 'state', 'unknown')]
        ]

        println '=' * 72
        println "  DELIVERY STATUS: ${key}"
        println "  ${DailyCommands.timestamp()}"
        println '=' * 72
        println()
        stages.each { println "  ${indicator(it[1], rules)} ${it[0]}: ${it[1]}" }
        println()

        List<String> gaps = gaps(data, rules)
        if (gaps) {
            println 'DELIVERY GAPS:'
            gaps.each { println "  - ${it}" }
            println()
        }
        println '=' * 72
    }

    static String value(Object object, String key, String fallback) {
        object instanceof Map && object[key] != null ? object[key].toString() : fallback
    }

    static String prSummary(List prs) {
        if (!prs) return 'none'
        int merged = prs.count { it.state == 'merged' }
        int open = prs.count { it.state == 'open' }
        if (open) return "${open} open, ${merged} merged"
        if (merged) return "all ${merged} merged"
        "${prs.size()} total"
    }

    static String buildSummary(List builds) {
        if (!builds) return 'none'
        int success = builds.count { it.result == 'success' }
        int failure = builds.count { it.result == 'failure' }
        failure ? "${failure} failed, ${success} success" : "${success} success"
    }

    static String indicator(String status, Map rules) {
        String lower = status.toLowerCase()
        if (((List) (rules.positive_statuses ?: [])).any { lower.contains(it.toString()) }) {
            return '[OK]'
        }
        if (((List) (rules.negative_statuses ?: [])).any { lower.contains(it.toString()) }) {
            return '[!!]'
        }
        if (((List) (rules.empty_statuses ?: [])).contains(status)) {
            return '[--]'
        }
        '[..]'
    }

    static List<String> gaps(Map data, Map rules) {
        Map messages = (Map) (rules.gap_messages ?: [:])
        List<String> output = []
        if (data.implementation?.state == 'complete' && data.implementation?.uncommitted) {
            output << messages.uncommitted
        }
        List prs = (List) (data.pull_requests ?: [])
        List merged = prs.findAll { it.state == 'merged' }
        List builds = (List) (data.builds ?: [])
        if (merged && !builds) output << messages.merged_without_build
        if (builds && data.gitops?.state == 'not_applicable') output << messages.build_without_gitops
        if (data.synchronization?.state == 'forced_sync_required') output << messages.forced_sync
        if (data.verification?.state == 'not_started' && merged) output << messages.unverified_merge
        output.findAll { it != null }.collect { it.toString() }
    }
}
