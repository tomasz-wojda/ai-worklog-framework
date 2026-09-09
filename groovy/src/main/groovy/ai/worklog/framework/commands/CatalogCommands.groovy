package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import groovy.json.JsonOutput

class CatalogCommands {
    static int run(String action, List<String> args, CatalogLoader loader) {
        CommandContract contract = CommandContract.load(loader.frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)
        if (!action) {
            System.err.print usage.renderPath(['catalog'])
            return 1
        }
        Map definition = contract.node(['catalog', action])
        if (!definition) {
            System.err.println("Unknown action for catalog: ${action}")
            System.err.println()
            System.err.print usage.renderPath(['catalog'])
            return 1
        }
        ParsedArguments parsed
        try {
            parsed = new ArgumentParser(contract).parse('catalog', definition, args, [:])
        } catch (UsageError exception) {
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['catalog', action])
            return 1
        }
        Map<String, Map> catalog = loader.load()
        switch (action) {
            case 'list':
                List<Map> items = catalog.collect { id, entry ->
                    [
                        id: id,
                        name: entry.name?.toString() ?: id,
                        type: entry.type?.toString() ?: '',
                        owners: ((List) (entry.owners ?: []))*.toString()
                    ]
                }.sort { a, b -> a.id <=> b.id }
                if (parsed.flag('--json')) {
                    println JsonOutput.prettyPrint(JsonOutput.toJson([
                        operation: 'list',
                        status: 'ok',
                        count: items.size(),
                        items: items
                    ]))
                } else if (!items) {
                    println 'No catalog systems found.'
                } else {
                    println "Catalog systems (${items.size()}):"
                    items.each {
                        String owners = it.owners ? " [${it.owners.join(', ')}]" : ''
                        println "  ${it.id}: ${it.name} (${it.type})${owners}"
                    }
                }
                return 0
            case 'validate':
                if (!catalog) {
                    System.err.println 'No catalog entries found.'
                    return 1
                }
                List<List<String>> errors = []
                catalog.each { id, entry ->
                    loader.validate(entry).each { errors << [id, it] }
                }
                if (errors) {
                    System.err.println "Catalog validation: ${errors.size()} error(s)"
                    errors.each { System.err.println "  [${it[0]}] ${it[1]}" }
                    return 1
                }
                println "Catalog validation: PASS (${catalog.size()} entries)"
                return 0
            case 'show':
                String system = parsed.positional('system')
                Map entry = catalog[system]
                if (!entry) {
                    System.err.println "Catalog system not found: ${system}"
                    if (catalog) {
                        System.err.println "Available: ${catalog.keySet().sort().join(', ')}"
                    }
                    return 1
                }
                println CatalogLoader.pretty(entry)
                return 0
            case 'search':
                List<String> queryParts = parsed.variadic('query')
                String queryText = queryParts.join(' ')
                String query = queryText.toLowerCase()
                List<List<String>> matches = catalog.findAll { id, entry ->
                    CatalogLoader.pretty(entry).toLowerCase().contains(query)
                }.collect { id, entry -> [id, entry.name?.toString() ?: id] }
                if (!matches) {
                    System.err.println "No catalog systems match: ${queryText}"
                    return 1
                }
                println "Found ${matches.size()} match(es) for '${queryText}':"
                matches.sort { it[0] }.each { println "  ${it[0]}: ${it[1]}" }
                return 0
        }
    }
}
