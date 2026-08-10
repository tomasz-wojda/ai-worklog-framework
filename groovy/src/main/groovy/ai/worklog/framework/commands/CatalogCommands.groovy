package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader

class CatalogCommands {
    static int run(String action, List<String> args, CatalogLoader loader) {
        Map<String, Map> catalog = loader.load()
        switch (action) {
            case 'validate':
                if (!catalog) {
                    println 'No catalog entries found.'
                    return 1
                }
                List<List<String>> errors = []
                catalog.each { id, entry ->
                    loader.validate(entry).each { errors << [id, it] }
                }
                if (errors) {
                    println "Catalog validation: ${errors.size()} error(s)"
                    errors.each { println "  [${it[0]}] ${it[1]}" }
                    return 1
                }
                println "Catalog validation: PASS (${catalog.size()} entries)"
                return 0
            case 'show':
                if (!args) {
                    println 'Usage: ai-worklog catalog show <service>'
                    return 1
                }
                Map entry = catalog[args[0]]
                if (!entry) {
                    println "Service not found: ${args[0]}"
                    if (catalog) {
                        println "Available: ${catalog.keySet().sort().join(', ')}"
                    }
                    return 1
                }
                println CatalogLoader.pretty(entry)
                return 0
            case 'search':
                if (!args) {
                    println 'Usage: ai-worklog catalog search <query>'
                    return 1
                }
                String query = args.join(' ').toLowerCase()
                List<List<String>> matches = catalog.findAll { id, entry ->
                    CatalogLoader.pretty(entry).toLowerCase().contains(query)
                }.collect { id, entry -> [id, entry.name?.toString() ?: id] }
                if (!matches) {
                    println "No catalog entries match: ${args.join(' ')}"
                    return 1
                }
                println "Found ${matches.size()} match(es) for '${args.join(' ')}':"
                matches.sort { it[0] }.each { println "  ${it[0]}: ${it[1]}" }
                return 0
            default:
                println 'Usage: ai-worklog catalog {validate|show|search}'
                return 1
        }
    }
}
