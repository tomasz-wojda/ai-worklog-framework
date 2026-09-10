package ai.worklog.framework.commands

import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import groovy.json.JsonOutput

class ServiceCommands {
    static int run(
        String service,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths,
        Map config
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        CommandContract contract = CommandContract.load(frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)
        if (!service) {
            System.err.print usage.renderPath(['service'])
            return exitCodes.userError
        }
        if (service == 'list') {
            Map definition = contract.node(['service', 'list'])
            try {
                ParsedArguments parsed = new ArgumentParser(contract).parse(
                    'service',
                    definition,
                    args,
                    [:]
                )
                List<Map> items = contract.children(['service'])
                    .findAll { it.kind == 'service' }
                    .collect { [id: it.name.toString(), description: it.description.toString()] }
                    .sort { a, b -> a.id <=> b.id }
                if (parsed.flag('--json')) {
                    println JsonOutput.prettyPrint(JsonOutput.toJson([
                        operation: 'list',
                        status: 'ready',
                        items: items
                    ]))
                } else {
                    println "Service operators (${items.size()}):"
                    items.each { println "  ${it.id}: ${it.description}" }
                }
                return exitCodes.success
            } catch (UsageError exception) {
                System.err.println(exception.message)
                System.err.println()
                System.err.print usage.renderPath(['service', 'list'])
                return exitCodes.userError
            }
        }
        if (service == 'automox') {
            String action = args ? args.remove(0) : null
            return AutomoxCommands.run(action, args, frameworkRoot, paths, config)
        }
        if (service == 'jenkins') {
            String action = args ? args.remove(0) : null
            return JenkinsCommands.run(action, args, frameworkRoot, paths, config)
        }
        if (service == 'jira') {
            String action = args ? args.remove(0) : null
            return JiraCommands.run(action, args, frameworkRoot, paths)
        }
        System.err.println("Unknown service: ${service}")
        System.err.println()
        System.err.print usage.renderPath(['service'])
        exitCodes.userError
    }
}
