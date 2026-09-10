package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

class AutomoxAdapter {
    private static final Pattern NUMERIC = ~/^\d+$/
    private static final Pattern IPV4 = ~/^\d{1,3}(\.\d{1,3}){3}$/

    final FrameworkPaths paths
    final ReadOnlyHttp http
    final AutomoxWriteClient writeClient
    final Map overrides
    final File frameworkRoot
    final Map config
    final boolean apply
    final Map operatorRules
    final JsonSlurper slurper = new JsonSlurper()

    AutomoxAdapter(
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
        this.writeClient = new AutomoxWriteClient(writeHttp ?: new JsonWriteHttp(), apply)
        this.overrides = overrides ?: [:]
        this.frameworkRoot = frameworkRoot
        this.config = config ?: [:]
        this.apply = apply
        this.operatorRules = frameworkRoot ? loadOperatorRules(frameworkRoot) : overrides ?: [:]
    }

    Map settings() {
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map automox = adapters.automox instanceof Map ? (Map) adapters.automox : [:]
        Map limits = operatorRules.limits instanceof Map ? (Map) operatorRules.limits : [:]
        Map polling = operatorRules.polling instanceof Map ? (Map) operatorRules.polling : [:]
        [
            timeout_seconds: (automox.timeout_seconds ?: operatorRules.timeouts?.http_seconds ?: 10) as int,
            mutation_timeout_seconds: (automox.mutation_timeout_seconds ?:
                operatorRules.timeouts?.mutation_seconds ?: 30) as int,
            api_base_url: automox.api_base_url?.toString() ?: operatorRules.api_base_url?.toString(),
            org: automox.org?.toString(),
            domain: automox.domain?.toString(),
            default_profile: automox.default_profile?.toString() ?: operatorRules.default_profile?.toString(),
            devices_limit: (automox.devices_limit ?: limits.devices_default ?: 500) as int,
            groups_limit: (automox.groups_limit ?: limits.groups_default ?: 500) as int,
            policies_limit: (automox.policies_limit ?: limits.policies_default ?: 500) as int,
            packages_limit: (automox.packages_limit ?: limits.packages_default ?: 500) as int,
            activity_limit: (automox.activity_limit ?: limits.activity_default ?: 500) as int,
            patch_summary_limit: (automox.patch_summary_limit ?: limits.patch_summary_default ?: 500) as int,
            queue_limit: (automox.queue_limit ?: limits.queue_default ?: 50) as int,
            history_max: (automox.history_max ?: limits.history_max ?: 50000) as int,
            response_body_max: (limits.response_body_max_characters ?: 1048576) as int,
            error_body_max: (limits.error_body_max_characters ?: 4096) as int,
            polling_interval_seconds: (automox.polling_interval_seconds ?:
                polling.default_interval_seconds ?: 5) as int,
            polling_max_wait_seconds: (automox.polling_max_wait_seconds ?:
                polling.max_wait_seconds ?: 900) as int
        ]
    }

    Map operatorProfiles() {
        String fetchedAt = utcNow()
        File propertiesFile = new File(paths.serviceDir('automox'), 'automox.properties')
        List<Map> items = AutomoxCredentials.publicProfiles(paths, config, operatorRules)
        boolean configured = propertiesFile.isFile() && items.any { it.has_api_token }
        if (!configured && !items.any { it.has_api_token }) {
            return report('profiles', Status.BLOCKED, [],
                [fetched_at: fetchedAt, message: 'No automox.properties found or no profiles configured'])
        }
        report('profiles', Status.READY, items, [fetched_at: fetchedAt])
    }

