package ai.worklog.framework.commands

import ai.worklog.framework.adapters.JiraAssetsAdapter
import ai.worklog.framework.adapters.JiraOperatorAdapter
import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.TempoOperatorAdapter
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.jira.JiraOperatorReport
import ai.worklog.framework.jira.JiraWorklogVerifier

import java.time.LocalDate

class JiraCommands {
    static int run(
        String action,
        List<String> args,
        File frameworkRoot,
        FrameworkPaths paths
    ) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        CommandContract contract = CommandContract.load(frameworkRoot)
        UsageRenderer usage = new UsageRenderer(contract)
        if (!action) {
            System.err.print usage.renderPath(['service', 'jira'])
            return exitCodes.userError
        }
        Map definition = contract.node(['service', 'jira', action])
        if (!definition) {
            System.err.println("Unknown action for service jira: ${action}")
            System.err.println()
            System.err.print usage.renderPath(['service', 'jira'])
            return exitCodes.userError
        }
        Map rules = loadRules(frameworkRoot, paths)
        List<String> original = new ArrayList<>(args)
        boolean json = original.contains('--json')
        Redaction redaction = new Redaction(frameworkRoot)
        try {
            ParsedArguments parsed = new ArgumentParser(contract).parse(
                'service jira',
                definition,
                original,
                rules
            )
            json = parsed.flag('--json')
            JiraOperatorAdapter jira = new JiraOperatorAdapter(paths, new ReadOnlyHttp(), rules)
            TempoOperatorAdapter tempo = new TempoOperatorAdapter(jira, new JsonWriteHttp(), rules)
            JiraAssetsAdapter assets = new JiraAssetsAdapter(jira, rules)
            Map payload = dispatch(action, parsed, jira, tempo, assets, paths)
            JiraOperatorReport report = JiraOperatorReport.fromPayload(payload)
            print json ? report.renderJson(redaction) : report.renderHuman(redaction)
            return JiraOperatorReport.exitCodeFor(report, exitCodes)
        } catch (UsageError exception) {
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['service', 'jira', action])
            if (json) {
                JiraOperatorReport report = JiraOperatorReport.fromPayload(
                    JiraOperatorAdapter.report(
                        action,
                        Status.ERROR,
                        [],
                        [message: exception.message, error_kind: 'user']
                    )
                )
                print report.renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.message)
            return exitCodes.userError
        } catch (Exception exception) {
            System.err.println("Jira operation failed: ${exception.message ?: exception.class.simpleName}")
            return exitCodes.systemError
        }
    }

    static Map loadRules(File frameworkRoot, FrameworkPaths paths) {
        Map shared = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/jira-operator-rules.json'),
            [:]
        )
        Map local = (Map) JsonFiles.read(
            new File(paths.serviceDir('jira'), 'jira-operator.json'),
            [:]
        )
        JsonFiles.deepMerge(shared, local)
    }

    private static Map dispatch(
        String action,
        ParsedArguments parsed,
        JiraOperatorAdapter jira,
        TempoOperatorAdapter tempo,
        JiraAssetsAdapter assets,
        FrameworkPaths paths
    ) {
        switch (action) {
            case 'ticket':
                return jira.ticket(parsed.positional('ticket_key'))
            case 'summary':
                return jira.summary(parsed.value('--limit').toString() as int)
            case 'rejected':
                return jira.rejected(parsed.value('--limit').toString() as int)
            case 'reporter':
                return jira.reporter(
                    parsed.variadic('display_name').join(' '),
                    parsed.value('--limit').toString() as int
                )
            case 'tempo':
                return tempo.daily(validDate(parsed.positional('date')))
            case 'verify':
                String date = validDate(parsed.positional('date'))
                return new JiraWorklogVerifier(paths).verify(date, tempo.daily(date))
            case 'whoami':
                return jira.whoami()
            case 'log-time':
                return tempo.logTime(
                    parsed.positional('ticket_key'),
                    validDate(parsed.positional('date')),
                    parsed.positional('seconds') as long,
                    parsed.variadic('comment').join(' '),
                    parsed.flag('--apply')
                )
            case 'assets-schemas':
                return assets.schemas()
            case 'assets-types':
                return assets.types(parsed.positional('schema_id') as int)
            case 'assets-attributes':
                return assets.attributes(parsed.positional('type_id') as int)
            case 'assets-object':
                return assets.object(parsed.positional('object_key'))
            case 'assets-search':
                return assets.search(
                    parsed.variadic('query').join(' '),
                    parsed.value('--limit').toString() as int
                )
            case 'get-ci':
                return assets.ci(parsed.positional('object_key'))
            case 'get-cis':
                return assets.cis(
                    parsed.value('--env')?.toString(),
                    parsed.value('--limit').toString() as int
                )
            default:
                throw new UsageError(
                    "Unknown action for service jira: ${action}",
                    'service jira',
                    action
                )
        }
    }

    private static String validDate(String supplied) {
        String value = supplied ?: LocalDate.now().toString()
        try {
            LocalDate.parse(value)
            value
        } catch (Exception ignored) {
            throw new UsageError("Invalid date: '${value}' (expected YYYY-MM-DD)")
        }
    }
}
