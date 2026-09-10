package ai.worklog.framework.commands

import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.NewRelicAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.newrelic.NewRelicOperatorReport

class NewRelicCommands {
    private static final List<String> MUTATIONS = [
        'alert-condition-create',
        'alert-condition-update',
        'dashboard-create',
        'dashboard-page-create',
        'dashboard-page-update',
        'dashboard-widget-create',
        'dashboard-widget-update'
    ]
    private static final List<String> APPLY_ACTIONS = MUTATIONS + ['dashboard-export']

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
            System.err.print usage.renderPath(['service', 'newrelic'])
            return exitCodes.userError
        }
        Map actionDefinition = contract.node(['service', 'newrelic', action])
        if (!actionDefinition) {
            System.err.println("Unknown action for service newrelic: ${action}")
            System.err.println()
            System.err.print usage.renderPath(['service', 'newrelic'])
            return exitCodes.userError
        }
        List<String> original = new ArrayList<>(args)
        boolean json = original.contains('--json')
        Redaction redaction = new Redaction(frameworkRoot)
        Map rules = NewRelicAdapter.loadOperatorRules(frameworkRoot)
        boolean apply = original.contains('--apply')
        NewRelicAdapter adapter = new NewRelicAdapter(
            paths,
            new ReadOnlyHttp(),
            new JsonWriteHttp(),
            rules,
            frameworkRoot,
            config,
            apply
        )
        Map defaults = buildDefaults(rules)
        ParsedArguments parsed
        try {
            validateGlobalOptions(action, original)
            parsed = new ArgumentParser(contract).parse(
                'service newrelic',
                actionDefinition,
                original,
                defaults
            )
            json = parsed.flag('--json')
            validateMutationOptions(action, parsed)
            validateSemanticRules(action, parsed)
            Map payload = dispatch(action, parsed, adapter)
            NewRelicOperatorReport report = NewRelicOperatorReport.fromPayload(payload)
            print json ? report.renderJson(redaction) : report.renderHuman(redaction)
            return NewRelicOperatorReport.exitCodeFor(report, exitCodes)
        } catch (UsageError exception) {
            System.err.println(exception.message)
            System.err.println()
            System.err.print usage.renderPath(['service', 'newrelic', action])
            if (json) {
                print NewRelicOperatorReport.fromPayload(errorPayload(action, exception.message)).renderJson(redaction)
            }
            return exitCodes.userError
        } catch (IllegalArgumentException exception) {
            if (json) {
                print NewRelicOperatorReport.fromPayload(errorPayload(action, exception.message)).renderJson(redaction)
            } else {
                System.err.println redaction.redact(exception.message)
            }
            return exitCodes.userError
        } catch (IllegalStateException exception) {
            boolean blocked = exception.message?.toLowerCase()?.contains('unavailable')
            Map payload = errorPayload(action, exception.message)
            payload.status = blocked ? Status.BLOCKED : Status.ERROR
            if (json) {
                print NewRelicOperatorReport.fromPayload(payload).renderJson(redaction)
            } else {
                System.err.println redaction.redact(exception.message)
            }
            return blocked ? exitCodes.blocked : exitCodes.systemError
        } catch (Exception exception) {
            String message = "New Relic operation failed: ${exception.message ?: exception.class.simpleName}"
            if (json) {
                print NewRelicOperatorReport.fromPayload(errorPayload(action, message)).renderJson(redaction)
            } else {
                System.err.println(redaction.redact(message))
            }
            return exitCodes.systemError
        }
    }

    private static Map dispatch(String action, ParsedArguments parsed, NewRelicAdapter adapter) {
        Map settings = adapter.settings()
        int readTimeout = settings.read_timeout_seconds as int
        int mutationTimeout = settings.mutation_timeout_seconds as int
        String profile = parsed.value('--profile')?.toString()
        switch (action) {
            case 'profiles':
                return adapter.operatorProfiles()
            case 'auth-test':
                return adapter.operatorAuthTest(profile, readTimeout)
            case 'whoami':
                return adapter.operatorWhoami(profile, readTimeout)
            case 'applications':
                return adapter.operatorApplications(
                    profile,
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.applications_limit as int, adapter.operatorRules, 'applications'),
                    readTimeout
                )
            case 'application':
                return adapter.operatorApplication(profile, parsed.positional('app_id'), readTimeout)
            case 'hosts':
                return adapter.operatorHosts(
                    profile,
                    parsed.positional('app_id'),
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.hosts_limit as int, adapter.operatorRules, 'hosts'),
                    readTimeout
                )
            case 'deployments':
                return adapter.operatorDeployments(
                    profile,
                    parsed.positional('app_id'),
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.deployments_limit as int, adapter.operatorRules, 'deployments'),
                    readTimeout
                )
            case 'violations':
                return adapter.operatorViolations(
                    profile,
                    parsed.value('--policy')?.toString(),
                    parsed.value('--entity')?.toString(),
                    parsed.value('--priority')?.toString() ?: 'all',
                    parseLimit(parsed.value('--limit'), settings.violations_limit as int, adapter.operatorRules, 'violations'),
                    readTimeout
                )
            case 'issues':
                return adapter.operatorIssues(
                    profile,
                    parsed.value('--state')?.toString() ?: 'all',
                    parsed.value('--priority')?.toString() ?: 'all',
                    parsed.value('--entity')?.toString(),
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.issues_limit as int, adapter.operatorRules, 'issues'),
                    readTimeout
                )
            case 'incidents':
                return adapter.operatorIncidents(
                    profile,
                    parsed.value('--issue')?.toString(),
                    parsed.value('--condition')?.toString(),
                    parsed.value('--entity')?.toString(),
                    parsed.value('--since')?.toString(),
                    parsed.value('--until')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.incidents_limit as int, adapter.operatorRules, 'incidents'),
                    readTimeout
                )
            case 'errors':
                return adapter.operatorErrors(
                    profile,
                    parsed.positional('app_id'),
                    parsed.value('--hours') ? parsed.value('--hours').toString() as int : settings.errors_hours as int,
                    parseLimit(parsed.value('--limit'), settings.errors_limit as int, adapter.operatorRules, 'errors_limit'),
                    readTimeout
                )
            case 'nrql':
                return adapter.operatorNrql(
                    profile,
                    parsed.positional('query')?.toString(),
                    parsed.value('--file') ? new File(parsed.value('--file').toString()) : null,
                    parseLimit(parsed.value('--limit'), settings.nrql_rows_limit as int, adapter.operatorRules, 'nrql_rows'),
                    readTimeout
                )
            case 'entities':
                return adapter.operatorEntities(
                    profile,
                    parsed.value('--query')?.toString(),
                    parsed.value('--domain')?.toString(),
                    parsed.value('--type')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.entities_limit as int, adapter.operatorRules, 'entities'),
                    readTimeout
                )
            case 'dashboards':
                return adapter.operatorDashboards(
                    profile,
                    parsed.value('--query')?.toString(),
                    parsed.value('--owner')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.dashboards_limit as int, adapter.operatorRules, 'dashboards'),
                    readTimeout
                )
            case 'dashboard':
                return adapter.operatorDashboard(profile, parsed.positional('dashboard_guid'), readTimeout)
            case 'dashboard-export':
                return adapter.operatorDashboardExport(
                    profile,
                    parsed.positional('dashboard_guid'),
                    parsed.value('--format')?.toString(),
                    parsed.value('--output')?.toString(),
                    parsed.flag('--force'),
                    readTimeout
                )
            case 'alert-policies':
                return adapter.operatorAlertPolicies(
                    profile,
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.alert_policies_limit as int, adapter.operatorRules, 'alert_policies'),
                    readTimeout
                )
            case 'alert-policy':
                return adapter.operatorAlertPolicy(profile, parsed.positional('policy_id'), readTimeout)
            case 'alert-conditions':
                return adapter.operatorAlertConditions(
                    profile,
                    parsed.value('--policy')?.toString(),
                    parsed.value('--entity')?.toString(),
                    parsed.value('--type')?.toString(),
                    parsed.value('--query')?.toString(),
                    parseLimit(parsed.value('--limit'), settings.alert_conditions_limit as int, adapter.operatorRules, 'alert_conditions'),
                    readTimeout
                )
            case 'alert-condition':
                return adapter.operatorAlertCondition(profile, parsed.positional('condition_id'), readTimeout)
            case 'alert-condition-create':
                return adapter.operatorAlertConditionCreate(
                    profile,
                    parsed.positional('policy_id'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-policy')?.toString(),
                    mutationTimeout
                )
            case 'alert-condition-update':
                return adapter.operatorAlertConditionUpdate(
                    profile,
                    parsed.positional('condition_id'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-name')?.toString(),
                    mutationTimeout
                )
            case 'dashboard-create':
                return adapter.operatorDashboardCreate(
                    profile,
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-account')?.toString(),
                    mutationTimeout
                )
            case 'dashboard-page-create':
                return adapter.operatorDashboardPageCreate(
                    profile,
                    parsed.positional('dashboard_guid'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-dashboard')?.toString(),
                    mutationTimeout
                )
            case 'dashboard-page-update':
                return adapter.operatorDashboardPageUpdate(
                    profile,
                    parsed.positional('page_guid'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-name')?.toString(),
                    mutationTimeout
                )
            case 'dashboard-widget-create':
                return adapter.operatorDashboardWidgetCreate(
                    profile,
                    parsed.positional('dashboard_guid'),
                    parsed.positional('page_guid'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-dashboard')?.toString(),
                    mutationTimeout
                )
            case 'dashboard-widget-update':
                return adapter.operatorDashboardWidgetUpdate(
                    profile,
                    parsed.positional('widget_guid'),
                    new File(parsed.positional('definition_file')),
                    parsed.value('--confirm-name')?.toString(),
                    mutationTimeout
                )
            default:
                throw new UsageError("Unknown action for service newrelic: ${action}", 'service newrelic', action)
        }
    }

    private static void validateGlobalOptions(String action, List<String> args) {
        if (args.contains('--apply') && !(action in APPLY_ACTIONS)) {
            throw new UsageError("Unknown option for service newrelic ${action}: --apply", 'service newrelic', action)
        }
        if (args.contains('--force') && action != 'dashboard-export') {
            throw new UsageError("Unknown option for service newrelic ${action}: --force", 'service newrelic', action)
        }
    }

    private static void validateMutationOptions(String action, ParsedArguments parsed) {
        if (action == 'alert-condition-create' && !parsed.value('--confirm-policy')) {
            throw new UsageError('Missing --confirm-policy', 'service newrelic', action)
        }
        if (action == 'alert-condition-update' && !parsed.value('--confirm-name')) {
            throw new UsageError('Missing --confirm-name', 'service newrelic', action)
        }
        if (action == 'dashboard-create' && !parsed.value('--confirm-account')) {
            throw new UsageError('Missing --confirm-account', 'service newrelic', action)
        }
        if (action == 'dashboard-page-create' && !parsed.value('--confirm-dashboard')) {
            throw new UsageError('Missing --confirm-dashboard', 'service newrelic', action)
        }
        if (action == 'dashboard-page-update' && !parsed.value('--confirm-name')) {
            throw new UsageError('Missing --confirm-name', 'service newrelic', action)
        }
        if (action == 'dashboard-widget-create' && !parsed.value('--confirm-dashboard')) {
            throw new UsageError('Missing --confirm-dashboard', 'service newrelic', action)
        }
        if (action == 'dashboard-widget-update' && !parsed.value('--confirm-name')) {
            throw new UsageError('Missing --confirm-name', 'service newrelic', action)
        }
    }

    private static void validateSemanticRules(String action, ParsedArguments parsed) {
        if (action == 'nrql') {
            boolean hasInline = !!parsed.positional('query')?.toString()?.trim()
            boolean hasFile = !!parsed.value('--file')
            if (hasInline == hasFile) {
                throw new UsageError('nrql requires exactly one inline query or --file source', 'service newrelic', action)
            }
        }
        if (action == 'alert-condition-create') {
            String policyId = parsed.positional('policy_id')
            if (parsed.value('--confirm-policy')?.toString() != policyId?.toString()) {
                throw new UsageError('alert-condition-create requires matching --confirm-policy policy id', 'service newrelic', action)
            }
        }
        if (action == 'dashboard-create') {
            String confirmAccount = parsed.value('--confirm-account')?.toString()
            if (confirmAccount && !confirmAccount.matches('^[0-9]+$')) {
                throw new UsageError('Invalid --confirm-account', 'service newrelic', action)
            }
        }
        if (action == 'dashboard-page-create' || action == 'dashboard-widget-create') {
            String dashboardGuid = parsed.positional('dashboard_guid')
            if (parsed.value('--confirm-dashboard')?.toString() != dashboardGuid?.toString()) {
                throw new UsageError("${action} requires matching --confirm-dashboard guid", 'service newrelic', action)
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
            throw new UsageError("Invalid --limit: ${value}", 'service newrelic', kind)
        }
        value
    }

    private static Map buildDefaults(Map rules) {
        Map limits = rules.limits instanceof Map ? (Map) rules.limits : [:]
        [
            limits: limits,
            default_profile: rules.default_profile
        ]
    }

    private static Map errorPayload(String action, String message) {
        [
            operation: action,
            fetched_at: NewRelicAdapter.utcNow(),
            status: Status.ERROR,
            message: message,
            items: []
        ]
    }
}
