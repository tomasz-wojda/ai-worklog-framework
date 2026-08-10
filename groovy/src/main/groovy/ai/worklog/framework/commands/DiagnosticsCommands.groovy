package ai.worklog.framework.commands

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.diagnostics.DiagnosticExecutor
import ai.worklog.framework.diagnostics.EvidenceBundle

class DiagnosticsCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        Map packs = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/diagnostic-packs.json'),
            [:]
        )
        switch (action) {
            case 'list':
                listPacks(packs)
                return 0
            case 'run':
                return runPack(args, packs, frameworkRoot, paths, exitCodes)
            default:
                println 'Usage: ai-worklog diag {list|run}'
                return 1
        }
    }

    static void listPacks(Map packs) {
        println "Available diagnostic packs (${packs.size()}):"
        println()
        packs.keySet().sort().each { id ->
            Map info = (Map) packs[id]
            String safety = info.read_only ? 'read-only' : 'WRITE-CAPABLE'
            println "  ${id}"
            println "    ${info.name} [${safety}]"
            println "    ${info.description}"
            println "    requires: ${((List) info.prerequisites).join(', ')}"
            println()
        }
    }

    static int runPack(
        List<String> args,
        Map packs,
        File frameworkRoot,
        FrameworkPaths paths,
        ExitCodes exitCodes
    ) {
        if (!args) {
            println 'Usage: ai-worklog diag run <pack> [--namespace NS] [--app APP]'
            return 1
        }
        String id = args[0]
        Map info = (Map) packs[id]
        if (!info) {
            println "Unknown pack: ${id}"
            println "Available: ${packs.keySet().sort().join(', ')}"
            return 1
        }
        Map<String, String> parameters = [:]
        List<String> parameterValues = optionValues(args, '--param')
        String invalid = parameterValues.find { !it.contains('=') }
        if (invalid) {
            println "Invalid parameter: ${invalid} (expected key=value)"
            return 1
        }
        parameterValues.each { item ->
            List<String> pair = item.split('=', 2).toList()
            parameters[pair[0]] = pair[1]
        }
        ['namespace', 'app', 'service'].each { name ->
            String value = option(args, "--${name}")
            if (value) parameters[name] = value
        }
        File output = option(args, '--output') ?
            new File(option(args, '--output')) : null
        Map result
        try {
            result = new DiagnosticExecutor(frameworkRoot).runPack(
                id,
                info,
                parameters,
                paths,
                output
            )
        } catch (IOException | IllegalArgumentException exception) {
            println "Diagnostic execution failed: ${exception.message}"
            return 1
        }
        EvidenceBundle bundle = (EvidenceBundle) result.bundle
        if (args.contains('--json')) {
            println CatalogLoader.pretty(bundle.toMap())
        } else {
            println "Running: ${info.name}"
            println "  Pack: ${id}"
            println "  Status: ${bundle.status}"
            bundle.steps.each { step ->
                println "  [${step.exit_code}] ${step.id} (${step.duration_ms}ms)"
                if (step.stderr) println "    ${step.stderr}"
            }
            println "  Evidence: ${result.path}"
        }
        bundle.status == 'success' ? exitCodes.success :
            bundle.status == 'blocked' ? exitCodes.blocked : exitCodes.userError
    }

    static String option(List<String> args, String name) {
        int index = args.indexOf(name)
        index >= 0 && index + 1 < args.size() ? args[index + 1] : null
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
