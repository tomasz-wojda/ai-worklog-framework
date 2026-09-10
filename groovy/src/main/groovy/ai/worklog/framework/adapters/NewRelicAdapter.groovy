package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import ai.worklog.framework.newrelic.NewRelicTerraformRenderer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class NewRelicAdapter {
    final FrameworkPaths paths
    final ReadOnlyHttp http
    final NewRelicGraphqlClient graphql
    final Map overrides
    final File frameworkRoot
    final Map config
    final boolean apply
    final Map operatorRules
    final JsonSlurper slurper = new JsonSlurper()

    NewRelicAdapter(
        FrameworkPaths paths,
        ReadOnlyHttp http,
        JsonWriteHttp writeHttp,
        Map overrides = [:],
        File frameworkRoot = null,
        Map config = [:],
        boolean apply = false
    ) {
        this.paths = paths
        this.http = http
        this.operatorRules = frameworkRoot ? loadOperatorRules(frameworkRoot) : overrides ?: [:]
        List<String> mutations = operatorRules.graphql_mutations instanceof List ?
            (List<String>) operatorRules.graphql_mutations : []
        List<Integer> readCodes = operatorRules.success_codes?.read instanceof List ?
            (List<Integer>) operatorRules.success_codes.read : [200]
        List<Integer> mutationCodes = operatorRules.success_codes?.mutation instanceof List ?
            (List<Integer>) operatorRules.success_codes.mutation : [200]
        this.graphql = new NewRelicGraphqlClient(writeHttp ?: new JsonWriteHttp(), apply, mutations, readCodes, mutationCodes)
        this.overrides = overrides ?: [:]
        this.frameworkRoot = frameworkRoot
        this.config = config ?: [:]
        this.apply = apply
    }

    Map settings() {
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map newrelic = adapters.newrelic instanceof Map ? (Map) adapters.newrelic : [:]
        Map limits = operatorRules.limits instanceof Map ? (Map) operatorRules.limits : [:]
        [
            read_timeout_seconds: (newrelic.read_timeout_seconds ?: operatorRules.timeouts?.read_seconds ?: 15) as int,
            mutation_timeout_seconds: (newrelic.mutation_timeout_seconds ?:
                operatorRules.timeouts?.mutation_seconds ?: 30) as int,
            rest_base_url: newrelic.rest_base_url?.toString() ?: operatorRules.rest_base_url?.toString(),
            graphql_url: newrelic.graphql_url?.toString() ?: operatorRules.graphql_url?.toString(),
            default_profile: newrelic.default_profile?.toString() ?: operatorRules.default_profile?.toString(),
            applications_limit: (newrelic.applications_limit ?: limits.applications_default ?: 500) as int,
            hosts_limit: (newrelic.hosts_limit ?: limits.hosts_default ?: 500) as int,
            deployments_limit: (newrelic.deployments_limit ?: limits.deployments_default ?: 500) as int,
            violations_limit: (newrelic.violations_limit ?: limits.violations_default ?: 500) as int,
            issues_limit: (newrelic.issues_limit ?: limits.issues_default ?: 200) as int,
            incidents_limit: (newrelic.incidents_limit ?: limits.incidents_default ?: 200) as int,
            errors_hours: (newrelic.errors_hours ?: limits.errors_hours_default ?: 1) as int,
            errors_limit: (newrelic.errors_limit ?: limits.errors_limit_default ?: 50) as int,
            nrql_rows_limit: (newrelic.nrql_rows_limit ?: limits.nrql_rows_default ?: 500) as int,
            entities_limit: (newrelic.entities_limit ?: limits.entities_default ?: 500) as int,
            dashboards_limit: (newrelic.dashboards_limit ?: limits.dashboards_default ?: 500) as int,
            alert_policies_limit: (newrelic.alert_policies_limit ?: limits.alert_policies_default ?: 500) as int,
            alert_conditions_limit: (newrelic.alert_conditions_limit ?: limits.alert_conditions_default ?: 500) as int,
            response_body_max: (limits.response_body_max_characters ?: 4194304) as int,
            error_body_max: (limits.error_body_max_characters ?: 8192) as int,
            definition_file_max: (newrelic.definition_file_max_bytes ?:
                limits.definition_file_max_bytes ?: 1048576) as long,
            rest_max_pages: (operatorRules.pagination?.rest_max_pages ?: 100) as int,
            entity_cursor_max_pages: (operatorRules.pagination?.entity_cursor_max_pages ?: 100) as int
        ]
    }

    Map operatorProfiles() {
        String fetchedAt = utcNow()
        File propertiesFile = new File(paths.serviceDir('newrelic'), 'newrelic.properties')
        List<Map> items = NewRelicCredentials.publicProfiles(paths, config, operatorRules)
        if (!propertiesFile.isFile() && !items.any { it.has_api_key }) {
            return report('profiles', Status.BLOCKED, [],
                [fetched_at: fetchedAt, message: 'No newrelic.properties found or no profiles configured'])
        }
        report('profiles', Status.READY, items, [fetched_at: fetchedAt])
    }

    Map operatorAuthTest(String profile, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        try {
            Map response = graphqlQuery(credentials, userQuery(), [:], timeout)
            Map user = dig(response.data, ['actor', 'user']) ?: [:]
            if (user) {
                return report('auth-test', Status.READY, [[authenticated: true]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    message: 'New Relic authentication succeeded'
                ])
            }
            report('auth-test', Status.DEGRADED, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                message: 'NerdGraph user query returned no data'
            ])
        } catch (Exception exception) {
            String message = exception.message ?: exception.class.simpleName
            if (message.contains('401') || message.contains('403')) {
                return report('auth-test', Status.BLOCKED, [], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    message: message
                ])
            }
            report('auth-test', Status.DEGRADED, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                message: message
            ])
        }
    }

    Map operatorWhoami(String profile, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map response = graphqlQuery(credentials, userQuery(), [:], timeout)
        Map user = dig(response.data, ['actor', 'user']) ?: [:]
        if (!user) {
            return report('whoami', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                message: 'Authenticated user not found'
            ])
        }
        report('whoami', Status.READY, [[
            id: user.id, email: user.email, name: user.name
        ]], [fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId])
    }

    Map operatorApplications(String profile, String query, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        List apps = fetchRestCollection(credentials, 'applications', timeout, [:], 'applications')
        List<Map> items = apps.collect { normalizeApplication(it) }
        items = filterByQuery(items, 'name', query)
        items = sortByName(items)
        Map truncatedResult = dedupeAndTruncate(items, { it.id?.toString() ?: it.name?.toString() }, limit)
        report('applications', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            query: query, truncated: truncatedResult.truncated,
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorApplication(String profile, String appId, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map response = restGet(credentials, 'application', timeout, [id: appId], [:])
        if (response.code == 404) {
            return report('application', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                application: appId, message: 'Application not found'
            ])
        }
        validateRestSuccess(response)
        Object parsed = parseJson(response.body)
        Map app = parsed instanceof Map ? (Map) (parsed.application ?: parsed) : [:]
        report('application', Status.READY, [normalizeApplication(app)], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            application: appId
        ])
    }

    Map operatorHosts(String profile, String appId, String query, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        List hosts = fetchRestCollection(credentials, 'application_hosts', timeout, [id: appId], 'application_hosts')
        List<Map> items = hosts.collect { normalizeHost(it) }
        items = filterByQuery(items, 'host', query)
        items = sortByName(items, 'host')
        Map truncatedResult = dedupeAndTruncate(items, { it.id?.toString() ?: it.host?.toString() }, limit)
        report('hosts', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            application: appId, query: query, truncated: truncatedResult.truncated,
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorDeployments(
        String profile,
        String appId,
        String since,
        String until,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Instant sinceInstant = parseDateBoundary(since, true)
        Instant untilInstant = parseDateBoundary(until, false)
        List deployments = fetchRestCollection(credentials, 'application_deployments', timeout, [id: appId], 'deployments')
        List<Map> items = deployments.collect { normalizeDeployment(it) }
        items = items.findAll { row ->
            Instant ts = parseInstant(row.timestamp)
            ts && !ts.isBefore(sinceInstant) && !ts.isAfter(untilInstant)
        }
        items = items.sort { a, b -> (b.timestamp ?: '') <=> (a.timestamp ?: '') }
        Map truncatedResult = dedupeAndTruncate(items, { it.id?.toString() ?: "${it.timestamp}:${it.revision}" }, limit)
        report('deployments', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            application: appId, truncated: truncatedResult.truncated,
            filters: effectiveDateFilters(since, until, sinceInstant, untilInstant),
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorViolations(
        String profile,
        String policyId,
        String entity,
        String priority,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map query = [only_open: 'true']
        if (policyId) {
            query.policy_id = policyId
        }
        List violations = fetchRestCollection(credentials, 'violations', timeout, [:], 'violations', query)
        List<Map> items = violations.collect { normalizeViolation(it) }
        if (entity) {
            String lower = entity.toLowerCase()
            items = items.findAll {
                (it.entity_name ?: '').toString().toLowerCase().contains(lower) ||
                    (it.entity_label ?: '').toString().toLowerCase().contains(lower)
            }
        }
        if (priority && priority != 'all') {
            items = items.findAll { (it.priority ?: '').toString().equalsIgnoreCase(priority) }
        }
        items = items.sort { a, b -> (b.opened_at ?: '') <=> (a.opened_at ?: '') }
        Map truncatedResult = dedupeAndTruncate(items, { it.id?.toString() }, limit)
        report('violations', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            policy: policyId, truncated: truncatedResult.truncated,
            filters: [entity: entity, priority: priority ?: 'all'],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorIssues(
        String profile,
        String state,
        String priority,
        String entity,
        String since,
        String until,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Instant sinceInstant = parseDateBoundary(since, true)
        Instant untilInstant = parseDateBoundary(until, false)
        List<Map> items = fetchAiIssues(credentials, timeout, state)
        if (priority && priority != 'all') {
            items = items.findAll { (it.priority ?: '').toString().equalsIgnoreCase(priority) }
        }
        if (entity) {
            String lower = entity.toLowerCase()
            items = items.findAll {
                (it.entity_name ?: '').toString().toLowerCase().contains(lower) ||
                    (it.title ?: '').toString().toLowerCase().contains(lower)
            }
        }
        items = items.findAll { row ->
            Instant ts = parseInstant(row.updated_at ?: row.created_at)
            !ts || (!ts.isBefore(sinceInstant) && !ts.isAfter(untilInstant))
        }
        items = items.sort { a, b -> (b.updated_at ?: b.created_at ?: '') <=> (a.updated_at ?: a.created_at ?: '') }
        Map truncatedResult = dedupeAndTruncate(items, { it.issue_id?.toString() ?: it.id?.toString() }, limit)
        report('issues', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            truncated: truncatedResult.truncated,
            filters: effectiveDateFilters(since, until, sinceInstant, untilInstant) +
                [state: state ?: 'all', priority: priority ?: 'all', entity: entity],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorIncidents(
        String profile,
        String issueId,
        String conditionId,
        String entity,
        String since,
        String until,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Instant sinceInstant = parseDateBoundary(since, true)
        Instant untilInstant = parseDateBoundary(until, false)
        List<Map> items = fetchAiIncidents(credentials, timeout, issueId, conditionId)
        if (entity) {
            String lower = entity.toLowerCase()
            items = items.findAll {
                (it.entity_name ?: '').toString().toLowerCase().contains(lower) ||
                    (it.title ?: '').toString().toLowerCase().contains(lower)
            }
        }
        items = items.findAll { row ->
            Instant ts = parseInstant(row.updated_at ?: row.created_at)
            !ts || (!ts.isBefore(sinceInstant) && !ts.isAfter(untilInstant))
        }
        items = items.sort { a, b -> (b.updated_at ?: b.created_at ?: '') <=> (a.updated_at ?: a.created_at ?: '') }
        Map truncatedResult = dedupeAndTruncate(items, { it.incident_id?.toString() ?: it.id?.toString() }, limit)
        report('incidents', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            truncated: truncatedResult.truncated,
            filters: effectiveDateFilters(since, until, sinceInstant, untilInstant) +
                [issue: issueId, condition: conditionId, entity: entity],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorErrors(String profile, String appId, int hours, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map facets = operatorRules.errors_facets instanceof Map ? (Map) operatorRules.errors_facets : [:]
        int classLimit = Math.min(limit, (facets.class_limit ?: 25) as int)
        int messageLimit = Math.min(limit, (facets.message_limit ?: 50) as int)
        int txLimit = Math.min(limit, (facets.transaction_limit ?: 25) as int)
        int hostLimit = Math.min(limit, (facets.host_limit ?: 25) as int)
        String account = credentials.accountId
        String operation = '''
            query Errors($accountId: Int!, $summary: Nrql!, $byClass: Nrql!, $byMessage: Nrql!, $byTx: Nrql!, $byHost: Nrql!, $timeline: Nrql!) {
              actor {
                account(id: $accountId) {
                  errSummary: nrql(query: $summary) { results }
                  errByClass: nrql(query: $byClass) { results }
                  errByMessage: nrql(query: $byMessage) { results }
                  errByTransaction: nrql(query: $byTx) { results }
                  errByHost: nrql(query: $byHost) { results }
                  errTimeline: nrql(query: $timeline) { results }
                }
              }
            }
        '''.stripIndent().trim()
        Map variables = [
            accountId: account as int,
            summary: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} SINCE ${hours} HOUR AGO",
            byClass: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} FACET error.class SINCE ${hours} HOUR AGO LIMIT ${classLimit}",
            byMessage: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} FACET error.class, error.message SINCE ${hours} HOUR AGO LIMIT ${messageLimit}",
            byTx: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} FACET transactionName SINCE ${hours} HOUR AGO LIMIT ${txLimit}",
            byHost: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} FACET host SINCE ${hours} HOUR AGO LIMIT ${hostLimit}",
            timeline: "SELECT count(*) FROM TransactionError WHERE appId = ${appId} TIMESERIES 1 hour SINCE ${hours} HOUR AGO"
        ]
        Map response = graphqlQuery(credentials, operation, variables, timeout)
        Map accountNode = dig(response.data, ['actor', 'account']) ?: [:]
        Map item = normalizeErrors(accountNode, appId, hours)
        report('errors', Status.READY, [item], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            application: appId,
            filters: [hours: hours],
            totals: [total_errors: item.total_errors ?: 0]
        ])
    }

    Map operatorNrql(String profile, String inlineQuery, File nrqlFile, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        boolean hasInline = inlineQuery?.trim()
        boolean hasFile = nrqlFile != null
        if (hasInline == hasFile) {
            throw new IllegalArgumentException('nrql requires exactly one inline query or --file source')
        }
        String queryText
        Map definitionMeta = null
        if (hasFile) {
            Map loaded = validateDefinitionFile(nrqlFile, false)
            definitionMeta = loaded.subMap(['path', 'size_bytes', 'sha256'])
            queryText = loaded.content
        } else {
            queryText = inlineQuery
        }
        String operation = '''
            query Nrql($accountId: Int!, $nrql: Nrql!) {
              actor {
                account(id: $accountId) {
                  nrql(query: $nrql) {
                    results
                    metadata {
                      timeWindow { begin end }
                      facets
                    }
                  }
                }
              }
            }
        '''.stripIndent().trim()
        Map variables = [accountId: credentials.accountId as int, nrql: queryText]
        Map response = graphqlQuery(credentials, operation, variables, timeout)
        List rows = dig(response.data, ['actor', 'account', 'nrql', 'results']) ?: []
        List<Map> items = rows.collect { row -> row instanceof Map ? new LinkedHashMap((Map) row) : [value: row] }
        Map truncatedResult = dedupeAndTruncate(items, { JsonOutput.toJson(it) }, limit)
        Map extras = [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            truncated: truncatedResult.truncated,
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ]
        if (definitionMeta) {
            extras.definition = definitionMeta
        } else {
            extras.query = queryText
        }
        report('nrql', Status.READY, truncatedResult.items, extras)
    }

    Map operatorEntities(String profile, String query, String domain, String type, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        String searchQuery = buildEntitySearchQuery(query, domain, type)
        List<Map> items = fetchEntitySearch(credentials, searchQuery, timeout)
        items = sortByName(items)
        Map truncatedResult = dedupeAndTruncate(items, { it.guid?.toString() }, limit)
        report('entities', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            query: searchQuery, truncated: truncatedResult.truncated,
            filters: [domain: domain, type: type],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorDashboards(String profile, String query, String owner, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        String searchQuery = "type = 'DASHBOARD'"
        if (query?.trim()) {
            searchQuery += " AND name LIKE '%${escapeNrqlLiteral(query.trim())}%'"
        }
        List<Map> items = fetchEntitySearch(credentials, searchQuery, timeout)
        if (owner?.trim()) {
            String lowerOwner = owner.trim().toLowerCase()
            items = items.findAll { (it.owner_email ?: it.created_by ?: '').toString().toLowerCase() == lowerOwner }
        }
        items = sortByName(items)
        Map truncatedResult = dedupeAndTruncate(items, { it.guid?.toString() }, limit)
        report('dashboards', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            query: query, truncated: truncatedResult.truncated,
            filters: [owner: owner],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorDashboard(String profile, String dashboardGuid, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map dashboard = fetchDashboardDetail(credentials, dashboardGuid, timeout)
        if (!dashboard) {
            return report('dashboard', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                dashboard: dashboardGuid, message: 'Dashboard not found'
            ])
        }
        report('dashboard', Status.READY, [dashboard], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            dashboard: dashboardGuid
        ])
    }

    Map operatorDashboardExport(
        String profile,
        String dashboardGuid,
        String format,
        String outputPath,
        boolean force,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map dashboard = fetchDashboardDetail(credentials, dashboardGuid, timeout)
        if (!dashboard) {
            return report('dashboard-export', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                dashboard: dashboardGuid, message: 'Dashboard not found'
            ])
        }
        String exportFormat = format ?: 'json'
        Map change = [dry_run: !apply, applied: false, steps: []]
        Map outputSummary = [format: exportFormat]
        List<Map> items = []
        if (exportFormat == 'terraform') {
            String resourceName = NewRelicTerraformRenderer.sanitizeResourceName(dashboard.name?.toString())
            Map<String, String> files = NewRelicTerraformRenderer.render(dashboard, credentials.accountId, resourceName)
            if (outputPath?.trim()) {
                Path targetDir = resolveWorkspaceOutput(outputPath)
                List<Map> fileSummaries = []
                files.each { name, content ->
                    File destination = new File(targetDir.toFile(), name)
                    Map fileMeta = fileMetadata(destination, content)
                    fileSummaries << fileMeta
                    change.steps << [action: 'write', path: fileMeta.path, size_bytes: fileMeta.size_bytes]
                    if (apply) {
                        writeAtomicText(destination, content, force)
                    }
                }
                outputSummary.files = fileSummaries
                outputSummary.path = workspaceRelative(targetDir)
                change.applied = apply
            } else {
                items = [[dashboard: dashboard.name, guid: dashboard.guid, format: exportFormat]]
            }
        } else {
            items = [[dashboard: dashboard]]
            if (outputPath?.trim()) {
                Path target = resolveWorkspaceOutputFile(outputPath, 'dashboard.json')
                String content = JsonOutput.prettyPrint(JsonOutput.toJson(dashboard)) + System.lineSeparator()
                Map fileMeta = fileMetadata(target.toFile(), content)
                outputSummary.path = fileMeta.path
                outputSummary.size_bytes = fileMeta.size_bytes
                outputSummary.sha256 = fileMeta.sha256
                change.steps << [action: 'write', path: fileMeta.path, size_bytes: fileMeta.size_bytes]
                if (apply) {
                    writeAtomicText(target.toFile(), content, force)
                    change.applied = true
                }
            }
        }
        report('dashboard-export', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            dashboard: dashboardGuid, dry_run: !apply, applied: change.applied as boolean,
            change: change, output: outputSummary
        ])
    }

    Map operatorAlertPolicies(String profile, String query, int limit, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        List policies = fetchRestCollection(credentials, 'alert_policies', timeout, [:], 'policies')
        List<Map> items = policies.collect { normalizeAlertPolicy(it) }
        items = filterByQuery(items, 'name', query)
        items = sortByName(items)
        Map truncatedResult = dedupeAndTruncate(items, { it.id?.toString() }, limit)
        report('alert-policies', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            query: query, truncated: truncatedResult.truncated,
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorAlertPolicy(String profile, String policyId, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map response = restGet(credentials, 'alert_policy', timeout, [id: policyId], [:])
        if (response.code == 404) {
            return report('alert-policy', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                policy: policyId, message: 'Alert policy not found'
            ])
        }
        validateRestSuccess(response)
        Object parsed = parseJson(response.body)
        Map policy = parsed instanceof Map ? (Map) (parsed.policy ?: parsed) : [:]
        List<Map> conditions = fetchConditionsForPolicy(credentials, policyId, timeout)
        Map item = normalizeAlertPolicy(policy)
        item.conditions = conditions
        report('alert-policy', Status.READY, [item], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            policy: policyId, totals: [conditions: conditions.size()]
        ])
    }

    Map operatorAlertConditions(
        String profile,
        String policyId,
        String entityGuid,
        String type,
        String query,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        List<Map> items = []
        if (policyId) {
            items.addAll(fetchConditionsForPolicy(credentials, policyId, timeout))
        } else {
            List policies = fetchRestCollection(credentials, 'alert_policies', timeout, [:], 'policies')
            policies.each { Map policy ->
                items.addAll(fetchConditionsForPolicy(credentials, policy.id?.toString(), timeout))
            }
        }
        if (type && type != 'all') {
            items = items.findAll { (it.type ?: '').toString().equalsIgnoreCase(type) }
        }
        if (entityGuid) {
            items = items.findAll { (it.entity_guid ?: '').toString() == entityGuid.toString() }
        }
        items = filterByQuery(items, 'name', query)
        items = sortByName(items)
        Map truncatedResult = dedupeAndTruncate(items, { "${it.type}:${it.id}" }, limit)
        report('alert-conditions', Status.READY, truncatedResult.items, [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            policy: policyId, query: query, truncated: truncatedResult.truncated,
            filters: [entity: entityGuid, type: type ?: 'all'],
            totals: [matched: truncatedResult.total, returned: truncatedResult.items.size()]
        ])
    }

    Map operatorAlertCondition(String profile, String conditionId, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map condition = fetchConditionById(credentials, conditionId, timeout)
        if (!condition) {
            return report('alert-condition', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                condition: conditionId, message: 'Alert condition not found'
            ])
        }
        report('alert-condition', Status.READY, [condition], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            condition: conditionId, policy: condition.policy_id?.toString()
        ])
    }

    Map operatorAlertConditionCreate(
        String profile,
        String policyId,
        File definitionFile,
        String confirmPolicy,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateAlertConditionDefinition(loaded.contentMap)
        if (confirmPolicy != policyId.toString()) {
            throw new IllegalArgumentException('alert-condition-create requires matching --confirm-policy policy id')
        }
        Map target = normalizeAlertConditionTarget(definition, policyId)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'alertsNrqlConditionStaticCreate', policy_id: policyId, name: definition.name
        ]]]
        String createdId = null
        if (apply) {
            String operation = '''
                mutation CreateCondition($accountId: Int!, $policyId: ID!, $condition: AlertsNrqlConditionStaticInput!) {
                  alertsNrqlConditionStaticCreate(accountId: $accountId, policyId: $policyId, condition: $condition) {
                    id
                    name
                    enabled
                  }
                }
            '''.stripIndent().trim()
            Map variables = [
                accountId: credentials.accountId as int,
                policyId: policyId,
                condition: buildConditionInput(definition)
            ]
            Map response = graphqlMutate(credentials, 'alertsNrqlConditionStaticCreate', operation, variables, timeout)
            Map created = dig(response.data, ['alertsNrqlConditionStaticCreate']) ?: [:]
            createdId = created.id?.toString()
            if (!createdId) {
                throw new IllegalStateException('Created alert condition id missing from mutation response')
            }
            Map verified = fetchConditionById(credentials, createdId, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('alert-condition-create', Status.ERROR, [[id: createdId]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    policy: policyId, dry_run: false, applied: false, change: change,
                    message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('alert-condition-create', Status.READY, [[
            id: createdId, name: definition.name, policy_id: policyId
        ]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            policy: policyId, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorAlertConditionUpdate(
        String profile,
        String conditionId,
        File definitionFile,
        String confirmName,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map existing = fetchConditionById(credentials, conditionId, timeout)
        if (!existing) {
            return report('alert-condition-update', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                condition: conditionId, message: 'Alert condition not found'
            ])
        }
        if (confirmName != existing.name?.toString()) {
            throw new IllegalArgumentException('alert-condition-update requires exact current condition name confirmation')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateAlertConditionDefinition(loaded.contentMap)
        Map target = normalizeAlertConditionTarget(definition, existing.policy_id?.toString())
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'alertsNrqlConditionStaticUpdate', condition_id: conditionId, name: definition.name
        ]]]
        if (apply) {
            String operation = '''
                mutation UpdateCondition($accountId: Int!, $id: ID!, $condition: AlertsNrqlConditionStaticInput!) {
                  alertsNrqlConditionStaticUpdate(accountId: $accountId, id: $id, condition: $condition) {
                    id
                    name
                    enabled
                  }
                }
            '''.stripIndent().trim()
            Map variables = [
                accountId: credentials.accountId as int,
                id: conditionId,
                condition: buildConditionInput(definition)
            ]
            graphqlMutate(credentials, 'alertsNrqlConditionStaticUpdate', operation, variables, timeout)
            Map verified = fetchConditionById(credentials, conditionId, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('alert-condition-update', Status.ERROR, [[id: conditionId]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    condition: conditionId, dry_run: false, applied: false, change: change,
                    message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('alert-condition-update', Status.READY, [[
            id: conditionId, name: definition.name, policy_id: existing.policy_id
        ]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            condition: conditionId, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorDashboardCreate(String profile, File definitionFile, String confirmAccount, int timeout) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        if (confirmAccount != credentials.accountId.toString()) {
            throw new IllegalArgumentException('dashboard-create requires matching --confirm-account account id')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateDashboardDefinition(loaded.contentMap, 'dashboard')
        Map target = normalizeDashboardTarget(definition)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'dashboardCreate', name: definition.name
        ]]]
        String createdGuid = null
        if (apply) {
            String operation = '''
                mutation CreateDashboard($accountId: Int!, $dashboard: DashboardCreateInput!) {
                  dashboardCreate(accountId: $accountId, dashboard: $dashboard) {
                    entityResult { guid name }
                    errors { description type }
                  }
                }
            '''.stripIndent().trim()
            Map variables = [accountId: credentials.accountId as int, dashboard: buildDashboardCreateInput(definition)]
            Map response = graphqlMutate(credentials, 'dashboardCreate', operation, variables, timeout)
            Map result = dig(response.data, ['dashboardCreate']) ?: [:]
            validateMutationErrors(result.errors)
            createdGuid = result.entityResult?.guid?.toString()
            if (!createdGuid) {
                throw new IllegalStateException('Created dashboard guid missing from mutation response')
            }
            Map verified = fetchDashboardDetail(credentials, createdGuid, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('dashboard-create', Status.ERROR, [[guid: createdGuid]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    dry_run: false, applied: false, change: change, message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('dashboard-create', Status.READY, [[guid: createdGuid, name: definition.name]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            dry_run: !apply, applied: change.applied as boolean, change: change,
            definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorDashboardPageCreate(
        String profile,
        String dashboardGuid,
        File definitionFile,
        String confirmDashboard,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        if (confirmDashboard != dashboardGuid.toString()) {
            throw new IllegalArgumentException('dashboard-page-create requires matching --confirm-dashboard guid')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateDashboardDefinition(loaded.contentMap, 'page')
        Map target = normalizePageTarget(definition)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'dashboardCreatePage', dashboard: dashboardGuid, name: definition.name
        ]]]
        String createdGuid = null
        if (apply) {
            String operation = '''
                mutation CreatePage($dashboardGuid: ID!, $page: DashboardPageCreateInput!) {
                  dashboardCreatePage(dashboardGuid: $dashboardGuid, page: $page) {
                    entityResult { guid name }
                    errors { description type }
                  }
                }
            '''.stripIndent().trim()
            Map variables = [dashboardGuid: dashboardGuid, page: buildPageInput(definition)]
            Map response = graphqlMutate(credentials, 'dashboardCreatePage', operation, variables, timeout)
            Map result = dig(response.data, ['dashboardCreatePage']) ?: [:]
            validateMutationErrors(result.errors)
            createdGuid = result.entityResult?.guid?.toString()
            if (!createdGuid) {
                throw new IllegalStateException('Created dashboard page guid missing from mutation response')
            }
            Map verified = fetchPageDetail(credentials, dashboardGuid, createdGuid, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('dashboard-page-create', Status.ERROR, [[guid: createdGuid]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    dashboard: dashboardGuid, dry_run: false, applied: false, change: change,
                    message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('dashboard-page-create', Status.READY, [[guid: createdGuid, name: definition.name]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            dashboard: dashboardGuid, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorDashboardPageUpdate(
        String profile,
        String pageGuid,
        File definitionFile,
        String confirmName,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map existing = fetchPageByGuid(credentials, pageGuid, timeout)
        if (!existing) {
            return report('dashboard-page-update', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                page: pageGuid, message: 'Dashboard page not found'
            ])
        }
        if (confirmName != existing.name?.toString()) {
            throw new IllegalArgumentException('dashboard-page-update requires exact current page name confirmation')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateDashboardDefinition(loaded.contentMap, 'page')
        Map target = normalizePageTarget(definition)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'dashboardUpdatePage', page: pageGuid, name: definition.name
        ]]]
        if (apply) {
            String operation = '''
                mutation UpdatePage($guid: EntityGuid!, $page: DashboardUpdatePageInput!) {
                  dashboardUpdatePage(guid: $guid, page: $page) {
                    entityResult { guid name }
                    errors { description type }
                  }
                }
            '''.stripIndent().trim()
            Map variables = [guid: pageGuid, page: buildPageInput(definition)]
            Map response = graphqlMutate(credentials, 'dashboardUpdatePage', operation, variables, timeout)
            Map result = dig(response.data, ['dashboardUpdatePage']) ?: [:]
            validateMutationErrors(result.errors)
            Map verified = fetchPageByGuid(credentials, pageGuid, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('dashboard-page-update', Status.ERROR, [[guid: pageGuid]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    page: pageGuid, dry_run: false, applied: false, change: change,
                    message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('dashboard-page-update', Status.READY, [[guid: pageGuid, name: definition.name]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            page: pageGuid, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorDashboardWidgetCreate(
        String profile,
        String dashboardGuid,
        String pageGuid,
        File definitionFile,
        String confirmDashboard,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        if (confirmDashboard != dashboardGuid.toString()) {
            throw new IllegalArgumentException('dashboard-widget-create requires matching --confirm-dashboard guid')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateDashboardDefinition(loaded.contentMap, 'widget')
        Map target = normalizeWidgetTarget(definition)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'dashboardCreateWidget', dashboard: dashboardGuid, page: pageGuid, title: definition.title
        ]]]
        String createdGuid = null
        if (apply) {
            String operation = '''
                mutation CreateWidget($input: DashboardCreateWidgetInput!) {
                  dashboardCreateWidget(input: $input) {
                    widget { guid title }
                    errors { description type }
                  }
                }
            '''.stripIndent().trim()
            Map input = buildWidgetCreateInput(dashboardGuid, pageGuid, definition, credentials.accountId)
            Map response = graphqlMutate(credentials, 'dashboardCreateWidget', operation, [input: input], timeout)
            Map result = dig(response.data, ['dashboardCreateWidget']) ?: [:]
            validateMutationErrors(result.errors)
            createdGuid = result.widget?.guid?.toString()
            if (!createdGuid) {
                throw new IllegalStateException('Created widget guid missing from mutation response')
            }
            Map verified = fetchWidgetByGuid(credentials, dashboardGuid, createdGuid, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('dashboard-widget-create', Status.ERROR, [[guid: createdGuid]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    dashboard: dashboardGuid, page: pageGuid, dry_run: false, applied: false,
                    change: change, message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('dashboard-widget-create', Status.READY, [[guid: createdGuid, title: definition.title]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            dashboard: dashboardGuid, page: pageGuid, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    Map operatorDashboardWidgetUpdate(
        String profile,
        String widgetGuid,
        File definitionFile,
        String confirmName,
        int timeout
    ) {
        String fetchedAt = utcNow()
        NewRelicCredentials.Resolved credentials = requireCredentials(profile)
        Map existing = fetchWidgetByGuidOnly(credentials, widgetGuid, timeout)
        if (!existing) {
            return report('dashboard-widget-update', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                widget: widgetGuid, message: 'Dashboard widget not found'
            ])
        }
        if (confirmName != existing.title?.toString()) {
            throw new IllegalArgumentException('dashboard-widget-update requires exact current widget title confirmation')
        }
        Map loaded = validateDefinitionFile(definitionFile, true)
        Map definition = validateDashboardDefinition(loaded.contentMap, 'widget')
        Map target = normalizeWidgetTarget(definition)
        Map change = [dry_run: !apply, applied: false, target: target, steps: [[
            action: 'dashboardUpdateWidget', widget: widgetGuid, title: definition.title
        ]]]
        if (apply) {
            String operation = '''
                mutation UpdateWidget($input: DashboardUpdateWidgetInput!) {
                  dashboardUpdateWidget(input: $input) {
                    widget { guid title }
                    errors { description type }
                  }
                }
            '''.stripIndent().trim()
            Map input = buildWidgetUpdateInput(widgetGuid, definition, credentials.accountId)
            Map response = graphqlMutate(credentials, 'dashboardUpdateWidget', operation, [input: input], timeout)
            Map result = dig(response.data, ['dashboardUpdateWidget']) ?: [:]
            validateMutationErrors(result.errors)
            Map verified = fetchWidgetByGuidOnly(credentials, widgetGuid, timeout)
            if (!resourcesMatch(target, verified)) {
                return report('dashboard-widget-update', Status.ERROR, [[guid: widgetGuid]], [
                    fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
                    widget: widgetGuid, dry_run: false, applied: false, change: change,
                    message: 'Post-apply verification failed'
                ])
            }
            change.applied = true
        }
        report('dashboard-widget-update', Status.READY, [[guid: widgetGuid, title: definition.title]], [
            fetched_at: fetchedAt, profile: credentials.id, account_id: credentials.accountId,
            widget: widgetGuid, dry_run: !apply, applied: change.applied as boolean,
            change: change, definition: loaded.subMap(['path', 'size_bytes', 'sha256'])
        ])
    }

    static Map loadOperatorRules(File frameworkRoot) {
        Map defaults = [
            rest_base_url: 'https://api.eu.newrelic.com/v2',
            graphql_url: 'https://api.eu.newrelic.com/graphql',
            default_profile: 'default',
            timeouts: [read_seconds: 15, mutation_seconds: 30],
            limits: [
                response_body_max_characters: 4194304,
                error_body_max_characters: 8192,
                definition_file_max_bytes: 1048576
            ],
            pagination: [rest_max_pages: 100, entity_cursor_max_pages: 100],
            graphql_mutations: [],
            success_codes: [read: [200], mutation: [200]]
        ]
        (Map) JsonFiles.read(new File(frameworkRoot, 'shared/newrelic-operator-rules.json'), defaults)
    }

    static String utcNow() {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .format(OffsetDateTime.now(ZoneOffset.UTC))
    }

    private NewRelicCredentials.Resolved requireCredentials(String profile) {
        NewRelicCredentials.Resolved resolved = NewRelicCredentials.resolve(paths, config, operatorRules, profile)
        if (!resolved.apiKey) {
            throw new NewRelicCredentialException(
                'New Relic API key unavailable',
                resolved.id,
                resolved.accountId
            )
        }
        if (!resolved.accountId) {
            throw new NewRelicCredentialException(
                'New Relic account id unavailable',
                resolved.id,
                resolved.accountId
            )
        }
        resolved
    }

    private Map graphqlQuery(NewRelicCredentials.Resolved credentials, String operation, Map variables, int timeout) {
        Map settings = settings()
        graphql.query(
            credentials.graphqlUrl,
            NewRelicCredentials.authorizationHeaders(credentials),
            operation,
            variables ?: [:],
            timeout > 0 ? timeout : settings.read_timeout_seconds as int,
            settings.response_body_max as int,
            settings.error_body_max as int
        )
    }

    private Map graphqlMutate(
        NewRelicCredentials.Resolved credentials,
        String mutationName,
        String operation,
        Map variables,
        int timeout
    ) {
        Map settings = settings()
        graphql.mutate(
            mutationName,
            credentials.graphqlUrl,
            NewRelicCredentials.authorizationHeaders(credentials),
            operation,
            variables ?: [:],
            timeout > 0 ? timeout : settings.mutation_timeout_seconds as int,
            settings.response_body_max as int,
            settings.error_body_max as int
        )
    }

    private Map restGet(
        NewRelicCredentials.Resolved credentials,
        String pathKey,
        int timeout,
        Map pathBindings,
        Map query
    ) {
        String url = buildRestUrl(credentials, pathKey, pathBindings, query)
        Map settings = settings()
        http.get(
            url,
            NewRelicCredentials.authorizationHeaders(credentials),
            timeout > 0 ? timeout : settings.read_timeout_seconds as int,
            settings.response_body_max as int
        )
    }

    private List fetchRestCollection(
        NewRelicCredentials.Resolved credentials,
        String pathKey,
        int timeout,
        Map pathBindings,
        String collectionKey,
        Map extraQuery = [:]
    ) {
        List collected = []
        Set<String> seen = [] as Set
        int maxPages = settings().rest_max_pages as int
        for (int page = 1; page <= maxPages; page++) {
            Map query = new LinkedHashMap(extraQuery)
            query.page = page
            Map response = restGet(credentials, pathKey, timeout, pathBindings, query)
            if (response.code != 200) {
                if (response.code == 404 && page == 1) {
                    return []
                }
                validateRestSuccess(response)
            }
            Object parsed = parseJson(response.body)
            List pageItems = extractRestItems(parsed, collectionKey)
            if (!pageItems) {
                break
            }
            int collectedBeforePage = collected.size()
            pageItems.each { item ->
                String key = item instanceof Map ? JsonOutput.toJson(item.id ?: item.guid ?: item) : item?.toString()
                if (!seen.contains(key)) {
                    seen << key
                    collected << item
                }
            }
            if (collected.size() == collectedBeforePage) {
                break
            }
        }
        collected
    }

    private static List extractRestItems(Object parsed, String collectionKey) {
        if (parsed instanceof List) {
            return parsed
        }
        if (!(parsed instanceof Map)) {
            return []
        }
        Map map = (Map) parsed
        if (map[collectionKey] instanceof List) {
            return (List) map[collectionKey]
        }
        if (map.data instanceof List) {
            return (List) map.data
        }
        []
    }

    private void validateRestSuccess(Map response) {
        int code = (response.code ?: 0) as int
        List<Integer> codes = operatorRules.success_codes?.read instanceof List ?
            (List<Integer>) operatorRules.success_codes.read : [200]
        if (!codes.collect { it as int }.contains(code)) {
            throw new IllegalStateException(response.error ?: "New Relic returned HTTP ${code}")
        }
    }

    private String buildRestUrl(
        NewRelicCredentials.Resolved credentials,
        String pathKey,
        Map pathBindings,
        Map query
    ) {
        Map apiPaths = operatorRules.api_paths instanceof Map ? (Map) operatorRules.api_paths : [:]
        String template = apiPaths[pathKey]?.toString() ?: "/${pathKey}"
        pathBindings.each { key, value ->
            template = template.replace("{${key}}", value.toString())
        }
        String base = credentials.restUrl ?: settings().rest_base_url ?: operatorRules.rest_base_url
        Map encodedQuery = new LinkedHashMap()
        query.each { key, value ->
            if (value != null && value.toString()) {
                encodedQuery[key] = URLEncoder.encode(value.toString(), 'UTF-8')
            }
        }
        String queryString = encodedQuery.collect { k, v -> "${k}=${v}" }.join('&')
        "${base}${template}${queryString ? '?' + queryString : ''}"
    }

    private List<Map> fetchEntitySearch(NewRelicCredentials.Resolved credentials, String searchQuery, int timeout) {
        List<Map> collected = []
        Set<String> seen = [] as Set
        String cursor = null
        int maxPages = settings().entity_cursor_max_pages as int
        String operation = '''
            query EntitySearch($query: String!, $cursor: String) {
              actor {
                entitySearch(query: $query, options: {cursor: $cursor}) {
                  results {
                    nextCursor
                    entities {
                      guid
                      name
                      type
                      domain
                      accountId
                      tags { key values }
                    }
                  }
                }
              }
            }
        '''.stripIndent().trim()
        for (int page = 0; page < maxPages; page++) {
            Map variables = [query: searchQuery]
            if (cursor) {
                variables.cursor = cursor
            }
            Map response = graphqlQuery(credentials, operation, variables, timeout)
            Map results = dig(response.data, ['actor', 'entitySearch', 'results']) ?: [:]
            List entities = results.entities instanceof List ? (List) results.entities : []
            entities.each { entity ->
                if (!(entity instanceof Map)) {
                    return
                }
                String guid = entity.guid?.toString()
                if (guid && !seen.contains(guid)) {
                    seen << guid
                    collected << normalizeEntity(entity)
                }
            }
            cursor = results.nextCursor?.toString()
            if (!cursor || !entities) {
                break
            }
        }
        collected
    }

    private List<Map> fetchAiIssues(NewRelicCredentials.Resolved credentials, int timeout, String state) {
        List<Map> collected = []
        Set<String> seen = [] as Set
        String cursor = null
        int maxPages = settings().entity_cursor_max_pages as int
        String operation = '''
            query AiIssues($accountId: Int!, $cursor: String, $states: [AiIssuesIssueState!]) {
              actor {
                account(id: $accountId) {
                  aiIssues(filter: {states: $states}, cursor: $cursor) {
                    issues {
                      issueId
                      title
                      priority
                      state
                      createdAt
                      updatedAt
                      entities { name guid }
                    }
                    nextCursor
                  }
                }
              }
            }
        '''.stripIndent().trim()
        List states = null
        if (state && state != 'all') {
            states = [state.toUpperCase()]
        }
        for (int page = 0; page < maxPages; page++) {
            Map variables = [accountId: credentials.accountId as int, states: states, cursor: cursor]
            Map response = graphqlQuery(credentials, operation, variables, timeout)
            Map aiIssues = dig(response.data, ['actor', 'account', 'aiIssues']) ?: [:]
            List issues = aiIssues.issues instanceof List ? (List) aiIssues.issues :
                aiIssues.results instanceof List ? (List) aiIssues.results : []
            issues.each { issue ->
                if (!(issue instanceof Map)) {
                    return
                }
                String key = issue.issueId?.toString() ?: issue.id?.toString()
                if (key && !seen.contains(key)) {
                    seen << key
                    collected << normalizeAiIssue(issue)
                }
            }
            cursor = aiIssues.nextCursor?.toString()
            if (!cursor || !issues) {
                break
            }
        }
        collected
    }

    private List<Map> fetchAiIncidents(
        NewRelicCredentials.Resolved credentials,
        int timeout,
        String issueId,
        String conditionId
    ) {
        List<Map> collected = []
        Set<String> seen = [] as Set
        String cursor = null
        int maxPages = settings().entity_cursor_max_pages as int
        String operation = '''
            query AiIncidents($accountId: Int!, $cursor: String, $issueId: ID, $conditionId: ID) {
              actor {
                account(id: $accountId) {
                  aiIssues {
                    nrAiIncidents(filter: {issueId: $issueId, conditionId: $conditionId}, cursor: $cursor) {
                      incidents {
                        incidentId
                        title
                        state
                        priority
                        createdAt
                        updatedAt
                        entity { name guid }
                        conditionId
                        issueId
                      }
                      nextCursor
                    }
                  }
                }
              }
            }
        '''.stripIndent().trim()
        for (int page = 0; page < maxPages; page++) {
            Map variables = [
                accountId: credentials.accountId as int,
                cursor: cursor,
                issueId: issueId ?: null,
                conditionId: conditionId ?: null
            ]
            Map response = graphqlQuery(credentials, operation, variables, timeout)
            Map node = dig(response.data, ['actor', 'account', 'aiIssues', 'nrAiIncidents']) ?: [:]
            List incidents = node.incidents instanceof List ? (List) node.incidents :
                node.results instanceof List ? (List) node.results : []
            incidents.each { incident ->
                if (!(incident instanceof Map)) {
                    return
                }
                String key = incident.incidentId?.toString() ?: incident.id?.toString()
                if (key && !seen.contains(key)) {
                    seen << key
                    collected << normalizeAiIncident(incident)
                }
            }
            cursor = node.nextCursor?.toString()
            if (!cursor || !incidents) {
                break
            }
        }
        collected
    }

    private Map fetchDashboardDetail(NewRelicCredentials.Resolved credentials, String dashboardGuid, int timeout) {
        String operation = '''
            query DashboardDetail($guid: EntityGuid!) {
              actor {
                entity(guid: $guid) {
                  guid
                  name
                  accountId
                  ... on DashboardEntity {
                    description
                    createdAt
                    updatedAt
                    permissions
                    pages {
                      guid
                      name
                      description
                      widgets {
                        id
                        guid
                        title
                        layout { row column width height }
                        visualization { id }
                        configuration {
                          area { nrqlQueries { accountId query } }
                          bar { nrqlQueries { accountId query } }
                          billboard { nrqlQueries { accountId query } }
                          line { nrqlQueries { accountId query } }
                          pie { nrqlQueries { accountId query } }
                          table { nrqlQueries { accountId query } }
                        }
                        rawConfiguration
                        linkedEntityGuids
                      }
                    }
                  }
                }
              }
            }
        '''.stripIndent().trim()
        Map response = graphqlQuery(credentials, operation, [guid: dashboardGuid], timeout)
        Map entity = dig(response.data, ['actor', 'entity']) ?: [:]
        if (!entity || !entity.guid) {
            return null
        }
        normalizeDashboard(entity)
    }

    private Map fetchPageDetail(
        NewRelicCredentials.Resolved credentials,
        String dashboardGuid,
        String pageGuid,
        int timeout
    ) {
        Map dashboard = fetchDashboardDetail(credentials, dashboardGuid, timeout)
        if (!dashboard) {
            return null
        }
        dashboard.pages?.find { it.guid?.toString() == pageGuid.toString() }
    }

    private Map fetchPageByGuid(NewRelicCredentials.Resolved credentials, String pageGuid, int timeout) {
        String operation = '''
            query PageDetail($guid: EntityGuid!) {
              actor {
                entity(guid: $guid) {
                  guid
                  name
                  ... on DashboardPageEntity {
                    description
                  }
                }
              }
            }
        '''.stripIndent().trim()
        Map response = graphqlQuery(credentials, operation, [guid: pageGuid], timeout)
        Map entity = dig(response.data, ['actor', 'entity']) ?: [:]
        entity.guid ? normalizePageTarget(entity) : null
    }

    private Map fetchWidgetByGuid(
        NewRelicCredentials.Resolved credentials,
        String dashboardGuid,
        String widgetGuid,
        int timeout
    ) {
        Map dashboard = fetchDashboardDetail(credentials, dashboardGuid, timeout)
        if (!dashboard) {
            return null
        }
        Map found = null
        dashboard.pages?.each { page ->
            page.widgets?.each { widget ->
                if (widget.guid?.toString() == widgetGuid.toString() || widget.id?.toString() == widgetGuid.toString()) {
                    found = widget
                }
            }
        }
        found
    }

    private Map fetchWidgetByGuidOnly(NewRelicCredentials.Resolved credentials, String widgetGuid, int timeout) {
        String operation = '''
            query WidgetDetail($guid: EntityGuid!) {
              actor {
                entity(guid: $guid) {
                  guid
                  name
                  ... on DashboardWidgetEntity {
                    title
                    visualization { id }
                    layout { row column width height }
                    rawConfiguration
                  }
                }
              }
            }
        '''.stripIndent().trim()
        Map response = graphqlQuery(credentials, operation, [guid: widgetGuid], timeout)
        Map entity = dig(response.data, ['actor', 'entity']) ?: [:]
        if (!entity.guid) {
            return null
        }
        normalizeWidgetTarget([
            title: entity.title ?: entity.name,
            visualization: entity.visualization,
            layout: entity.layout,
            rawConfiguration: entity.rawConfiguration
        ])
    }

    private List<Map> fetchConditionsForPolicy(
        NewRelicCredentials.Resolved credentials,
        String policyId,
        int timeout
    ) {
        Map conditionTypes = operatorRules.condition_types instanceof Map ?
            (Map) operatorRules.condition_types : [:]
        Map pathMappings = [
            apm: 'alert_conditions',
            nrql: 'alert_nrql_conditions',
            external: 'alert_external_service_conditions',
            synthetics: 'alert_synthetics_conditions'
        ]
        List<Map> items = []
        conditionTypes.each { label, collectionKey ->
            String pathKey = pathMappings[label?.toString()] ?: "alert_${label}_conditions"
            List conditions = fetchRestCollection(
                credentials, pathKey, timeout, [:], collectionKey.toString(),
                [policy_id: policyId]
            )
            conditions.each { cond ->
                items << normalizeAlertCondition(cond, label.toString().toUpperCase(), policyId)
            }
        }
        items
    }

    private Map fetchConditionById(NewRelicCredentials.Resolved credentials, String conditionId, int timeout) {
        List attempts = [
            [path: 'alert_condition', type: 'NRQL'],
            [path: 'alert_apm_condition', type: 'APM'],
            [path: 'alert_external_condition', type: 'EXTERNAL'],
            [path: 'alert_synthetics_condition', type: 'SYNTHETICS']
        ]
        for (Map attempt : attempts) {
            Map response = restGet(credentials, attempt.path as String, timeout, [id: conditionId], [:])
            if (response.code == 404) {
                continue
            }
            validateRestSuccess(response)
            Object parsed = parseJson(response.body)
            Map raw = null
            if (parsed instanceof Map) {
                raw = (Map) (parsed.nrql_condition ?: parsed.condition ?: parsed.external_service_condition ?:
                    parsed.synthetics_condition ?: parsed)
            }
            if (raw) {
                return normalizeAlertCondition(raw, attempt.type as String, raw.policy_id?.toString())
            }
        }
        null
    }

    private static String userQuery() {
        '''query { actor { user { id email name } } }'''
    }

    private static String buildEntitySearchQuery(String query, String domain, String type) {
        List<String> parts = []
        if (query?.trim()) {
            parts << query.trim()
        } else {
            parts << "domain IN ('APM','INFRA','BROWSER','MOBILE','EXT','SYNTH','VIZ')"
        }
        if (domain?.trim()) {
            parts << "domain = '${escapeNrqlLiteral(domain.trim())}'"
        }
        if (type?.trim()) {
            parts << "type = '${escapeNrqlLiteral(type.trim())}'"
        }
        parts.join(' AND ')
    }

    private Map validateDefinitionFile(File file, boolean parseJsonContent) {
        Path workspace = paths.root.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        Path supplied = file.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(supplied)) {
            throw new IllegalArgumentException("Invalid definition file: ${file}")
        }
        Path target = file.canonicalFile.toPath()
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid definition file: ${file}")
        }
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("Definition file must be inside workspace: ${file}")
        }
        long maxBytes = settings().definition_file_max as long
        long size = Files.size(target)
        if (size > maxBytes) {
            throw new IllegalArgumentException("Definition file exceeds size limit: ${file}")
        }
        byte[] bytes = Files.readAllBytes(target)
        if (bytes.contains((byte) 0)) {
            throw new IllegalArgumentException("Definition file contains NUL bytes: ${file}")
        }
        String content
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Definition file must be valid UTF-8: ${file}")
        }
        String sha256 = MessageDigest.getInstance('SHA-256').digest(bytes)
            .collect { String.format('%02x', it) }.join('')
        Map result = [
            path: workspace.relativize(target).toString(),
            size_bytes: size,
            sha256: sha256,
            content: content
        ]
        if (parseJsonContent) {
            Object parsed = slurper.parseText(content)
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException('Definition file must contain a JSON object')
            }
            result.contentMap = new LinkedHashMap((Map) parsed)
        }
        result
    }

    private Map validateAlertConditionDefinition(Map definition) {
        rejectUnexpectedKeys(definition, [
            'name', 'enabled', 'nrql', 'signal', 'terms', 'violationTimeLimitSeconds', 'valueFunction'
        ], 'Alert condition definition')
        List<String> required = ['name', 'enabled', 'nrql', 'signal', 'terms', 'violationTimeLimitSeconds']
        required.each { field ->
            if (!definition.containsKey(field)) {
                throw new IllegalArgumentException("Alert condition definition missing ${field}")
            }
        }
        if (!(definition.name instanceof String) || !definition.name.trim()) {
            throw new IllegalArgumentException('Alert condition definition requires non-empty name')
        }
        if (!(definition.enabled instanceof Boolean)) {
            throw new IllegalArgumentException('Alert condition definition requires boolean enabled')
        }
        if (!(definition.nrql instanceof Map) || !definition.nrql.query?.toString()?.trim()) {
            throw new IllegalArgumentException('Alert condition definition requires nrql.query')
        }
        rejectUnexpectedKeys((Map) definition.nrql, ['query', 'dataAccountId'], 'Alert condition nrql')
        if (definition.nrql.dataAccountId != null) {
            requireIntegerMinimum(definition.nrql.dataAccountId, 1, 'Alert condition nrql.dataAccountId')
        }
        if (!(definition.signal instanceof Map) || !definition.signal.aggregationWindow) {
            throw new IllegalArgumentException('Alert condition definition requires signal.aggregationWindow')
        }
        rejectUnexpectedKeys((Map) definition.signal, [
            'aggregationWindow', 'aggregationMethod', 'aggregationDelay', 'fillOption', 'fillValue', 'evaluationDelay'
        ], 'Alert condition signal')
        requireIntegerMinimum(definition.signal.aggregationWindow, 1, 'Alert condition signal.aggregationWindow')
        if (definition.signal.aggregationDelay != null) {
            requireIntegerMinimum(definition.signal.aggregationDelay, 0, 'Alert condition signal.aggregationDelay')
        }
        if (definition.signal.evaluationDelay != null) {
            requireIntegerMinimum(definition.signal.evaluationDelay, 0, 'Alert condition signal.evaluationDelay')
        }
        if (!(definition.terms instanceof List) || !definition.terms) {
            throw new IllegalArgumentException('Alert condition definition requires at least one term')
        }
        definition.terms.each { term ->
            if (!(term instanceof Map)) {
                throw new IllegalArgumentException('Alert condition term must be an object')
            }
            rejectUnexpectedKeys((Map) term, [
                'priority', 'operator', 'threshold', 'thresholdDuration', 'thresholdOccurrences'
            ], 'Alert condition term')
            ['priority', 'operator', 'threshold', 'thresholdDuration', 'thresholdOccurrences'].each { key ->
                if (!term.containsKey(key)) {
                    throw new IllegalArgumentException("Alert condition term missing ${key}")
                }
            }
            requireEnum(term.priority, ['CRITICAL', 'WARNING'], 'Alert condition term.priority')
            requireEnum(term.operator, ['ABOVE', 'BELOW', 'EQUAL', 'NOT_EQUAL'], 'Alert condition term.operator')
            requireEnum(term.thresholdOccurrences, ['ALL', 'AT_LEAST_ONCE'], 'Alert condition term.thresholdOccurrences')
            requireIntegerMinimum(term.thresholdDuration, 1, 'Alert condition term.thresholdDuration')
        }
        requireIntegerRange(
            definition.violationTimeLimitSeconds, 300, 2592000,
            'Alert condition violationTimeLimitSeconds'
        )
        if (definition.valueFunction != null) {
            requireEnum(definition.valueFunction, ['SINGLE_VALUE', 'SUM'], 'Alert condition valueFunction')
        }
        new LinkedHashMap(definition)
    }

    private Map validateDashboardDefinition(Map definition, String expectedKind) {
        String kind = definition.kind?.toString() ?: expectedKind
        if (kind != expectedKind) {
            throw new IllegalArgumentException("Dashboard definition kind must be ${expectedKind}")
        }
        if (expectedKind == 'dashboard') {
            rejectUnexpectedKeys(definition, ['kind', 'name', 'description', 'permissions', 'pages'], 'Dashboard definition')
            if (!definition.name?.toString()?.trim()) {
                throw new IllegalArgumentException('Dashboard definition requires name')
            }
            if (definition.permissions != null) {
                requireEnum(
                    definition.permissions,
                    ['PUBLIC_READ_WRITE', 'PUBLIC_READ_ONLY', 'PRIVATE'],
                    'Dashboard definition permissions'
                )
            }
            if (definition.pages instanceof List) {
                definition.pages.each { page ->
                    validateDashboardDefinition(page instanceof Map ? (Map) page : [:], 'page')
                }
            }
        } else if (expectedKind == 'page') {
            rejectUnexpectedKeys(definition, ['kind', 'name', 'description', 'widgets'], 'Dashboard page definition')
            if (!definition.name?.toString()?.trim()) {
                throw new IllegalArgumentException('Dashboard page definition requires name')
            }
            if (definition.widgets instanceof List) {
                definition.widgets.each { widget ->
                    validateDashboardDefinition(widget instanceof Map ? (Map) widget : [:], 'widget')
                }
            }
        } else if (expectedKind == 'widget') {
            rejectUnexpectedKeys(definition, [
                'kind', 'title', 'visualization', 'layout', 'row', 'column', 'width', 'height',
                'configuration', 'rawConfiguration', 'linkedEntityGuids'
            ], 'Dashboard widget definition')
            if (!definition.title?.toString()?.trim()) {
                throw new IllegalArgumentException('Dashboard widget definition requires title')
            }
            if (!(definition.visualization instanceof Map) && !definition.rawConfiguration) {
                throw new IllegalArgumentException('Dashboard widget definition requires visualization or rawConfiguration')
            }
            if (definition.visualization instanceof Map) {
                rejectUnexpectedKeys((Map) definition.visualization, ['id'], 'Dashboard widget visualization')
                if (!definition.visualization.id?.toString()?.trim()) {
                    throw new IllegalArgumentException('Dashboard widget visualization requires id')
                }
            }
            if (definition.configuration != null && !(definition.configuration instanceof Map)) {
                throw new IllegalArgumentException('Dashboard widget configuration must be an object')
            }
            if (definition.rawConfiguration != null &&
                !(definition.rawConfiguration instanceof String) &&
                !(definition.rawConfiguration instanceof Map)) {
                throw new IllegalArgumentException('Dashboard widget rawConfiguration must be a string or object')
            }
            if (definition.linkedEntityGuids != null &&
                (!(definition.linkedEntityGuids instanceof List) ||
                    definition.linkedEntityGuids.any { !(it instanceof String) })) {
                throw new IllegalArgumentException('Dashboard widget linkedEntityGuids must be an array of strings')
            }
            validateWidgetLayout(definition)
        }
        new LinkedHashMap(definition)
    }

    private static void validateWidgetLayout(Map definition) {
        Map layout = definition.layout instanceof Map ? (Map) definition.layout : [:]
        if (layout) {
            rejectUnexpectedKeys(layout, ['row', 'column', 'width', 'height'], 'Dashboard widget layout')
            ['row', 'column', 'width', 'height'].each { key ->
                if (layout[key] != null) {
                    requireIntegerMinimum(layout[key], 1, "Dashboard widget layout.${key}")
                }
            }
        }
        ['row', 'column', 'width', 'height'].each { key ->
            if (definition[key] != null) {
                requireIntegerMinimum(definition[key], 1, "Dashboard widget ${key}")
            }
        }
    }

    private static void rejectUnexpectedKeys(Map map, Collection<String> allowed, String label) {
        map.keySet().each { key ->
            if (!allowed.contains(key.toString())) {
                throw new IllegalArgumentException("${label} has unexpected property: ${key}")
            }
        }
    }

    private static void requireEnum(Object value, List<String> allowed, String label) {
        if (!allowed.contains(value?.toString())) {
            throw new IllegalArgumentException("${label} must be one of ${allowed.join(', ')}")
        }
    }

    private static void requireIntegerMinimum(Object value, int minimum, String label) {
        if (!(value instanceof Number) || (value as Number).longValue() < minimum) {
            throw new IllegalArgumentException("${label} must be >= ${minimum}")
        }
    }

    private static void requireIntegerRange(Object value, int minimum, int maximum, String label) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("${label} must be an integer")
        }
        long number = (value as Number).longValue()
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("${label} must be between ${minimum} and ${maximum}")
        }
    }

    private static Map buildConditionInput(Map definition) {
        Map input = [
            name: definition.name,
            enabled: definition.enabled,
            nrql: definition.nrql,
            signal: definition.signal,
            terms: definition.terms,
            violationTimeLimitSeconds: definition.violationTimeLimitSeconds
        ]
        if (definition.valueFunction) {
            input.valueFunction = definition.valueFunction
        }
        input
    }

    private static Map buildDashboardCreateInput(Map definition) {
        Map dashboard = [
            name: definition.name,
            permissions: definition.permissions ?: 'PRIVATE'
        ]
        if (definition.description) {
            dashboard.description = definition.description
        }
        if (definition.pages instanceof List) {
            dashboard.pages = definition.pages.collect { buildPageInput(it instanceof Map ? (Map) it : [:]) }
        }
        dashboard
    }

    private static Map buildPageInput(Map definition) {
        Map page = [name: definition.name]
        if (definition.description) {
            page.description = definition.description
        }
        if (definition.widgets instanceof List) {
            page.widgets = definition.widgets.collect { widget ->
                buildDashboardWidgetInput(widget instanceof Map ? (Map) widget : [:])
            }
        }
        page
    }

    private static Map buildDashboardWidgetInput(Map definition) {
        Map layout = definition.layout instanceof Map ? (Map) definition.layout : [
            row: definition.row ?: 1,
            column: definition.column ?: 1,
            width: definition.width ?: 4,
            height: definition.height ?: 3
        ]
        Map input = [
            title: definition.title,
            layout: layout
        ]
        if (definition.visualization instanceof Map) {
            input.visualization = definition.visualization
        }
        if (definition.configuration instanceof Map) {
            input.configuration = definition.configuration
        }
        if (definition.rawConfiguration != null) {
            input.rawConfiguration = definition.rawConfiguration
        }
        if (definition.linkedEntityGuids instanceof List) {
            input.linkedEntityGuids = definition.linkedEntityGuids
        }
        input
    }

    private static Map buildWidgetCreateInput(
        String dashboardGuid,
        String pageGuid,
        Map definition,
        String accountId
    ) {
        Map input = [:]
        if (dashboardGuid) {
            input.dashboardGuid = dashboardGuid
        }
        if (pageGuid) {
            input.pageGuid = pageGuid
        }
        input.title = definition.title
        Map layout = definition.layout instanceof Map ? (Map) definition.layout : [:]
        input.row = (layout.row ?: definition.row ?: 1) as int
        input.column = (layout.column ?: definition.column ?: 1) as int
        input.width = (layout.width ?: definition.width ?: 4) as int
        input.height = (layout.height ?: definition.height ?: 3) as int
        if (definition.visualization instanceof Map) {
            input.visualization = definition.visualization
        }
        if (definition.configuration) {
            input.configuration = definition.configuration
        }
        if (definition.rawConfiguration) {
            input.rawConfiguration = definition.rawConfiguration instanceof String ?
                definition.rawConfiguration : JsonOutput.toJson(definition.rawConfiguration)
        } else if (definition.configuration && accountId) {
            input.rawConfiguration = JsonOutput.toJson([
                nrqlQueries: NewRelicTerraformRenderer.extractNrqlQueries(definition, accountId)
                    .collect { [accountId: it.account_id as int, query: it.query] }
            ])
        }
        input
    }

    private static Map buildWidgetUpdateInput(String widgetGuid, Map definition, String accountId) {
        Map input = buildWidgetCreateInput(null, null, definition, accountId)
        input.guid = widgetGuid
        input
    }

    private static void validateMutationErrors(Object errors) {
        if (errors instanceof List && errors) {
            List messages = errors.collect { it instanceof Map ? it.description?.toString() : it?.toString() }.findAll { it }
            throw new IllegalStateException(messages ? messages.join('; ') : 'NerdGraph mutation returned errors')
        }
    }

    private static Map normalizeApplication(Map app) {
        Map summary = app.application_summary instanceof Map ? (Map) app.application_summary : [:]
        [
            id: app.id,
            name: app.name,
            language: app.language,
            health_status: app.health_status,
            reporting: app.reporting,
            last_reported_at: app.last_reported_at,
            response_time: summary.response_time,
            throughput: summary.throughput,
            error_rate: summary.error_rate,
            apdex_score: summary.apdex_score,
            host_count: summary.host_count,
            instance_count: summary.instance_count
        ]
    }

    private static Map normalizeHost(Map host) {
        Map summary = host.application_summary instanceof Map ? (Map) host.application_summary : [:]
        [
            id: host.id,
            host: host.host,
            health_status: host.health_status,
            response_time: summary.response_time,
            throughput: summary.throughput,
            error_rate: summary.error_rate,
            apdex_score: summary.apdex_score
        ]
    }

    private static Map normalizeDeployment(Map deployment) {
        [
            id: deployment.id,
            revision: deployment.revision,
            description: deployment.description,
            user: deployment.user,
            timestamp: deployment.timestamp,
            changelog: deployment.changelog
        ]
    }

    private static Map normalizeViolation(Map violation) {
        [
            id: violation.id,
            priority: violation.priority,
            policy_id: violation.policy_id,
            policy_name: violation.policy_name,
            condition_id: violation.condition_id,
            condition_name: violation.condition_name,
            entity_id: violation.entity_id,
            entity_name: violation.entity_name,
            entity_label: violation.label ?: violation.entity_label,
            opened_at: formatEpoch(violation.opened_at),
            duration: violation.duration,
            closed_at: formatEpoch(violation.closed_at)
        ]
    }

    private static Map normalizeAiIssue(Map issue) {
        List entities = issue.entities instanceof List ? (List) issue.entities : []
        Map entity = entities ? (entities[0] instanceof Map ? (Map) entities[0] : [:]) : [:]
        [
            issue_id: issue.issueId ?: issue.id,
            title: issue.title,
            priority: issue.priority,
            state: issue.state,
            created_at: issue.createdAt,
            updated_at: issue.updatedAt,
            entity_name: entity.name,
            entity_guid: entity.guid
        ]
    }

    private static Map normalizeAiIncident(Map incident) {
        Map entity = incident.entity instanceof Map ? (Map) incident.entity : [:]
        [
            incident_id: incident.incidentId ?: incident.id,
            issue_id: incident.issueId,
            condition_id: incident.conditionId,
            title: incident.title,
            priority: incident.priority,
            state: incident.state,
            created_at: incident.createdAt,
            updated_at: incident.updatedAt,
            entity_name: entity.name,
            entity_guid: entity.guid
        ]
    }

    private static Map normalizeErrors(Map accountNode, String appId, int hours) {
        int total = extractCount(dig(accountNode, ['errSummary', 'results']))
        [
            application_id: appId,
            hours: hours,
            total_errors: total,
            by_class: normalizeFacetRows(dig(accountNode, ['errByClass', 'results'])),
            by_message: normalizeFacetRows(dig(accountNode, ['errByMessage', 'results']), true),
            by_transaction: normalizeFacetRows(dig(accountNode, ['errByTransaction', 'results'])),
            by_host: normalizeFacetRows(dig(accountNode, ['errByHost', 'results'])),
            timeline: normalizeTimelineRows(dig(accountNode, ['errTimeline', 'results']))
        ]
    }

    private static int extractCount(Object results) {
        if (results instanceof List && results) {
            Object first = results[0]
            if (first instanceof Map) {
                return (first.count ?: first['count(*)'] ?: 0) as int
            }
        }
        0
    }

    private static List<Map> normalizeFacetRows(Object results, boolean dualFacet = false) {
        if (!(results instanceof List)) {
            return []
        }
        results.collect { row ->
            if (!(row instanceof Map)) {
                return [count: row]
            }
            Map map = (Map) row
            if (dualFacet && map.facet instanceof List) {
                return [count: map.count, facet: map.facet]
            }
            [count: map.count, facet: map.facet ?: map['error.class'] ?: map.transactionName ?: map.host]
        }
    }

    private static List<Map> normalizeTimelineRows(Object results) {
        if (!(results instanceof List)) {
            return []
        }
        results.collect { row ->
            if (!(row instanceof Map)) {
                return [count: row]
            }
            Map map = (Map) row
            [
                count: map.count,
                begin_time_seconds: map.beginTimeSeconds,
                end_time_seconds: map.endTimeSeconds
            ]
        }
    }

    private static Map normalizeEntity(Map entity) {
        String ownerEmail = null
        if (entity.tags instanceof List) {
            entity.tags.each { tag ->
                if (tag instanceof Map && tag.key == 'createdBy' && tag.values instanceof List && tag.values) {
                    ownerEmail = tag.values[0]?.toString()
                }
            }
        }
        [
            guid: entity.guid,
            name: entity.name,
            type: entity.type,
            domain: entity.domain,
            account_id: entity.accountId ?: entity.account_id,
            owner_email: ownerEmail,
            created_by: ownerEmail
        ]
    }

    private static Map normalizeDashboard(Map entity) {
        List pages = entity.pages instanceof List ? entity.pages.collect { page ->
            if (!(page instanceof Map)) {
                return [:]
            }
            List widgets = page.widgets instanceof List ? page.widgets.collect { widget ->
                normalizeWidgetTarget(widget instanceof Map ? (Map) widget : [:])
            } : []
            [
                guid: page.guid,
                name: page.name,
                description: page.description,
                widgets: widgets
            ]
        } : []
        [
            guid: entity.guid,
            name: entity.name,
            description: entity.description,
            account_id: entity.accountId ?: entity.account_id,
            permissions: entity.permissions,
            created_at: entity.createdAt,
            updated_at: entity.updatedAt,
            pages: pages
        ]
    }

    private static Map normalizeAlertPolicy(Map policy) {
        [
            id: policy.id,
            name: policy.name,
            incident_preference: policy.incident_preference,
            created_at: policy.created_at,
            updated_at: policy.updated_at
        ]
    }

    private static Map normalizeAlertCondition(Map condition, String type, String policyId) {
        Map nrql = condition.nrql instanceof Map ? (Map) condition.nrql : [:]
        [
            id: condition.id,
            name: condition.name,
            type: type,
            policy_id: condition.policy_id ?: policyId,
            enabled: condition.enabled != false,
            metric: condition.metric,
            nrql_query: nrql.query,
            terms: condition.terms,
            signal: condition.signal,
            entity_guid: condition.entity_guid,
            entities: condition.entities,
            created_at: condition.created_at,
            updated_at: condition.updated_at,
            violation_time_limit_seconds: condition.violation_time_limit_seconds ?:
                condition.violationTimeLimitSeconds
        ]
    }

    private static Map normalizeAlertConditionTarget(Map definition, String policyId) {
        [
            name: definition.name,
            enabled: definition.enabled,
            policy_id: policyId,
            nrql_query: definition.nrql?.query,
            terms: definition.terms,
            signal: definition.signal,
            violation_time_limit_seconds: definition.violationTimeLimitSeconds
        ]
    }

    private static Map normalizeDashboardTarget(Map definition) {
        [
            name: definition.name,
            description: definition.description,
            permissions: definition.permissions,
            pages: definition.pages instanceof List ? definition.pages.collect { normalizePageTarget(it) } : []
        ]
    }

    private static Map normalizePageTarget(Map definition) {
        [
            name: definition.name,
            description: definition.description,
            widgets: definition.widgets instanceof List ? definition.widgets.collect { normalizeWidgetTarget(it) } : []
        ]
    }

    private static Map normalizeWidgetTarget(Map definition) {
        Map layout = definition.layout instanceof Map ? (Map) definition.layout : [:]
        [
            guid: definition.guid ?: definition.id,
            title: definition.title ?: definition.name,
            visualization: definition.visualization,
            layout: layout ?: [
                row: definition.row, column: definition.column,
                width: definition.width, height: definition.height
            ],
            configuration: definition.configuration,
            rawConfiguration: definition.rawConfiguration,
            linked_entity_guids: definition.linkedEntityGuids
        ]
    }

    private static boolean resourcesMatch(Map target, Map actual) {
        if (!target || !actual) {
            return false
        }
        for (Map.Entry entry : target.entrySet()) {
            String key = entry.key.toString()
            Object expected = entry.value
            if (expected == null) {
                continue
            }
            if (!actual.containsKey(key)) {
                return false
            }
            Object actualValue = actual[key]
            if (expected instanceof Map && actualValue instanceof Map) {
                if (!resourcesMatch((Map) expected, (Map) actualValue)) {
                    return false
                }
            } else if (expected instanceof List && actualValue instanceof List) {
                if (expected.size() != actualValue.size()) {
                    return false
                }
                for (int index = 0; index < expected.size(); index++) {
                    Object expectedItem = expected[index]
                    Object actualItem = actualValue[index]
                    if (expectedItem instanceof Map && actualItem instanceof Map) {
                        if (!resourcesMatch((Map) expectedItem, (Map) actualItem)) {
                            return false
                        }
                    } else if (expectedItem?.toString() != actualItem?.toString()) {
                        return false
                    }
                }
            } else if (expected?.toString() != actualValue?.toString()) {
                return false
            }
        }
        true
    }

    private Path resolveWorkspaceOutput(String outputPath) {
        File target = resolveWorkspaceFile(outputPath)
        File directory = target.isFile() ? target.parentFile : target
        assertInsideWorkspace(directory)
        directory.toPath()
    }

    private Path resolveWorkspaceOutputFile(String outputPath, String defaultName) {
        if (outputPath.endsWith('.json')) {
            File file = resolveWorkspaceFile(outputPath)
            assertInsideWorkspace(file)
            return file.toPath()
        }
        File directory = resolveWorkspaceFile(outputPath)
        assertInsideWorkspace(directory.isFile() ? directory.parentFile : directory)
        resolveWorkspaceFile(new File(directory, defaultName).path).toPath()
    }

    private File resolveWorkspaceFile(String outputPath) {
        File raw = new File(outputPath)
        raw.isAbsolute() ? raw : new File(paths.root, outputPath)
    }

    private void assertInsideWorkspace(File target) {
        Path workspace = paths.root.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        Path absolute = target.toPath().toAbsolutePath().normalize()
        Path current = absolute
        while (current != null && current.nameCount > 0) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Output path must not use symlinks: ${target.path}")
            }
            if (current == workspace) {
                break
            }
            current = current.parent
        }
        Path resolved = Files.exists(absolute) ?
            absolute.toRealPath(LinkOption.NOFOLLOW_LINKS) :
            absolute
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("Output path must be inside workspace: ${target.path}")
        }
    }

    private String workspaceRelative(Path path) {
        Path workspace = paths.root.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        workspace.relativize(path.toAbsolutePath().normalize()).toString()
    }

    private static Map fileMetadata(File file, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8)
        String sha256 = MessageDigest.getInstance('SHA-256').digest(bytes)
            .collect { String.format('%02x', it) }.join('')
        [
            path: file.name,
            size_bytes: bytes.length,
            sha256: sha256
        ]
    }

    private static void writeAtomicText(File file, String content, boolean force) {
        if (file.exists() && !force) {
            throw new IllegalArgumentException("Destination exists: ${file.path} (use --force to replace)")
        }
        file.parentFile?.mkdirs()
        File temporary = File.createTempFile(".${file.name}.", '.tmp', file.parentFile)
        temporary.setText(content, 'UTF-8')
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    Instant parseDateBoundary(String value, boolean start) {
        if (!value) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC)
            return start ? today.atStartOfDay(ZoneOffset.UTC).toInstant() :
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
        }
        try {
            if (value.contains('T')) {
                return OffsetDateTime.parse(value).toInstant()
            }
            LocalDate date = LocalDate.parse(value)
            return start ? date.atStartOfDay(ZoneOffset.UTC).toInstant() :
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid date: ${value} (expected YYYY-MM-DD or ISO 8601)")
        }
    }

    private static Map effectiveDateFilters(String since, String until, Instant sinceInstant, Instant untilInstant) {
        [
            since: since ?: DateTimeFormatter.ISO_INSTANT.format(sinceInstant),
            until: until ?: DateTimeFormatter.ISO_INSTANT.format(untilInstant)
        ]
    }

    private static Instant parseInstant(Object value) {
        if (!value) {
            return null
        }
        try {
            if (value instanceof Number) {
                long epoch = value.longValue()
                if (epoch > 9999999999L) {
                    return Instant.ofEpochMilli(epoch)
                }
                return Instant.ofEpochSecond(epoch)
            }
            String text = value.toString()
            if (text.contains('T')) {
                return Instant.parse(text)
            }
            return OffsetDateTime.parse(text).toInstant()
        } catch (Exception ignored) {
            null
        }
    }

    private static String formatEpoch(Object value) {
        Instant instant = parseInstant(value)
        instant ? DateTimeFormatter.ISO_INSTANT.format(instant) : value?.toString()
    }

    private static String escapeNrqlLiteral(String value) {
        value.replace('\\', '\\\\').replace("'", "\\'")
    }

    private static List<Map> filterByQuery(List<Map> items, String field, String query) {
        if (!query?.trim()) {
            return items
        }
        String lower = query.toLowerCase()
        items.findAll { (it[field] ?: '').toString().toLowerCase().contains(lower) }
    }

    private static List<Map> sortByName(List<Map> items, String field = 'name') {
        items.sort { a, b -> (a[field] ?: '').toString().toLowerCase() <=> (b[field] ?: '').toString().toLowerCase() }
    }

    private static Map dedupeAndTruncate(List<Map> items, Closure<String> keyFn, int limit) {
        Set<String> seen = [] as Set
        List<Map> unique = []
        items.each { item ->
            String key = keyFn.call(item)
            if (!seen.contains(key)) {
                seen << key
                unique << item
            }
        }
        boolean truncated = unique.size() > limit
        if (truncated) {
            unique = unique.take(limit)
        }
        [items: unique, truncated: truncated, total: seen.size()]
    }

    private Object parseJson(String body) {
        if (!body?.trim()) {
            return null
        }
        slurper.parseText(body)
    }

    private static Object dig(Object node, List<String> path) {
        Object current = node
        path.each { segment ->
            if (!(current instanceof Map)) {
                return null
            }
            current = ((Map) current)[segment]
        }
        current
    }

    private static Map report(String operation, Status status, List items, Map extras = [:]) {
        Map payload = [
            operation: operation,
            fetched_at: extras.remove('fetched_at') ?: utcNow(),
            status: status,
            items: items
        ]
        payload.putAll(extras)
        payload
    }
}
