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

class JenkinsCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths,
        Map config
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        CommandContract contract = CommandContract.load(frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)
        if (!action) {
            System.err.print usage.renderPath(['service', 'jenkins'])
            return exitCodes.userError
        }
        Map actionDefinition = contract.node(['service', 'jenkins', action])
        if (!actionDefinition) {
            System.err.print usage.renderPath(['service', 'jenkins'])
            return exitCodes.userError
        }
        List<String> original = new ArrayList<>(args)
        Redaction redaction = new Redaction(frameworkRoot)
        JenkinsAdapter adapter = new JenkinsAdapter(
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
        boolean json = original.contains('--json')
        Map payload
        try {
            parsed = new ArgumentParser(contract).parse(
                'service jenkins',
                actionDefinition,
                original,
                defaults
            )
            json = parsed.flag('--json')
            payload = dispatch(action, parsed, adapter, settings)
        } catch (UsageError exception) {
            JenkinsOperatorReport report = errorReport(
                action,
                errorController(action, null, original),
                exception.message
            )
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['service', 'jenkins', action])
            if (json) {
                print report.renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            JenkinsOperatorReport report = errorReport(
                action,
                errorController(action, parsed, original),
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
        JenkinsOperatorReport report = JenkinsOperatorReport.fromPayload(payload)
        print json ? report.renderJson(redaction) : report.renderHuman(redaction)
        JenkinsOperatorReport.exitCodeFor(report, exitCodes)
    }

    private static Map dispatch(
        String action,
        ParsedArguments parsed,
        JenkinsAdapter adapter,
        Map settings
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
            case 'credentials':
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
        List<String> optionsWithValues = ['--builds', '--require', '--domain', '--limit', '--folder', '--query', '--view']
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
