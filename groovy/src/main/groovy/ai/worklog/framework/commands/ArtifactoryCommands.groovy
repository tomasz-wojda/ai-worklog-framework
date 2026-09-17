package ai.worklog.framework.commands

import ai.worklog.framework.adapters.ArtifactoryAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.artifactory.ArtifactoryOperatorReport
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status

class ArtifactoryCommands {
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
            System.err.print usage.renderPath(['service', 'artifactory'])
            return exitCodes.userError
        }
        Map actionDefinition = contract.node(['service', 'artifactory', action])
        if (!actionDefinition) {
            System.err.println("Unknown action for service artifactory: ${action}")
            System.err.println()
            System.err.print usage.renderPath(['service', 'artifactory'])
            return exitCodes.userError
        }
        List<String> original = new ArrayList<>(args)
        boolean json = original.contains('--json')
        Redaction redaction = new Redaction(frameworkRoot)
        Map rules = ArtifactoryAdapter.loadOperatorRules(frameworkRoot)
        ArtifactoryAdapter adapter = new ArtifactoryAdapter(paths, new ReadOnlyHttp(), rules, config)
        ParsedArguments parsed
        try {
            parsed = new ArgumentParser(contract).parse(
                'service artifactory',
                actionDefinition,
                original,
                [
                    default_profile: rules.default_profile,
                    timeouts: [
                        default_seconds: adapter.settings().timeout_seconds
                    ],
                    limits: [
                        repositories_default: adapter.settings().repositories_default,
                        artifacts_default: adapter.settings().artifacts_default,
                        manifest_default_bytes: adapter.settings().manifest_default_bytes
                    ]
                ]
            )
            json = parsed.flag('--json')
            Map payload = dispatch(action, parsed, adapter)
            ArtifactoryOperatorReport report = ArtifactoryOperatorReport.fromPayload(payload)
            print json ? report.renderJson(redaction) : report.renderHuman(redaction)
            return ArtifactoryOperatorReport.exitCodeFor(report, exitCodes)
        } catch (UsageError exception) {
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['service', 'artifactory', action])
            if (json) {
                print errorReport(action, exception.message, 'user').renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            if (json) {
                print errorReport(action, exception.message, 'user').renderJson(redaction)
            } else {
                System.err.println redaction.redactString(exception.message ?: 'Invalid Artifactory request')
            }
            return exitCodes.userError
        } catch (Exception exception) {
            String message = "Artifactory operation failed: ${exception.message ?: exception.class.simpleName}"
            if (json) {
                print errorReport(action, message, 'system').renderJson(redaction)
            } else {
                System.err.println redaction.redactString(message)
            }
            return exitCodes.systemError
        }
    }

    private static Map dispatch(
        String action,
        ParsedArguments parsed,
        ArtifactoryAdapter adapter
    ) {
        String profile = parsed.value('--profile')?.toString()
        int timeout = parsed.value('--timeout') ?
            parsed.value('--timeout').toString() as int :
            adapter.settings().timeout_seconds as int
        switch (action) {
            case 'profiles':
                return adapter.operatorProfiles()
            case 'status':
                return adapter.operatorStatus(profile, timeout)
            case 'auth-test':
                return adapter.operatorAuthTest(
                    profile,
                    parsed.positional('repository'),
                    parsed.positional('path'),
                    timeout
                )
            case 'repositories':
                return adapter.operatorRepositories(
                    profile,
                    parsed.value('--query')?.toString(),
                    parsed.value('--type')?.toString(),
                    parsed.value('--package-type')?.toString(),
                    parsed.value('--limit').toString() as int,
                    timeout
                )
            case 'artifacts':
                return adapter.operatorArtifacts(
                    profile,
                    parsed.positional('repository'),
                    parsed.positional('path'),
                    parsed.value('--query')?.toString(),
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parsed.flag('--recursive'),
                    parsed.value('--limit').toString() as int,
                    timeout
                )
            case 'artifact':
                return adapter.operatorArtifact(
                    profile,
                    parsed.positional('repository'),
                    parsed.positional('path'),
                    timeout
                )
            case 'manifest':
                return adapter.operatorManifest(
                    profile,
                    parsed.positional('repository'),
                    parsed.positional('path'),
                    parsed.value('--max-bytes').toString() as int,
                    timeout
                )
            default:
                throw new UsageError(
                    "Unknown action for service artifactory: ${action}",
                    'service artifactory',
                    action
                )
        }
    }

    private static ArtifactoryOperatorReport errorReport(
        String action,
        String message,
        String errorKind
    ) {
        ArtifactoryOperatorReport.fromPayload([
            operation: action,
            fetched_at: ArtifactoryAdapter.utcNow(),
            status: Status.ERROR,
            message: message,
            error_kind: errorKind,
            items: []
        ])
    }
}
