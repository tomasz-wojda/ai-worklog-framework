package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.FrameworkPaths
import groovy.json.JsonSlurper

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TicketCommands {
    static int run(String action, List<String> args, FrameworkPaths paths, CatalogLoader loader) {
        if (action != 'prepare' || !args) {
            println 'Usage: ai-worklog ticket prepare <TICKET-KEY>'
            return 1
        }
        prepare(args[0], paths, loader)
        0
    }

    static void prepare(String ticketKey, FrameworkPaths paths, CatalogLoader loader) {
        Map<String, Map> catalog = loader.load()
        String project = ticketKey.contains('-') ?
            ticketKey.substring(0, ticketKey.lastIndexOf('-')) : ticketKey
        List<String> services = loader.findServices(project)
        List<File> active = matchingLogs(paths.worklog, ticketKey)
        List<File> archived = matchingLogs(paths.worklogDone, ticketKey)
        Map<String, String> repositories = new TreeMap<>()
        services.each { id ->
            ((List) (catalog[id]?.repositories ?: [])).each { repository ->
                String localDir = repository.local_dir?.toString()
                if (localDir) {
                    repositories[localDir] = new File(paths.root, "repos/${localDir}").isDirectory() ?
                        'present' : 'NOT CLONED'
                }
            }
        }
        List<Map> pullRequests = findPullRequests(ticketKey)

        println '=' * 72
        println "  PREPARATION REPORT: ${ticketKey}"
        println "  Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm'))}"
        println '=' * 72
        println()

        println 'WORKLOG HISTORY:'
        if (active) {
            println '  Active:'
            active.each { println "    - ${it.name}" }
        }
        if (archived) {
            println '  Archived (in done/):'
            archived.each { println "    - ${it.name}" }
        }
        if (!active && !archived) {
            println '  No previous worklogs found.'
        }
        println()

        println 'MATCHED SERVICES:'
        if (services) {
            services.each { id ->
                Map entry = catalog[id]
                println "  - ${id}: ${entry.name ?: 'unnamed'} (${entry.type ?: '?'})"
            }
        } else {
            println '  No catalog matches. Manual service identification required.'
        }
        println()

        if (repositories) {
            println 'REPOSITORIES:'
            repositories.each { name, status ->
                println "  ${status == 'present' ? '[OK]' : '[MISSING]'} ${name}"
            }
            println()
        }

        if (pullRequests) {
            println 'OPEN PULL REQUESTS:'
            pullRequests.each {
                println "  #${it.number}: ${it.title}"
                if (it.url) println "         ${it.url}"
            }
            println()
        }

        println 'DELIVERY PATH:'
        boolean hasDelivery = false
        services.each { id ->
            List delivery = (List) (catalog[id]?.delivery_path ?: [])
            if (delivery) {
                hasDelivery = true
                println "  ${id}: ${delivery.join(' → ')}"
            }
        }
        if (!hasDelivery) {
            println '  Not defined in catalog. Manual identification required.'
        }
        println()

        println 'READINESS:'
        List<String> missing = repositories.findAll { it.value == 'NOT CLONED' }.keySet().toList()
        if (!services) {
            println '  [?] catalog: No catalog services matched this ticket'
        } else if (missing) {
            println "  [DEGRADED] repositories: Not cloned: ${missing.join(', ')}"
        } else {
            println '  [OK] repositories: All referenced repositories present'
        }
        println()

        println 'PREPARATION GAPS:'
        if (!services) {
            println '  - No catalog service match; add entry or identify service manually'
        }
        if (missing) {
            println "  - Missing repos: ${missing.join(', ')}"
        }
        if (services && !missing) {
            println '  - None identified'
        }
        println()
        println '=' * 72
    }

    private static List<File> matchingLogs(File directory, String ticketKey) {
        if (!directory.isDirectory()) {
            return []
        }
        directory.listFiles()
            ?.findAll { it.isFile() && it.name.endsWith('.log') && it.name.contains("_${ticketKey}") }
            ?.sort { it.name } ?: []
    }

    private static List<Map> findPullRequests(String ticketKey) {
        try {
            Process process = new ProcessBuilder(
                'gh', 'pr', 'list', '--search', ticketKey, '--state', 'open',
                '--json', 'number,title,url,repository'
            ).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return []
            }
            if (process.exitValue() != 0) {
                return []
            }
            Object parsed = new JsonSlurper().parseText(process.inputStream.getText('UTF-8'))
            parsed instanceof List ? (List<Map>) parsed : []
        } catch (Exception ignored) {
            []
        }
    }
}