    Map operatorAuthTest(String profile, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map response = automoxGet('orgs', credentials, timeout, [:])
        if (response.code == 401 || response.code == 403) {
            return report('auth-test', Status.BLOCKED, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                message: "Automox returned HTTP ${response.code}"
            ])
        }
        if (response.code == 200) {
            return report('auth-test', Status.READY, [[authenticated: true]], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                message: 'Automox authentication succeeded'
            ])
        }
        report('auth-test', Status.DEGRADED, [], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            message: response.error ?: "Automox returned HTTP ${response.code}"
        ])
    }

    Map operatorOrgs(String profile, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        List orgs = fetchArray('orgs', credentials, timeout, [:], [:])
        List<Map> items = orgs.collect { Map org ->
            [
                id: org.id,
                name: org.name,
                device_count: org.device_count ?: org.devices ?: 0
            ]
        }.sort { a, b -> (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase() }
        report('orgs', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org
        ])
    }

    Map operatorGroups(String profile, String query, int limit, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        List groups = fetchArray('groups', credentials, timeout, [:], [o: credentials.org])
        List<Map> items = groups.collect { Map group ->
            [
                id: group.id,
                name: group.name?.toString() ?: '(unnamed)',
                parent_server_group_id: group.parent_server_group_id,
                notes: group.notes
            ]
        }
        if (query) {
            String lower = query.toLowerCase()
            items = items.findAll { (it.name ?: '').toString().toLowerCase().contains(lower) }
        }
        items = items.sort { a, b -> (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase() }
        boolean truncated = items.size() > limit
        if (items.size() > limit) {
            items = items.take(limit)
        }
        report('groups', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org, query: query,
            truncated: truncated
        ])
    }

    Map operatorGroup(String profile, String groupId, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        List groups = fetchArray('groups', credentials, timeout, [:], [o: credentials.org])
        Map group = groups.find { it.id?.toString() == groupId.toString() }
        if (!group) {
            return report('group', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                group: groupId, message: 'Group not found'
            ])
        }
        List servers = fetchArray('servers', credentials, timeout, [:], [o: credentials.org, g: groupId])
        List<Map> devices = servers.collect { normalizeDeviceSummary(it) }
            .sort { a, b -> (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase() }
        int deviceLimit = (operatorRules.limits?.devices_max ?: 500) as int
        boolean truncated = devices.size() > deviceLimit
        if (truncated) {
            devices = devices.take(deviceLimit)
        }
        List<Map> items = [[
            id: group.id,
            name: group.name?.toString() ?: '(unnamed)',
            parent_server_group_id: group.parent_server_group_id,
            notes: group.notes,
            devices: devices
        ]]
        report('group', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org, group: groupId,
            truncated: truncated
        ])
    }

    Map operatorDevices(
        String profile,
        String groupId,
        String query,
        String state,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map queryParams = [o: credentials.org]
        if (groupId) {
            queryParams.g = groupId
        }
        List servers = fetchArray('servers', credentials, timeout, [:], queryParams)
        List<Map> items = servers.collect { normalizeDeviceSummary(it) }
        items = filterDevicesByState(items, state)
        if (query) {
            String lower = query.toLowerCase()
            items = items.findAll { (it.name ?: '').toString().toLowerCase().contains(lower) }
        }
        items = items.sort { a, b -> (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase() }
        boolean truncated = items.size() > limit
        if (items.size() > limit) {
            items = items.take(limit)
        }
        report('devices', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            query: query, group: groupId,
            filters: [state: state ?: 'all'],
            truncated: truncated
        ])
    }

    Map operatorDevice(String profile, String reference, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map device = resolveDevice(credentials, reference, timeout)
        report('device', Status.READY, [normalizeDeviceDetail(device)], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org, device: reference
        ])
    }

    Map operatorDevicePackages(
        String profile,
        String reference,
        String state,
        String query,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map device = resolveDevice(credentials, reference, timeout)
        List<Map> packages = fetchDevicePackages(credentials, device.id.toString(), timeout)
        packages = packages.collect { Map pkg ->
            boolean installed = pkg.installed == true
            [
                hostname: device.name,
                host_short_name: shortName(device.name?.toString()),
                package_name: pkg.name,
                package_display_name: pkg.display_name ?: pkg.name,
                package_version: pkg.version,
                installed: installed,
                pending: !installed,
                severity: severityLabel(pkg),
                cve_score: pkg.cve_score,
                cves: pkg.cves instanceof List ? pkg.cves.join(';') : pkg.cves,
                repo: pkg.repo,
                requires_reboot: pkg.requires_reboot == true || device.needs_reboot == true
            ]
        }
        if (state == 'pending') {
            packages = packages.findAll { it.pending }
        } else if (state == 'installed') {
            packages = packages.findAll { it.installed }
        }
        if (query) {
            String lower = query.toLowerCase()
            packages = packages.findAll {
                (it.package_name ?: '').toString().toLowerCase().contains(lower) ||
                    (it.package_display_name ?: '').toString().toLowerCase().contains(lower)
            }
        }
        packages = packages.sort { a, b ->
            (a.package_name ?: '').toString().toLowerCase() <=> (b.package_name ?: '').toString().toLowerCase()
        }
        int totalPackages = packages.size()
        boolean truncated = packages.size() > limit
        if (truncated) {
            packages = packages.take(limit)
        }
        report('device-packages', Status.READY, packages, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            device: reference, query: query, truncated: truncated,
            filters: [state: state ?: 'all'],
            totals: [packages: totalPackages, returned: packages.size()]
        ])
    }

    Map operatorActivity(
        String profile,
        String since,
        String until,
        List<String> requestedEventTypes,
        String deviceRef,
        String policyId,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Instant sinceInstant = parseDateBoundary(since, true)
        Instant untilInstant = parseDateBoundary(until, false)
        Set<String> eventTypes = requestedEventTypes ?
            requestedEventTypes.collect { it.toString() } as Set :
            ([operatorRules.event_types?.patch_applied,
              operatorRules.event_types?.patch_failed,
              operatorRules.event_types?.policy_action] as Set)
        Integer deviceId = null
        if (deviceRef) {
            deviceId = resolveDevice(credentials, deviceRef, timeout).id as Integer
        }
        List<Map> events = fetchEvents(credentials, timeout, sinceInstant, untilInstant)
        List<Map> items = []
        Set<String> matchingEventIds = [] as Set
        events.each { Map event ->
            if (!eventTypes.contains(event.name?.toString())) {
                return
            }
            if (policyId && event.policy_id?.toString() != policyId.toString()) {
                return
            }
            if (deviceId && event.server_id?.toString() != deviceId.toString()) {
                return
            }
            matchingEventIds << (event.id?.toString() ?: "${event.name}:${event.create_time}:${event.server_id}")
            items.addAll(normalizeEventRows(event))
        }
        items = items.sort { a, b -> (b.event_time ?: '') <=> (a.event_time ?: '') }
        int totalRows = items.size()
        boolean truncated = items.size() > limit
        if (truncated) {
            items = items.take(limit)
        }
        report('activity', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            device: deviceRef, policy: policyId, truncated: truncated,
            filters: [since: since, until: until, event: requestedEventTypes],
            totals: [events: matchingEventIds.size(), rows: totalRows, returned: items.size()]
        ])
    }

    Map operatorPatchSummary(
        String profile,
        String since,
        String until,
        String deviceRef,
        String policyId,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Instant sinceInstant = parseDateBoundary(since, true)
        Instant untilInstant = parseDateBoundary(until, false)
        Integer deviceId = null
        if (deviceRef) {
            deviceId = resolveDevice(credentials, deviceRef, timeout).id as Integer
        }
        List<Map> events = fetchEvents(credentials, timeout, sinceInstant, untilInstant)
        List<String> patchTypes = [
            operatorRules.event_types?.patch_applied?.toString(),
            operatorRules.event_types?.patch_failed?.toString()
        ]
        List<Map> rows = []
        Set hostsUpdated = [] as Set
        Set hostsFailed = [] as Set
        Set distinctPackages = [] as Set
        Set policies = [] as Set
        Set operatingSystems = [] as Set
        int packageApplications = 0
        int packageAttempts = 0
        int failedPackages = 0
        int successfulRuns = 0
        int partialRuns = 0
        events.each { Map event ->
            if (!patchTypes.contains(event.name?.toString())) {
                return
            }
            if (policyId && event.policy_id?.toString() != policyId.toString()) {
                return
            }
            if (deviceId && event.server_id?.toString() != deviceId.toString()) {
                return
            }
            boolean failedEvent = event.name?.toString() ==
                operatorRules.event_types?.patch_failed?.toString()
            if (failedEvent) {
                partialRuns++
                hostsFailed << (event.server_name ?: event.server_id)
            } else {
                successfulRuns++
                hostsUpdated << (event.server_name ?: event.server_id)
            }
            List<Map> normalized = normalizeEventRows(event)
            normalized.each { Map row ->
                rows << row
                if (row.package_name) {
                    distinctPackages << row.package_name
                    if (failedEvent) {
                        packageAttempts++
                    } else {
                        packageApplications++
                    }
                    if (row.outcome == 'failed') {
                        failedPackages++
                    }
                }
                if (event.policy_name) {
                    policies << event.policy_name
                }
                if (row.os_name) {
                    operatingSystems << row.os_name
                }
            }
        }
        rows = rows.sort { a, b ->
            ((b.update_time_utc ?: b.event_time) ?: '') <=> ((a.update_time_utc ?: a.event_time) ?: '')
        }
        boolean truncated = rows.size() > limit
        if (truncated) {
            rows = rows.take(limit)
        }
        report('patch-summary', Status.READY, rows, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            device: deviceRef, policy: policyId, truncated: truncated,
            filters: [since: since, until: until],
            totals: [
                hosts_updated: hostsUpdated.size(),
                hosts_failed: hostsFailed.size(),
                package_applications: packageApplications,
                package_attempts: packageAttempts,
                failed_packages: failedPackages,
                distinct_packages: distinctPackages.size(),
                successful_runs: successfulRuns,
                partial_runs: partialRuns,
                policies: policies.size(),
                operating_systems: operatingSystems.size()
            ]
        ])
    }

    Map operatorPolicies(String profile, String query, String type, int limit, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        List policies = fetchArray('policies', credentials, timeout, [:], [o: credentials.org])
        List<Map> items = policies.collect { Map policy ->
            Map configuration = policy.configuration instanceof Map ? (Map) policy.configuration : [:]
            [
                id: policy.id,
                name: policy.name,
                policy_type_name: policy.policy_type_name,
                schedule_time: policy.schedule_time,
                server_groups: policy.server_groups,
                auto_patch: configuration.auto_patch,
                auto_reboot: configuration.auto_reboot
            ]
        }
        if (type && type != 'all') {
            String expected = type == 'patch' ? 'patch' : 'custom'
            items = items.findAll { (it.policy_type_name ?: '').toString().equalsIgnoreCase(expected) }
        }
        if (query) {
            String lower = query.toLowerCase()
            items = items.findAll { (it.name ?: '').toString().toLowerCase().contains(lower) }
        }
        items = items.sort { a, b -> (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase() }
        boolean truncated = items.size() > limit
        if (items.size() > limit) {
            items = items.take(limit)
        }
        report('policies', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org, query: query,
            filters: [type: type ?: 'all'],
            truncated: truncated
        ])
    }

    Map operatorPolicy(String profile, String policyId, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map policy = fetchObject('policy', credentials, timeout, [id: policyId], [o: credentials.org])
        if (!policy) {
            return report('policy', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                policy: policyId, message: 'Policy not found'
            ])
        }
        Map decoded = decodeSchedule(policy)
        Map schedule = decoded.schedule
        Map item = [
            id: policy.id,
            name: policy.name,
            policy_type_name: policy.policy_type_name,
            schedule_time: policy.schedule_time,
            server_groups: policy.server_groups,
            notes: policy.notes,
            configuration: policy.configuration
        ]
        Map extras = [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            policy: policyId, schedule: schedule
        ]
        if (decoded.message) {
            extras.message = decoded.message
        }
        report('policy', Status.READY, [item], extras)
    }

    Map operatorPolicyStats(String profile, String policyId, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        List stats = fetchArray('policy_stats', credentials, timeout, [:], [o: credentials.org])
        List<Map> items = stats.collect { Map entry ->
            [
                policy_id: entry.policy_id,
                policy_name: entry.policy_name,
                policy_type_name: entry.policy_type_name,
                compliant: entry.compliant,
                noncompliant: entry.noncompliant,
                pending: entry.pending
            ]
        }
        if (policyId) {
            items = items.findAll { it.policy_id?.toString() == policyId.toString() }
            if (!items) {
                return report('policy-stats', Status.ERROR, [], [
                    fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                    policy: policyId, message: 'Policy stats not found'
                ])
            }
        }
        report('policy-stats', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org, policy: policyId
        ])
    }

    Map operatorDeviceQueue(
        String profile,
        String reference,
        String policyId,
        String statusFilter,
        int limit,
        int waitSeconds,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map device = resolveDevice(credentials, reference, timeout)
        Map settings = settings()
        int maxWait = Math.min(waitSeconds ?: 0, settings.polling_max_wait_seconds as int)
        int interval = settings.polling_interval_seconds as int
        long deadline = System.currentTimeMillis() + (maxWait * 1000L)
        List<Map> items = []
        Status status = Status.READY
        String message = 'Queue read complete'
        while (true) {
            List queue = fetchArray('server_queues', credentials, timeout,
                [id: device.id.toString()], [o: credentials.org])
            items = queue.collect { Map command -> normalizeQueueItem(command) }
            if (policyId) {
                items = items.findAll { it.policy_id?.toString() == policyId.toString() }
            }
            if (statusFilter) {
                String lower = statusFilter.toLowerCase()
                items = items.findAll { (it.status ?: '').toString().toLowerCase() == lower }
            }
            if (maxWait <= 0 || items.any { isTerminalQueueStatus(it.status?.toString()) }) {
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                status = Status.DEGRADED
                message = 'Queue polling timed out before terminal status'
                break
            }
            Thread.sleep(interval * 1000L)
        }
        if (items.size() > limit) {
            items = items.take(limit)
        }
        report('device-queue', status, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            device: reference, policy: policyId,
            filters: [status: statusFilter, wait_seconds: waitSeconds],
            message: message,
            totals: [commands: items.size()]
        ])
    }

    Map operatorPolicyRun(
        String profile,
        String policyId,
        String deviceRef,
        boolean runAll,
        String confirmAll,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map policy = fetchObject('policy', credentials, timeout, [id: policyId], [o: credentials.org])
        if (!policy) {
            return report('policy-run', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                policy: policyId, message: 'Policy not found'
            ])
        }
        Map change = [
            dry_run: !apply,
            applied: false,
            steps: []
        ]
        if (runAll) {
            if (!apply) {
                change.steps << [action: 'remediateAll', policy_id: policyId]
            } else if (confirmAll != policyId.toString()) {
                throw new IllegalArgumentException('policy-run --all requires matching --confirm-all policy id')
            } else {
                String url = buildUrl('policy_actions', credentials, [id: policyId],
                    [o: credentials.org])
                Map response = writeClient.post(url, AutomoxCredentials.authorizationHeaders(credentials),
                    [action: 'remediateAll'], settings().mutation_timeout_seconds as int,
                    settings().error_body_max as int)
                validateSuccess(response, operatorRules.success_codes?.policy_action ?: [200, 204])
                change.applied = true
            }
        } else {
            Map device = resolveDevice(credentials, deviceRef, timeout)
            change.from = device.name
            if (apply) {
                String url = buildUrl('policy_actions', credentials, [id: policyId],
                    [o: credentials.org])
                Map response = writeClient.post(url, AutomoxCredentials.authorizationHeaders(credentials),
                    [action: 'remediateServer', serverId: device.id],
                    settings().mutation_timeout_seconds as int, settings().error_body_max as int)
                validateSuccess(response, operatorRules.success_codes?.policy_action ?: [200, 204])
                change.applied = true
            } else {
                change.steps << [action: 'remediateServer', policy_id: policyId, device_id: device.id]
            }
        }
        report('policy-run', Status.READY, [[policy_id: policyId, policy_name: policy.name]], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            policy: policyId, device: deviceRef, dry_run: !apply, applied: change.applied, change: change
        ])
    }

    Map operatorWorkletCreate(
        String profile,
        String name,
        File evaluationFile,
        File remediationFile,
        String notes,
        int timeout
    ) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map evalMeta = validateWorkletFile(evaluationFile)
        Map remMeta = validateWorkletFile(remediationFile)
        Map workletRules = operatorRules.worklet instanceof Map ? (Map) operatorRules.worklet : [:]
        Map configuration = [
            auto_patch: false,
            auto_reboot: false,
            os_family: workletRules.os_family ?: 'Linux',
            evaluation_code: evalMeta.content,
            remediation_code: remMeta.content
        ]
        Map payload = [
            name: name,
            organization_id: credentials.org as long,
            policy_type_name: workletRules.policy_type_name ?: 'custom',
            configuration: configuration,
            schedule_days: workletRules.unscheduled_schedule_days ?: 0,
            schedule_weeks_of_month: workletRules.unscheduled_schedule_weeks_of_month ?: 0,
            schedule_months: workletRules.unscheduled_schedule_months ?: 0,
            schedule_time: '00:00',
            notes: notes ?: '',
            server_groups: []
        ]
        Map change = [
            dry_run: !apply,
            applied: false,
            steps: [[action: 'create_policy', name: name]]
        ]
        String createdPolicyId = null
        if (apply) {
            List existingPolicies = fetchArray('policies', credentials, timeout, [:], [o: credentials.org])
            if (existingPolicies.any { it.name?.toString() == name }) {
                throw new IllegalArgumentException("Policy already exists: ${name}")
            }
            Set<String> existingPolicyIds = existingPolicies.collect { it.id?.toString() }.findAll { it } as Set
            String url = buildUrl('policies', credentials, [:], [o: credentials.org])
            Map response = writeClient.post(url, AutomoxCredentials.authorizationHeaders(credentials),
                payload, settings().mutation_timeout_seconds as int, settings().error_body_max as int)
            validateSuccess(response, operatorRules.success_codes?.mutation ?: [200, 201, 204])
            if (response.body) {
                Object parsed = parseJson(response.body)
                if (parsed instanceof Map && parsed.id) {
                    createdPolicyId = parsed.id.toString()
                }
            }
            if (!createdPolicyId) {
                List policies = fetchArray('policies', credentials, timeout, [:], [o: credentials.org])
                Map created = policies.find {
                    it.name?.toString() == name && !existingPolicyIds.contains(it.id?.toString())
                }
                createdPolicyId = created?.id?.toString()
            }
            if (!createdPolicyId) {
                throw new IllegalStateException('Created Worklet policy id could not be resolved')
            }
            change.applied = true
        }
        List<Map> items = [[
            name: name,
            policy_id: createdPolicyId,
            evaluation: evalMeta.subMap(['path', 'size_bytes', 'sha256']),
            remediation: remMeta.subMap(['path', 'size_bytes', 'sha256'])
        ]]
        report('worklet-create', Status.READY, items, [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            policy: createdPolicyId, dry_run: !apply, applied: change.applied, change: change
        ])
    }

    Map operatorPolicyDelete(String profile, String policyId, String confirmName, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map policy = fetchObject('policy', credentials, timeout, [id: policyId], [o: credentials.org])
        if (!policy) {
            return report('policy-delete', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                policy: policyId, message: 'Policy not found'
            ])
        }
        if (confirmName != policy.name?.toString()) {
            throw new IllegalArgumentException('policy-delete requires exact current policy name confirmation')
        }
        Map change = [
            dry_run: !apply,
            applied: false,
            from: policy.name,
            steps: [[action: 'delete_policy', policy_id: policyId, name: policy.name]]
        ]
        if (apply) {
            String url = buildUrl('policy', credentials, [id: policyId], [o: credentials.org])
            Map response = writeClient.delete(url, AutomoxCredentials.authorizationHeaders(credentials),
                settings().mutation_timeout_seconds as int, settings().error_body_max as int)
            validateSuccess(response, operatorRules.success_codes?.policy_delete ?: [200, 204])
            change.applied = true
        }
        report('policy-delete', Status.READY, [[policy_id: policyId, name: policy.name]], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            policy: policyId, dry_run: !apply, applied: change.applied, change: change
        ])
    }

    Map operatorDeviceMove(String profile, String reference, String groupId, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map device = resolveDevice(credentials, reference, timeout)
        String fromGroup = device.server_group_id?.toString() ?: ''
        Map change = [
            dry_run: !apply,
            applied: false,
            from: fromGroup,
            to: groupId,
            steps: [[action: 'update_server_group', device_id: device.id, server_group_id: groupId]]
        ]
        if (apply) {
            String url = buildUrl('server', credentials, [id: device.id.toString()], [o: credentials.org])
            Map response = writeClient.put(url, AutomoxCredentials.authorizationHeaders(credentials),
                [server_group_id: groupId as long], settings().mutation_timeout_seconds as int,
                settings().error_body_max as int)
            validateSuccess(response, operatorRules.device_move?.success_codes ?: [200, 204])
            Map confirmed = fetchObject('server', credentials, timeout, [id: device.id.toString()], [o: credentials.org])
            if (confirmed?.server_group_id?.toString() != groupId.toString()) {
                return report('device-move', Status.ERROR, [], [
                    fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                    device: reference, message: 'Device move confirmation failed'
                ])
            }
            change.applied = true
        }
        report('device-move', Status.READY, [[device_id: device.id, name: device.name]], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            device: reference, group: groupId, dry_run: !apply, applied: change.applied, change: change
        ])
    }

    Map operatorPolicyAddGroup(String profile, String policyId, String groupId, int timeout) {
        String fetchedAt = utcNow()
        AutomoxCredentials.Resolved credentials = requireCredentials(profile)
        Map policy = fetchObject('policy', credentials, timeout, [id: policyId], [o: credentials.org])
        if (!policy) {
            return report('policy-add-group', Status.ERROR, [], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                policy: policyId, message: 'Policy not found'
            ])
        }
        List currentGroups = (policy.server_groups instanceof List ? policy.server_groups : []).collect { it as long }
        if (currentGroups.contains(groupId as long)) {
            return report('policy-add-group', Status.READY, [[policy_id: policyId, server_groups: currentGroups]], [
                fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                policy: policyId, group: groupId, message: 'Group already assigned', dry_run: !apply, applied: false
            ])
        }
        List targetGroups = (currentGroups + [groupId as long]).unique()
        String originalName = policy.name?.toString()
        String renameSuffix = operatorRules.policy_update?.rename_suffix?.toString() ?: '-TMP'
        String tempName = "${originalName}${renameSuffix}"
        Map change = [
            dry_run: !apply,
            applied: false,
            from: currentGroups.join(','),
            to: targetGroups.join(','),
            steps: [
                [action: 'rename', name: tempName],
                [action: 'restore', name: originalName, server_groups: targetGroups]
            ]
        ]
        if (apply) {
            Map firstPayload = buildPolicyUpdatePayload(policy, tempName, currentGroups)
            String url = buildUrl('policy', credentials, [id: policyId], [o: credentials.org])
            Map first = writeClient.put(url, AutomoxCredentials.authorizationHeaders(credentials),
                firstPayload, settings().mutation_timeout_seconds as int, settings().error_body_max as int)
            validateSuccess(first, operatorRules.policy_update?.success_codes ?: [200, 204])
            Map secondPayload = buildPolicyUpdatePayload(policy, originalName, targetGroups)
            Map second = writeClient.put(url, AutomoxCredentials.authorizationHeaders(credentials),
                secondPayload, settings().mutation_timeout_seconds as int, settings().error_body_max as int)
            if (!successCode(second.code, operatorRules.policy_update?.success_codes ?: [200, 204])) {
                return report('policy-add-group', Status.ERROR, [[policy_id: policyId, name: tempName]], [
                    fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
                    policy: policyId, group: groupId,
                    message: "Policy left in renamed state: ${tempName}"
                ])
            }
            change.applied = true
        }
        report('policy-add-group', Status.READY, [[policy_id: policyId, server_groups: targetGroups]], [
            fetched_at: fetchedAt, profile: credentials.id, org: credentials.org,
            policy: policyId, group: groupId, dry_run: !apply, applied: change.applied, change: change
        ])
    }

    static Map loadOperatorRules(File frameworkRoot) {
        Map defaults = [
            api_base_url: 'https://console.automox.com/api',
            default_profile: 'default',
            timeouts: [http_seconds: 10, process_seconds: 15, mutation_seconds: 30],
            limits: [
                response_body_max_characters: 1048576,
                error_body_max_characters: 4096,
                devices_default: 500, devices_max: 500,
                groups_default: 500, groups_max: 500,
                policies_default: 500, policies_max: 500,
                packages_default: 500, packages_max: 500,
                activity_default: 500, activity_max: 500,
                patch_summary_default: 500, patch_summary_max: 500,
                queue_default: 50, queue_max: 200,
                inventory_max: 500, history_max: 50000
            ],
            pagination: [page_size: 500, max_pages: 100],
            polling: [default_interval_seconds: 5, max_wait_seconds: 900],
            success_codes: [read: [200], mutation: [200, 201, 204]],
            queue_terminal_statuses: ['completed', 'failed', 'cancelled', 'error', 'timed_out'],
            policy_update: [
                required_fields: ['name', 'organization_id', 'policy_type_name', 'configuration',
                                  'schedule_days', 'schedule_weeks_of_month', 'schedule_months',
                                  'schedule_time', 'notes', 'server_groups'],
                rename_suffix: '-TMP',
                success_codes: [200, 204]
            ],
            device_move: [success_codes: [200, 204]],
            worklet: [max_file_bytes: 65536, policy_type_name: 'custom', os_family: 'Linux'],
            event_types: [
                patch_applied: 'system.patch.applied',
                patch_failed: 'system.patch.failed',
                policy_action: 'system.policy.action'
            ]
        ]
        (Map) JsonFiles.read(new File(frameworkRoot, 'shared/automox-operator-rules.json'), defaults)
    }

    static String utcNow() {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .format(OffsetDateTime.now(ZoneOffset.UTC))
    }

    private AutomoxCredentials.Resolved requireCredentials(String profile) {
        AutomoxCredentials.Resolved resolved = AutomoxCredentials.resolve(paths, config, operatorRules, profile)
        if (!resolved.apiToken) {
            throw new IllegalStateException('Automox API token unavailable')
        }
        if (!resolved.org) {
            throw new IllegalStateException('Automox organization unavailable')
        }
        resolved
    }

    private Map automoxGet(String pathKey, AutomoxCredentials.Resolved credentials, int timeout, Map query) {
        String url = buildUrl(pathKey, credentials, [:], query)
        http.get(url, AutomoxCredentials.authorizationHeaders(credentials), timeout, settings().response_body_max as int)
    }

    private List fetchArray(
        String pathKey,
        AutomoxCredentials.Resolved credentials,
        int timeout,
        Map pathBindings,
        Map query = [:]
    ) {
        List collected = []
        int pageSize = (operatorRules.pagination?.page_size ?: 500) as int
        int maxPages = (operatorRules.pagination?.max_pages ?: 100) as int
        for (int page = 0; page < maxPages; page++) {
            Map pageQuery = new LinkedHashMap(query)
            pageQuery.limit = pageSize
            pageQuery.page = page
            String url = buildUrl(pathKey, credentials, pathBindings, pageQuery)
            Map response = http.get(url, AutomoxCredentials.authorizationHeaders(credentials),
                timeout, settings().response_body_max as int)
            if (response.code != 200) {
                throw new IllegalStateException(response.error ?: "Automox returned HTTP ${response.code}")
            }
            Object parsed = parseJson(response.body)
            List pageItems = normalizeList(parsed)
            if (!pageItems) {
                break
            }
            collected.addAll(pageItems)
            if (pageItems.size() < pageSize) {
                break
            }
        }
        collected
    }

    private Map fetchObject(
        String pathKey,
        AutomoxCredentials.Resolved credentials,
        int timeout,
        Map pathBindings,
        Map query = [:]
    ) {
        String url = buildUrl(pathKey, credentials, pathBindings, query)
        Map response = http.get(url, AutomoxCredentials.authorizationHeaders(credentials),
            timeout, settings().response_body_max as int)
        if (response.code == 404) {
            return null
        }
        if (response.code != 200) {
            throw new IllegalStateException(response.error ?: "Automox returned HTTP ${response.code}")
        }
        Object parsed = parseJson(response.body)
        parsed instanceof Map ? (Map) parsed : null
    }

    private List<Map> fetchDevicePackages(AutomoxCredentials.Resolved credentials, String deviceId, int timeout) {
        List collected = []
        int pageSize = (operatorRules.pagination?.page_size ?: 500) as int
        int maxPages = (operatorRules.pagination?.max_pages ?: 100) as int
        for (int page = 0; page < maxPages; page++) {
            Map query = [o: credentials.org, limit: pageSize, page: page]
            String url = buildUrl('server_packages', credentials, [id: deviceId], query)
            Map response = http.get(url, AutomoxCredentials.authorizationHeaders(credentials),
                timeout, settings().response_body_max as int)
            if (response.code != 200) {
                throw new IllegalStateException(response.error ?: "Automox returned HTTP ${response.code}")
            }
            Object parsed = parseJson(response.body)
            List pageItems = []
            if (parsed instanceof List) {
                pageItems = parsed
            } else if (parsed instanceof Map) {
                if (parsed.packages instanceof List) {
                    pageItems = (List) parsed.packages
                } else if (parsed.data instanceof List) {
                    pageItems = (List) parsed.data
                } else {
                    pageItems = [parsed]
                }
            }
            if (!pageItems) {
                break
            }
            collected.addAll(pageItems)
            if (pageItems.size() < pageSize) {
                break
            }
        }
        collected
    }

    private List<Map> fetchEvents(
        AutomoxCredentials.Resolved credentials,
        int timeout,
        Instant since,
        Instant until
    ) {
        Set seen = [] as Set
        List<Map> collected = []
        int pageSize = (operatorRules.pagination?.page_size ?: 500) as int
        int maxPages = (operatorRules.pagination?.max_pages ?: 100) as int
        int historyMax = settings().history_max as int
        for (int page = 0; page < maxPages; page++) {
            Map query = [o: credentials.org, limit: pageSize, page: page]
            String url = buildUrl('events', credentials, [:], query)
            Map response = http.get(url, AutomoxCredentials.authorizationHeaders(credentials),
                timeout, settings().response_body_max as int)
            if (response.code != 200) {
                throw new IllegalStateException(response.error ?: "Automox returned HTTP ${response.code}")
            }
            List pageItems = normalizeList(parseJson(response.body))
            if (!pageItems) {
                break
            }
            boolean reachedLowerBound = false
            pageItems.each { Map event ->
                Instant eventTime = parseEventInstant(event.create_time?.toString())
                if (eventTime && eventTime.isBefore(since)) {
                    reachedLowerBound = true
                    return
                }
                if (eventTime && eventTime.isAfter(until)) {
                    return
                }
                String eventKey = event.id?.toString() ?:
                    "${event.name}|${event.create_time}|${event.server_id}|${event.policy_id}|${event.data}"
                if (!seen.contains(eventKey)) {
                    seen << eventKey
                    collected << event
                }
            }
            if (reachedLowerBound || pageItems.size() < pageSize || collected.size() >= historyMax) {
                break
            }
        }
        if (collected.size() > historyMax) {
            collected = collected.take(historyMax)
        }
        collected.sort { a, b -> (parseEventInstant(b.create_time?.toString()) ?: Instant.EPOCH) <=>
            (parseEventInstant(a.create_time?.toString()) ?: Instant.EPOCH) }
        collected
    }

    private Map resolveDevice(AutomoxCredentials.Resolved credentials, String reference, int timeout) {
        if (NUMERIC.matcher(reference).matches()) {
            Map device = fetchObject('server', credentials, timeout, [id: reference], [o: credentials.org])
            if (!device) {
                throw new IllegalArgumentException("Device not found: ${reference}")
            }
            return device
        }
        String domain = credentials.domain ?: settings().domain ?: ''
        String fqdn = reference
        if (!reference.contains('.') && !IPV4.matcher(reference).matches() && domain) {
            fqdn = "${reference}.${domain}"
        }
        String shortName = reference.contains('.') ? reference.split('\\.')[0] : reference
        List servers = fetchArray('servers', credentials, timeout, [:], [o: credentials.org])
        List exact = servers.findAll { (it.name ?: '').toString().equalsIgnoreCase(fqdn) }
        if (exact.size() == 1) {
            return exact[0] as Map
        }
        if (exact.size() > 1) {
            throw new IllegalArgumentException("Ambiguous device reference: ${reference}")
        }
        List prefix = servers.findAll {
            (it.name ?: '').toString().toLowerCase().startsWith("${shortName.toLowerCase()}.")
        }
        if (prefix.size() == 1) {
            return prefix[0] as Map
        }
        if (prefix.size() > 1) {
            throw new IllegalArgumentException("Ambiguous device reference: ${reference}")
        }
        List contains = servers.findAll {
            (it.name ?: '').toString().toLowerCase().contains(shortName.toLowerCase())
        }
        if (contains.size() == 1) {
            return contains[0] as Map
        }
        if (contains.size() > 1) {
            throw new IllegalArgumentException("Ambiguous device reference: ${reference}")
        }
        throw new IllegalArgumentException("Device not found: ${reference}")
    }

    private Map decodeSchedule(Map policy) {
        Map scheduleRules = operatorRules.schedule instanceof Map ? (Map) operatorRules.schedule : [:]
        Map dayBits = scheduleRules.day_bits instanceof Map ? (Map) scheduleRules.day_bits : [:]
        Map weekBits = scheduleRules.week_bits instanceof Map ? (Map) scheduleRules.week_bits : [:]
        int scheduleDays = (policy.schedule_days ?: 0) as int
        int scheduleWeeks = (policy.schedule_weeks_of_month ?: 0) as int
        List<String> days = []
        dayBits.each { bit, label ->
            if ((scheduleDays & (bit as int)) != 0) {
                days << label.toString()
            }
        }
        List<Integer> weeks = []
        weekBits.each { bit, week ->
            if ((scheduleWeeks & (bit as int)) != 0) {
                weeks << (week as int)
            }
        }
        Map configuration = policy.configuration instanceof Map ? (Map) policy.configuration : [:]
        List unreliable = operatorRules.unreliable_fields instanceof List ? (List) operatorRules.unreliable_fields : []
        Map schedule = [
            days: days,
            weeks_of_month: weeks,
            time: policy.schedule_time,
            use_scheduled_timezone: configuration.use_scheduled_timezone == true,
            next_remediation_reliable: !unreliable.contains('next_remediation')
        ]
        List<String> messages = []
        if (scheduleDays != 0 && !days) {
            messages << "schedule_days=${scheduleDays}"
        }
        if (scheduleWeeks != 0 && !weeks) {
            messages << "schedule_weeks_of_month=${scheduleWeeks}"
        }
        [schedule: schedule, message: messages ? messages.join('; ') : null]
    }

    private Map buildPolicyUpdatePayload(Map policy, String name, List serverGroups) {
        Map payload = [:]
        List required = operatorRules.policy_update?.required_fields instanceof List ?
            (List) operatorRules.policy_update.required_fields :
            ['name', 'organization_id', 'policy_type_name', 'configuration', 'schedule_days',
             'schedule_weeks_of_month', 'schedule_months', 'schedule_time', 'notes', 'server_groups']
        required.each { field ->
            switch (field.toString()) {
                case 'name':
                    payload.name = name
                    break
                case 'server_groups':
                    payload.server_groups = serverGroups
                    break
                default:
                    payload[field.toString()] = policy[field.toString()]
            }
        }
        payload
    }

    private Map validateWorkletFile(File file) {
        Path workspace = paths.root.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        Path supplied = file.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(supplied)) {
            throw new IllegalArgumentException("Invalid worklet file: ${file}")
        }
        Path target = file.canonicalFile.toPath()
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid worklet file: ${file}")
        }
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("Worklet file must be inside workspace: ${file}")
        }
        long maxBytes = (operatorRules.worklet?.max_file_bytes ?: 65536) as long
        long size = Files.size(target)
        if (size > maxBytes) {
            throw new IllegalArgumentException("Worklet file exceeds size limit: ${file}")
        }
        byte[] bytes = Files.readAllBytes(target)
        if (bytes.contains((byte) 0)) {
            throw new IllegalArgumentException("Worklet file contains NUL bytes: ${file}")
        }
        String content
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Worklet file must be valid UTF-8: ${file}")
        }
        String sha256 = MessageDigest.getInstance('SHA-256').digest(bytes)
            .collect { String.format('%02x', it) }.join('')
        [
            path: workspace.relativize(target).toString(),
            size_bytes: size,
            sha256: sha256,
            content: content
        ]
    }

    private List<Map> normalizeEventRows(Map event) {
        String eventName = event.name?.toString()
        Map data = event.data instanceof Map ? (Map) event.data : [:]
        Instant eventTime = parseEventInstant(event.create_time?.toString())
        String eventTimeText = eventTime ? DateTimeFormatter.ISO_INSTANT.format(eventTime) : event.create_time?.toString()
        String hostname = data.systemname ?: event.server_name
        String hostShort = shortName(hostname?.toString())
        String updateDate = eventTime ? LocalDate.ofInstant(eventTime, ZoneOffset.UTC).toString() : ''
        String updateTimeUtc = eventTime ? DateTimeFormatter.ofPattern('HH:mm:ss').withZone(ZoneOffset.UTC).format(eventTime) : ''
        if (eventName == operatorRules.event_types?.policy_action?.toString()) {
            return [[
                event_id: event.id,
                event_type: eventName,
                event_time: eventTimeText,
                hostname: hostname,
                host_short_name: hostShort,
                host_ip: data.ip,
                os_name: data.os,
                outcome: data.status?.toString() == '0' ? 'success' : 'failed',
                message: data.text
            ]]
        }
        boolean failedEvent = eventName == operatorRules.event_types?.patch_failed?.toString()
        String patchSource = data.patches?.toString() ?: data.failure?.toString()
        List<Map> patchRows = parsePatchList(patchSource)
        Set<String> failedPackageNames = failedEvent ?
            extractFailedPackageNames(data.failure?.toString()) :
            [] as Set
        if (failedEvent && !data.patches && !failedPackageNames) {
            failedPackageNames.addAll(patchRows*.name.findAll { it }*.toString())
        }
        if (!patchRows) {
            String outcome = failedEvent ? 'failed' : 'applied'
            return [[
                event_id: event.id,
                event_type: eventName,
                event_time: eventTimeText,
                update_date: updateDate,
                update_time_utc: updateTimeUtc,
                hostname: hostname,
                host_short_name: hostShort,
                host_ip: data.ip,
                os_name: data.os,
                outcome: outcome,
                message: data.text
            ]]
        }
        patchRows.collect { Map patch ->
            [
                event_id: event.id,
                event_type: eventName,
                event_time: eventTimeText,
                update_date: updateDate,
                update_time_utc: updateTimeUtc,
                hostname: hostname,
                host_short_name: hostShort,
                host_ip: data.ip,
                os_name: data.os,
                package_name: patch.name,
                package_display_name: patch.display_name ?: patch.name,
                package_version: patch.version,
                requires_reboot: patch.requires_reboot,
                repo: patch.repo,
                outcome: failedEvent ?
                    (failedPackageNames.contains(patch.name?.toString()) ? 'failed' : 'attempted') :
                    'applied'
            ]
        }
    }

    private static Set<String> extractFailedPackageNames(String failure) {
        Set<String> names = [] as Set
        if (!failure) {
            return names
        }
        def matcher = failure =~ /message="([^"]+?) failed to update\/install"/
        while (matcher.find()) {
            matcher.group(1).split(/[\s,]+/).findAll { it }.each { names << it }
        }
        names
    }

    private static List<Map> parsePatchList(String raw) {
        if (!raw?.trim()) {
            return []
        }
        List<String> tokens = []
        def quoted = raw =~ /"([^"]+)"/
        while (quoted.find()) {
            tokens << quoted.group(1)
        }
        if (!tokens) {
            tokens = raw.split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/)
                .collect { it.trim() }
                .findAll { it }
        }
        List<Map> patches = []
        tokens.each { token ->
            String trimmed = token.trim()
            if (!trimmed) {
                return
            }
            trimmed = trimmed.replaceAll(/^"|"$/, '')
            List parts = trimmed.split('\\|').toList()
            if (parts.size() >= 2) {
                patches << [
                    name: parts[0],
                    version: parts.size() > 1 ? parts[1] : '',
                    display_name: parts.size() > 2 ? parts[2] : parts[0],
                    repo: parts.size() > 3 ? parts[3] : '',
                    requires_reboot: parts.size() > 4 ? parts[4] == '1' : false
                ]
            } else {
                patches << [name: trimmed, display_name: trimmed]
            }
        }
        patches
    }

    private Map normalizeDeviceSummary(Map device) {
        [
            id: device.id,
            name: device.name,
            connected: deviceConnected(device),
            compliant: device.compliant,
            needs_reboot: device.needs_reboot,
            server_group_id: device.server_group_id,
            os_name: device.os_name,
            os_family: device.os_family,
            last_checkin_time: device.last_checkin_time,
            patches: device.patches
        ]
    }

    private Map normalizeDeviceDetail(Map device) {
        Map summary = normalizeDeviceSummary(device)
        summary.ip_addrs = device.ip_addrs
        summary.detail = device.detail
        summary.timezone = device.timezone
        summary
    }

    private static Map normalizeQueueItem(Map command) {
        String status = command.status?.toString() ?: (command.response ? 'completed' : 'pending')
        [
            id: command.id,
            command_type_name: command.command_type_name,
            policy_id: command.policy_id,
            status: status,
            create_time: command.create_time,
            exec_time: command.exec_time,
            response_time: command.response_time,
            response: command.response
        ]
    }

    private boolean isTerminalQueueStatus(String status) {
        if (!status) {
            return false
        }
        List terminal = operatorRules.queue_terminal_statuses instanceof List ?
            (List) operatorRules.queue_terminal_statuses : []
        terminal*.toString()*.toLowerCase().contains(status.toLowerCase())
    }

    private static boolean deviceConnected(Map device) {
        if (device.containsKey('connected')) {
            return device.connected == true
        }
        Map detail = device.detail instanceof Map ? (Map) device.detail : [:]
        if (detail.containsKey('CONNECTED')) {
            return detail.CONNECTED == true
        }
        true
    }

    private static List<Map> filterDevicesByState(List<Map> items, String state) {
        if (!state || state == 'all') {
            return items
        }
        if (state == 'connected') {
            return items.findAll { it.connected == true }
        }
        if (state == 'disconnected') {
            return items.findAll { it.connected == false }
        }
        items
    }

    private static String shortName(String hostname) {
        hostname?.contains('.') ? hostname.split('\\.')[0] : hostname
    }

    private static String severityLabel(Map pkg) {
        String score = pkg.cve_score?.toString()
        if (!score) {
            return pkg.cves ? 'unknown' : 'no_known_cves'
        }
        BigDecimal value = new BigDecimal(score)
        if (value >= 9.0) {
            return 'critical'
        }
        if (value >= 7.0) {
            return 'high'
        }
        if (value >= 4.0) {
            return 'medium'
        }
        if (value > 0) {
            return 'low'
        }
        'no_known_cves'
    }

    private String buildUrl(
        String pathKey,
        AutomoxCredentials.Resolved credentials,
        Map pathBindings,
        Map query
    ) {
        Map apiPaths = operatorRules.api_paths instanceof Map ? (Map) operatorRules.api_paths : [:]
        String template = apiPaths[pathKey]?.toString() ?: "/${pathKey}"
        pathBindings.each { key, value ->
            template = template.replace("{${key}}", value.toString())
        }
        String base = credentials.apiBaseUrl ?: settings().api_base_url ?: operatorRules.api_base_url
        Map encodedQuery = new LinkedHashMap()
        query.each { key, value ->
            if (value != null && value.toString()) {
                encodedQuery[key] = URLEncoder.encode(value.toString(), 'UTF-8')
            }
        }
        String queryString = encodedQuery.collect { k, v -> "${k}=${v}" }.join('&')
        "${base}${template}${queryString ? '?' + queryString : ''}"
    }

    private Object parseJson(String body) {
        if (!body?.trim()) {
            return null
        }
        slurper.parseText(body)
    }

    private static List normalizeList(Object parsed) {
        if (parsed instanceof List) {
            return parsed
        }
        if (parsed instanceof Map && parsed.data instanceof List) {
            return (List) parsed.data
        }
        parsed ? [parsed] : []
    }

    private static Instant parseEventInstant(String value) {
        if (!value) {
            return null
        }
        try {
            if (value.contains('T')) {
                return Instant.parse(value)
            }
            return OffsetDateTime.parse(value.replace(' ', 'T') + 'Z').toInstant()
        } catch (Exception ignored) {
            null
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

    private static void validateSuccess(Map response, List codes) {
        if (!successCode(response.code as int, codes)) {
            throw new IllegalStateException(response.error ?: "Automox returned HTTP ${response.code}")
        }
    }

    private static boolean successCode(int code, List codes) {
        (codes ?: [200]).collect { it as int }.contains(code)
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
