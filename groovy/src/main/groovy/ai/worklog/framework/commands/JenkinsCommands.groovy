package ai.worklog.framework.commands

import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.jenkins.JenkinsOperatorReport
import ai.worklog.framework.jenkins.JenkinsScriptSource

class JenkinsCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths,
        Map config,
        Closure<Boolean> interactiveTerminal = {
            System.console() != null &&
                (System.getenv('TERM') ?: '') != 'dumb'
        },
        JenkinsAdapter adapterOverride = null,
        InputStream scriptInput = System.in
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        CommandContract contract = CommandContract.load(frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)
        if (!action) {
            System.err.print usage.renderPath(['service', 'jenkins'])
            return exitCodes.userError
        }
        String dispatchAction = action
        List<String> actionPath = ['service', 'jenkins', action]
        if (action == 'plugins') {
            String pluginAction = args && args[0] in ['list', 'vulnerabilities'] ?
                args.remove(0) : 'list'
            actionPath << pluginAction
            dispatchAction = pluginAction == 'vulnerabilities' ?
                'plugin-vulnerabilities' : 'plugins'
        }
        Map actionDefinition = contract.node(actionPath)
        if (!actionDefinition) {
            System.err.print usage.renderPath(['service', 'jenkins'])
            return exitCodes.userError
        }
        List<String> original = new ArrayList<>(args)
        Redaction redaction = new Redaction(frameworkRoot)
        JenkinsAdapter adapter = adapterOverride ?: new JenkinsAdapter(
                paths,
                new ReadOnlyHttp(),
                [:],
                frameworkRoot,
                config,
                new ReadOnlyProcess(redaction)
            )
        Map defaults = new LinkedHashMap(adapter.operatorRules)
        Map settings = adapter.settings()
        defaults.max_builds = settings.max_builds
        defaults.credential_domain = settings.credential_domain
        ParsedArguments parsed
        boolean requestedJson = original.contains('--json')
        boolean json = requestedJson
        boolean revealSecret = false
        Map payload
        try {
            parsed = new ArgumentParser(contract).parse(
                'service jenkins',
                actionDefinition,
                original,
                defaults
            )
            json = parsed.flag('--json')
            revealSecret = dispatchAction == 'credentials' &&
                parsed.flag('--show-secretValue')
            if (revealSecret || parsed.value('--id')) {
                json = false
            }
            validateCredentialReveal(
                parsed,
                revealSecret,
                requestedJson,
                interactiveTerminal
            )
            if (dispatchAction == 'run-script') {
                validateRunScriptSource(parsed)
            }
            payload = dispatch(dispatchAction, parsed, adapter, settings, scriptInput)
        } catch (UsageError exception) {
            JenkinsOperatorReport report = errorReport(
                dispatchAction,
                errorController(dispatchAction, null, original),
                exception.message
            )
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(actionPath)
            if (json) {
                print report.renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            JenkinsOperatorReport report = errorReport(
                dispatchAction,
                errorController(dispatchAction, parsed, original),
                exception.message
            )
            if (json) {
                print report.renderJson(redaction)
            } else {
                System.err.println exception.message
            }
            return exitCodes.userError
        } catch (Exception exception) {
            System.err.println "Jenkins operation failed: ${exception.message ?: exception.class.simpleName}"
            return exitCodes.systemError
        }
        if (revealSecret && payload.status == Status.READY) {
            print renderCredentialSecret(payload)
            return exitCodes.success
        }
        JenkinsOperatorReport report = JenkinsOperatorReport.fromPayload(payload)
        print json ? report.renderJson(redaction) : report.renderHuman(redaction)
        JenkinsOperatorReport.exitCodeFor(report, exitCodes)
    }

    private static Map dispatch(
        String action,
        ParsedArguments parsed,
        JenkinsAdapter adapter,
        Map settings,
        InputStream scriptInput
    ) {
        switch (action) {
            case 'controllers':
                return adapter.operatorControllers()
            case 'health':
                return adapter.operatorHealth(
                    parsed.positional('controller'),
                    settings.timeout_seconds as int
                )
            case 'job':
                return adapter.operatorJob(
                    parsed.positional('controller'),
                    parsed.positional('job'),
                    parsed.value('--builds').toString() as int,
                    parsed.flag('--parameters'),
                    settings.timeout_seconds as int
                )
            case 'plugins':
                List<String> required = parsed.values('--require')
                required.addAll((List) (settings.required_plugins ?: []))
                return adapter.operatorPlugins(
                    parsed.positional('controller'),
                    required.unique().sort(),
                    settings.timeout_seconds as int
                )
            case 'plugin-vulnerabilities':
                return adapter.operatorPluginVulnerabilities(
                    parsed.positional('controller'),
                    parsed.values('--plugin').unique().sort(),
                    parsed.values('--enrich')
                        .collect { it.toLowerCase() }
                        .unique()
                        .sort(),
                    settings.timeout_seconds as int
                )
            case 'credentials':
                if (parsed.flag('--show-secretValue')) {
                    return adapter.operatorCredentialSecret(
                        parsed.positional('controller'),
                        parsed.value('--domain').toString(),
                        parsed.value('--id').toString(),
                        settings.timeout_seconds as int
                    )
                }
                return adapter.operatorCredentials(
                    parsed.positional('controller'),
                    parsed.value('--domain').toString(),
                    settings.timeout_seconds as int
                )
            case 'seed':
                return adapter.operatorSeed(
                    parsed.positional('controller'),
                    parsed.positional('job'),
                    settings.timeout_seconds as int,
                    settings.max_builds as int
                )
            case 'syntax-check':
                return adapter.operatorSyntaxCheck(
                    parsed.variadic('files'),
                    settings.process_timeout_seconds as int
                )
            case 'nodes':
                return adapter.operatorNodes(
                    parsed.positional('controller'),
                    settings.timeout_seconds as int
                )
            case 'queue':
                return adapter.operatorQueue(
                    parsed.positional('controller'),
                    parsed.value('--limit').toString() as int,
                    settings.timeout_seconds as int
                )
            case 'jobs':
                return adapter.operatorJobs(
                    parsed.positional('controller'),
                    parsed.value('--folder')?.toString(),
                    parsed.value('--query')?.toString(),
                    parsed.value('--limit').toString() as int,
                    settings.timeout_seconds as int
                )
            case 'artifacts':
                return adapter.operatorArtifacts(
                    parsed.positional('controller'),
                    parsed.positional('job'),
                    parsed.positional('build_selector'),
                    settings.timeout_seconds as int
                )
            case 'download-artifact':
                return adapter.operatorDownloadArtifact(
                    parsed.positional('controller'),
                    parsed.positional('job'),
                    parsed.positional('build_selector'),
                    parsed.positional('artifact'),
                    parsed.flag('--apply'),
                    parsed.flag('--force')
                )
            case 'job-export':
                return adapter.operatorJobExport(
                    parsed.positional('controller'),
                    parsed.positional('job'),
                    parsed.flag('--cwd') ? new File(System.getProperty('user.dir')) : null,
                    parsed.flag('--apply'),
                    parsed.flag('--force')
                )
            case 'run-script':
                return adapter.operatorRunScript(
                    parsed.positional('controller'),
                    JenkinsScriptSource.read(
                        parsed.positional('script_file')?.toString(),
                        parsed.value('--script')?.toString(),
                        scriptInput,
                        settings.script_max_bytes as long
                    ),
                    parsed.flag('--apply')
                )
            case 'views':
                return adapter.operatorViews(
                    parsed.positional('controller'),
                    parsed.value('--view')?.toString(),
                    settings.timeout_seconds as int
                )
            case 'whoami':
                return adapter.operatorWhoami(
                    parsed.positional('controller'),
                    settings.timeout_seconds as int
                )
            case 'credential-domains':
                return adapter.operatorCredentialDomains(
                    parsed.positional('controller'),
                    settings.timeout_seconds as int
                )
            default:
                throw new UsageError(
                    "Unknown action for service jenkins: ${action}",
                    'service jenkins',
                    action
                )
        }
    }

    private static String errorController(
        String action,
        ParsedArguments parsed,
        List<String> args
    ) {
        if (action in ['controllers', 'syntax-check']) {
            return null
        }
        if (parsed?.positional('controller')) {
            return parsed.positional('controller')
        }
        List<String> optionsWithValues = [
            '--builds', '--require', '--plugin', '--enrich', '--domain',
            '--limit', '--folder', '--query', '--view', '--id', '--script'
        ]
        int index = 0
        while (index < args.size()) {
            String token = args[index]
            if (optionsWithValues.contains(token)) {
                index += 2
                continue
            }
            if (!token.startsWith('--')) {
                return token
            }
            index++
        }
        null
    }

    private static void validateRunScriptSource(ParsedArguments parsed) {
        String scriptFile = parsed.positional('script_file')?.toString()
        int sources = (scriptFile ? 1 : 0) + (parsed.value('--script') != null ? 1 : 0)
        if (sources != 1) {
            throw new UsageError(
                'run-script requires exactly one of <script_file>, - (stdin), or --script',
                'service jenkins',
                'run-script'
            )
        }
    }

    private static void validateCredentialReveal(
        ParsedArguments parsed,
        boolean revealSecret,
        boolean requestedJson,
        Closure<Boolean> interactiveTerminal
    ) {
        if (parsed.value('--id') && !revealSecret) {
            throw new UsageError(
                '--id requires --show-secretValue',
                'service jenkins',
                'credentials'
            )
        }
        if (!revealSecret) {
            return
        }
        if (!parsed.value('--id')) {
            throw new UsageError(
                '--show-secretValue requires --id',
                'service jenkins',
                'credentials'
            )
        }
        if (requestedJson) {
            throw new UsageError(
                '--show-secretValue cannot be combined with --json',
                'service jenkins',
                'credentials'
            )
        }
        if (!interactiveTerminal.call()) {
            throw new UsageError(
                '--show-secretValue requires an interactive terminal',
                'service jenkins',
                'credentials'
            )
        }
    }

    private static String renderCredentialSecret(Map payload) {
        String newline = System.lineSeparator()
        StringBuilder output = new StringBuilder()
        output.append('Jenkins credential secret').append(newline)
        output.append("  Controller: ${payload.controller}").append(newline)
        output.append("  Domain: ${payload.domain}").append(newline)
        output.append("  ID: ${payload.credential_id}").append(newline)
        output.append("  Type: ${payload.credential_type}").append(newline)
        ((List<Map>) payload.secret_components).each { component ->
            output.append("  ${component.name} (${component.encoding}):")
                .append(newline)
            String value = component.value.toString()
            output.append(value)
            if (!value.endsWith(newline)) {
                output.append(newline)
            }
        }
        output.toString()
    }

    private static JenkinsOperatorReport errorReport(String action, String controller, String message) {
        JenkinsOperatorReport.fromPayload([
            operation: action,
            fetched_at: JenkinsAdapter.utcNow(),
            status: Status.ERROR,
            controller: controller,
            message: message,
            items: []
        ])
    }

}
