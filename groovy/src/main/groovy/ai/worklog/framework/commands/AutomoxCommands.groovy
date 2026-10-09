package ai.worklog.framework.commands

import ai.worklog.framework.adapters.AutomoxAdapter
import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.automox.AutomoxCsvRenderer
import ai.worklog.framework.automox.AutomoxOperatorReport
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status

class AutomoxCommands {
    private static final List<String> MUTATIONS = [
        'device-move', 'policy-add-group', 'policy-run', 'worklet-create', 'policy-delete'
    ]
    private static final List<String> CSV_ACTIONS = ['device-packages', 'patch-summary']

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
            System.err.print usage.renderPath(['service', 'automox'])
            return exitCodes.userError
        }
        Map actionDefinition = contract.node(['service', 'automox', action])
        if (!actionDefinition) {
            System.err.println("Unknown action for service automox: ${action}")
            System.err.println()
            System.err.print usage.renderPath(['service', 'automox'])
            return exitCodes.userError
        }
        List<String> original = new ArrayList<>(args)
        boolean json = original.contains('--json')
        boolean csv = original.contains('--csv')
        Redaction redaction = new Redaction(frameworkRoot)
        Map rules = AutomoxAdapter.loadOperatorRules(frameworkRoot)
        AutomoxAdapter adapter = new AutomoxAdapter(
            paths,
            new ReadOnlyHttp(),
            new JsonWriteHttp(),
            rules,
            frameworkRoot,
            config,
            original.contains('--apply')
        )
        Map defaults = buildDefaults(adapter, rules)
        ParsedArguments parsed
        try {
            validateGlobalOptions(action, original)
            parsed = new ArgumentParser(contract).parse(
                'service automox',
                actionDefinition,
                original,
                defaults
            )
            json = parsed.flag('--json')
            csv = parsed.flag('--csv')
            if (json && csv) {
                throw new UsageError('--json and --csv are mutually exclusive', 'service automox', action)
            }
            if (csv && !(action in CSV_ACTIONS)) {
                throw new UsageError("Unknown option for service automox ${action}: --csv", 'service automox', action)
            }
            validateMutationOptions(action, parsed)
            validateSemanticRules(action, parsed, adapter.apply)
            Map payload = dispatch(action, parsed, adapter)
            AutomoxOperatorReport report = AutomoxOperatorReport.fromPayload(payload)
            if (csv) {
                Map csvColumns = rules.csv_columns instanceof Map ? (Map) rules.csv_columns : [:]
                List<String> columns = action == 'device-packages' ?
                    (List<String>) (csvColumns.device_packages ?: []) :
                    (List<String>) (csvColumns.patch_summary ?: [])
                print AutomoxCsvRenderer.render(columns, report.items)
            } else {
                print json ? report.renderJson(redaction) : report.renderHuman(redaction)
            }
            return AutomoxOperatorReport.exitCodeFor(report, exitCodes)
        } catch (UsageError exception) {
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['service', 'automox', action])
            if (json) {
                AutomoxOperatorReport report = AutomoxOperatorReport.fromPayload(errorPayload(action, exception.message))
                print report.renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            if (json) {
                AutomoxOperatorReport report = AutomoxOperatorReport.fromPayload(errorPayload(action, exception.message))
                print report.renderJson(redaction)
            } else {
                System.err.println exception.message
            }
            return exitCodes.userError
        } catch (IllegalStateException exception) {
            boolean blocked = exception.message?.toLowerCase()?.contains('unavailable')
            Map payload = errorPayload(action, exception.message)
            payload.status = blocked ? Status.BLOCKED : Status.ERROR
            if (json) {
                print AutomoxOperatorReport.fromPayload(payload).renderJson(redaction)
            } else {
                System.err.println exception.message
            }
            return blocked ? exitCodes.blocked : exitCodes.systemError
        } catch (Exception exception) {
            String message = "Automox operation failed: ${exception.message ?: exception.class.simpleName}"
            if (json) {
                print AutomoxOperatorReport.fromPayload(errorPayload(action, message)).renderJson(redaction)
            } else {
                System.err.println(message)
            }
            return exitCodes.systemError
        }
    }

    private static Map dispatch(String action, ParsedArguments parsed, AutomoxAdapter adapter) {
        Map settings = adapter.settings()
        int timeout = settings.timeout_seconds as int
        String profile = parsed.value('--profile')?.toString()
        adapter.selectOrg(parsed.value('--org')?.toString())
        if (action != 'orgs') {
            adapter.validateOrgOverride(profile, timeout)
        }
        switch (action) {
            case 'profiles':
                return adapter.operatorProfiles()
            case 'auth-test':
                return adapter.operatorAuthTest(profile, timeout)
            case 'orgs':
                return adapter.operatorOrgs(profile, timeout)
            case 'groups':
                return adapter.operatorGroups(
                    profile,
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.groups_limit as int, adapter.operatorRules, 'groups'),
                    timeout
                )
            case 'group':
                return adapter.operatorGroup(profile, parsed.positional('group_id'), timeout)
            case 'devices':
                return adapter.operatorDevices(
                    profile,
                    parsed.value('--group')?.toString(),
                    parsed.value('--query')?.toString(),
                    parsed.value('--state')?.toString() ?: 'all',
                    parseLimit(parsed.value('--limit'), settings.devices_limit as int, adapter.operatorRules, 'devices'),
                    timeout
                )
            case 'device':
                return adapter.operatorDevice(profile, parsed.positional('device'), timeout)
            case 'device-packages':
                return adapter.operatorDevicePackages(
                    profile,
                    parsed.positional('device'),
                    parsed.value('--state')?.toString() ?: 'all',
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.packages_limit as int, adapter.operatorRules, 'packages'),
                    timeout,
                    parsed.value('--page') ? parsed.value('--page').toString() as int : 1
                )
            case 'activity':
                return adapter.operatorActivity(
                    profile,
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parsed.values('--event'),
                    parsed.value('--device')?.toString(),
                    parsed.value('--policy')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.activity_limit as int, adapter.operatorRules, 'activity'),
                    timeout
                )
            case 'patch-summary':
                return adapter.operatorPatchSummary(
                    profile,
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parsed.value('--device')?.toString(),
                    parsed.value('--policy')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.patch_summary_limit as int, adapter.operatorRules, 'patch_summary'),
                    timeout
                )
            case 'policies':
                return adapter.operatorPolicies(
                    profile,
                    parsed.value('--query')?.toString(),
                    parsed.value('--type')?.toString() ?: 'all',
                    parseLimit(parsed.value('--limit'), settings.policies_limit as int, adapter.operatorRules, 'policies'),
                    timeout
                )
            case 'policy':
                return adapter.operatorPolicy(profile, parsed.positional('policy_id'), timeout)
            case 'policy-stats':
                return adapter.operatorPolicyStats(profile, parsed.value('--policy')?.toString(), timeout)
            case 'device-queue':
                return adapter.operatorDeviceQueue(
                    profile,
                    parsed.positional('device'),
                    parsed.value('--policy')?.toString(),
                    parsed.value('--status')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.queue_limit as int, adapter.operatorRules, 'queue'),
                    parsed.value('--wait') ? parsed.value('--wait').toString() as int : 0,
                    timeout
                )
            case 'policy-run':
                return adapter.operatorPolicyRun(
                    profile,
                    parsed.positional('policy_id'),
                    parsed.value('--device')?.toString(),
                    parsed.flag('--all'),
                    parsed.value('--confirm-all')?.toString(),
                    timeout
                )
            case 'worklet-create':
                return adapter.operatorWorkletCreate(
                    profile,
                    parsed.positional('name'),
                    new File(parsed.positional('evaluation_file')),
                    new File(parsed.positional('remediation_file')),
                    parsed.value('--notes')?.toString(),
                    timeout
                )
            case 'policy-delete':
                return adapter.operatorPolicyDelete(
                    profile,
                    parsed.positional('policy_id'),
                    parsed.value('--confirm-name')?.toString(),
                    timeout
                )
            case 'device-move':
                return adapter.operatorDeviceMove(
                    profile,
                    parsed.positional('device'),
                    parsed.positional('group_id'),
                    timeout
                )
            case 'policy-add-group':
                return adapter.operatorPolicyAddGroup(
                    profile,
                    parsed.positional('policy_id'),
                    parsed.positional('group_id'),
                    timeout
                )
            default:
                throw new UsageError("Unknown action for service automox: ${action}", 'service automox', action)
        }
    }

    private static void validateGlobalOptions(String action, List<String> args) {
        if (args.contains('--apply') && !(action in MUTATIONS)) {
            throw new UsageError("Unknown option for service automox ${action}: --apply", 'service automox', action)
        }
    }

    private static void validateMutationOptions(String action, ParsedArguments parsed) {
        if (!(action in MUTATIONS)) {
            return
        }
        if (action == 'policy-delete' && !parsed.value('--confirm-name')) {
            throw new UsageError('Missing --confirm-name', 'service automox', action)
        }
    }

    private static void validateSemanticRules(String action, ParsedArguments parsed, boolean apply) {
        if (action == 'policy-run') {
            boolean hasDevice = !!parsed.value('--device')
            boolean hasAll = parsed.flag('--all')
            if (hasDevice == hasAll) {
                throw new UsageError('policy-run requires exactly one of --device or --all', 'service automox', action)
            }
            if (hasAll && apply && parsed.value('--confirm-all')?.toString() != parsed.positional('policy_id')) {
                throw new UsageError('policy-run --all requires matching --confirm-all policy id', 'service automox', action)
            }
        }
    }

    private static int parseLimit(Object supplied, int defaultValue, Map rules, String kind) {
        Map limits = rules.limits instanceof Map ? (Map) rules.limits : [:]
        String maxKey = "${kind}_max"
        int max = (limits[maxKey] ?: defaultValue) as int
        if (!supplied) {
            return defaultValue
        }
        int value = supplied.toString() as int
        if (value > max) {
            throw new UsageError("Invalid --limit: ${value}", 'service automox', kind)
        }
        value
    }

    private static Map buildDefaults(AutomoxAdapter adapter, Map rules) {
        Map settings = adapter.settings()
        Map limits = rules.limits instanceof Map ? (Map) rules.limits : [:]
        [
            limits: limits,
            default_profile: settings.default_profile,
            devices_limit: settings.devices_limit,
            groups_limit: settings.groups_limit,
            policies_limit: settings.policies_limit,
            packages_limit: settings.packages_limit,
            activity_limit: settings.activity_limit,
            patch_summary_limit: settings.patch_summary_limit,
            queue_limit: settings.queue_limit
        ]
    }

    private static Map errorPayload(String action, String message) {
        [
            operation: action,
            fetched_at: AutomoxAdapter.utcNow(),
            status: Status.ERROR,
            message: message,
            items: []
        ]
    }
}
