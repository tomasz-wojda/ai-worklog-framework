package ai.worklog.framework.commands

import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
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
        if (!action) {
            println 'Usage: ai-worklog jenkins {controllers|health|job|plugins|credentials|seed|syntax-check} ...'
            return exitCodes.userError
        }
        List<String> remaining = new ArrayList<>(args)
        boolean json = remaining.remove('--json')
        List<String> original = new ArrayList<>(remaining)
        Redaction redaction = new Redaction(frameworkRoot)
        JenkinsAdapter adapter = new JenkinsAdapter(
            paths,
            new ReadOnlyHttp(),
            [:],
            frameworkRoot,
            config,
            new ReadOnlyProcess(redaction)
        )
        Map payload
        try {
            payload = dispatch(action, remaining, adapter)
            rejectUnknownArgs(remaining, action)
        } catch (IllegalArgumentException exception) {
            JenkinsOperatorReport report = JenkinsOperatorReport.fromPayload([
                operation: action,
                fetched_at: JenkinsAdapter.utcNow(),
                status: Status.ERROR,
                controller: errorController(action, original),
                message: exception.message,
                items: []
            ])
            if (json) {
                print report.renderJson(redaction)
            } else {
                println exception.message
            }
            return exitCodes.userError
        } catch (Exception exception) {
            println "Jenkins operation failed: ${exception.message ?: exception.class.simpleName}"
            return exitCodes.systemError
        }
        JenkinsOperatorReport report = JenkinsOperatorReport.fromPayload(payload)
        print json ? report.renderJson(redaction) : report.renderHuman(redaction)
        JenkinsOperatorReport.exitCodeFor(report, exitCodes)
    }

    private static Map dispatch(String action, List<String> remaining, JenkinsAdapter adapter) {
        Map settings = adapter.settings()
        switch (action) {
            case 'controllers':
                return adapter.operatorControllers()
            case 'health':
                return adapter.operatorHealth(requireArg(remaining, 'controller', action), settings.timeout_seconds as int)
            case 'job':
                return adapter.operatorJob(
                    requireArg(remaining, 'controller', action),
                    requireArg(remaining, 'job', action),
                    parseBuilds(remaining, settings.max_builds as int),
                    remaining.remove('--parameters'),
                    settings.timeout_seconds as int
                )
            case 'plugins':
                List<String> required = parseRequirePlugins(remaining)
                required.addAll((List) (settings.required_plugins ?: []))
                return adapter.operatorPlugins(
                    requireArg(remaining, 'controller', action),
                    required.unique().sort(),
                    settings.timeout_seconds as int
                )
            case 'credentials':
                String domain = settings.credential_domain
                int domainIndex = remaining.indexOf('--domain')
                if (domainIndex >= 0) {
                    if (domainIndex + 1 >= remaining.size() || remaining[domainIndex + 1].startsWith('--')) {
                        throw new IllegalArgumentException('Missing value for --domain')
                    }
                    domain = remaining[domainIndex + 1]
                    remaining.remove(domainIndex + 1)
                    remaining.remove(domainIndex)
                }
                return adapter.operatorCredentials(
                    requireArg(remaining, 'controller', action),
                    domain,
                    settings.timeout_seconds as int
                )
            case 'seed':
                return adapter.operatorSeed(
                    requireArg(remaining, 'controller', action),
                    requireArg(remaining, 'job', action),
                    settings.timeout_seconds as int,
                    settings.max_builds as int
                )
            case 'syntax-check':
                return adapter.operatorSyntaxCheck(parseFiles(remaining, action), settings.process_timeout_seconds as int)
            default:
                throw new IllegalArgumentException(
                    'Usage: ai-worklog jenkins {controllers|health|job|plugins|credentials|seed|syntax-check}'
                )
        }
    }

    private static String requireArg(List<String> args, String label, String action) {
        String value = args.find { !it.startsWith('--') }
        if (!value) {
            throw new IllegalArgumentException("Missing ${label}")
        }
        args.remove(value)
        value
    }

    private static String errorController(String action, List<String> args) {
        if (action in ['controllers', 'syntax-check']) {
            return null
        }
        args.find { !it.startsWith('--') }
    }

    private static List<String> parseFiles(List<String> args, String action) {
        List<String> files = args.findAll { !it.startsWith('--') }
        if (!files) {
            throw new IllegalArgumentException('Missing file')
        }
        args.removeAll(files)
        files
    }

    private static int parseBuilds(List<String> args, int defaultBuilds) {
        int index = args.indexOf('--builds')
        if (index < 0) {
            return defaultBuilds
        }
        if (index + 1 >= args.size() || args[index + 1].startsWith('--')) {
            throw new IllegalArgumentException('Missing value for --builds')
        }
        int value = args[index + 1] as int
        args.remove(index + 1)
        args.remove(index)
        value
    }

    private static List<String> parseRequirePlugins(List<String> args) {
        List<String> required = []
        int index = 0
        while (index < args.size()) {
            if (args[index] == '--require') {
                if (index + 1 >= args.size() || args[index + 1].startsWith('--')) {
                    throw new IllegalArgumentException('Missing value for --require')
                }
                required << args[index + 1]
                args.remove(index + 1)
                args.remove(index)
                continue
            }
            index++
        }
        required
    }

    private static void rejectUnknownArgs(List<String> args, String action) {
        List<String> unknown = args.findAll { it.startsWith('--') }
        if (unknown) {
            throw new IllegalArgumentException("Unknown option for jenkins ${action}: ${unknown[0]}")
        }
        List<String> positional = args.findAll { !it.startsWith('--') }
        if (positional) {
            throw new IllegalArgumentException("Unexpected argument for jenkins ${action}: ${positional[0]}")
        }
    }
}
