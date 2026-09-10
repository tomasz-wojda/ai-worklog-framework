package ai.worklog.framework

import ai.worklog.framework.adapters.AutomoxAdapter
import ai.worklog.framework.adapters.AutomoxCredentials
import ai.worklog.framework.adapters.JsonWriteHttp
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.automox.AutomoxCsvRenderer
import ai.worklog.framework.automox.AutomoxOperatorReport
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.commands.AutomoxCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

class AutomoxOperatorTest extends GroovyTestCase {
    private File repository
    private File workspace
    private FrameworkPaths paths
    private Map rules
    private Map schema

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-automox-test')
        new File(workspace, 'integrations/automox').mkdirs()
        paths = new FrameworkPaths(workspace)
        rules = AutomoxAdapter.loadOperatorRules(repository)
        schema = (Map) JsonFiles.read(new File(repository, 'schemas/automox-operator-report.schema.json'), [:])
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testProfilesBlockedWithoutCredentials() {
        Map report = automoxAdapter([:]).operatorProfiles()
        assertEquals(Status.BLOCKED, report.status)
        assertTrue(report.message.contains('No automox.properties found'))
        assertEquals([], validateReport(report))
    }

    void testProfilesExposeRedactedBooleansOnly() {
        writeProperties(defaultProperties())
        Map report = automoxAdapter([:]).operatorProfiles()
        assertEquals(Status.READY, report.status)
        Map item = report.items[0]
        assertEquals('default', item.id)
        assertEquals('121224', item.org)
        assertTrue(item.has_api_token)
        assertTrue(item.has_enrollment_key)
        assertEquals(['id', 'org', 'api_base_url', 'domain', 'source', 'has_api_token', 'has_enrollment_key'] as Set, item.keySet())
        String json = JsonOutput.toJson(report)
        assertFalse(json.contains('secret-api-token-value'))
        assertFalse(json.contains('enrollment-secret-key'))
        assertEquals([], validateReport(report))
    }

    void testOrganizationOverridePrecedenceAndSource() {
        writeProperties(defaultProperties())
        AutomoxCredentials.Resolved resolved = AutomoxCredentials.resolve(
            paths,
            [:],
            rules,
            'default',
            '114895'
        )
        assertEquals('114895', resolved.org)
        assertEquals('argument', resolved.sources.org)
    }

    void testOrgsMarksActiveOrganizationAndAllowsTokenOnlyDiscovery() {
        Closure handler = { method, url, headers, timeout, max ->
            response([
                [id: 114895, name: 'Organization A', device_count: 10],
                [id: 121224, name: 'Organization B', device_count: 20]
            ])
        }
        writeProperties(defaultProperties())
        Map configured = automoxAdapter([:], handler).operatorOrgs('default', 5)
        assertEquals(Status.READY, configured.status)
        assertEquals([false, true], configured.items*.active)
        AutomoxOperatorReport rendered = AutomoxOperatorReport.fromPayload(configured)
        assertTrue(rendered.renderHuman(new Redaction(repository)).contains('Active org: 121224'))

        writeProperties('default.api_token=secret-api-token-value\n')
        Map discovery = automoxAdapter([:], handler).operatorOrgs('default', 5)
        assertEquals(Status.READY, discovery.status)
        assertFalse(discovery.items.any { it.active })
        assertEquals('No active Automox organization configured', discovery.message)
    }

    void testOrganizationOverrideIsValidatedAndUsedByScopedReads() {
        writeProperties(defaultProperties())
        List<String> urls = []
        Closure handler = { method, url, headers, timeout, max ->
            urls << url
            if (url.contains('/orgs')) {
                return response([
                    [id: 114895, name: 'Organization A'],
                    [id: 121224, name: 'Organization B']
                ])
            }
            if (url.contains('/policies')) {
                return response([])
            }
            response([])
        }
        AutomoxAdapter adapter = automoxAdapter([:], handler).selectOrg('114895')
        adapter.validateOrgOverride('default', 5)
        Map report = adapter.operatorPolicies('default', null, 'all', 50, 5)
        assertEquals('114895', report.org)
        assertTrue(urls.any { it.contains('/orgs') })
        assertTrue(urls.any { it.contains('/policies') && it.contains('o=114895') })
    }

    void testInaccessibleOrganizationStopsBeforeMutation() {
        writeProperties(defaultProperties())
        int writes = 0
        AutomoxAdapter adapter = automoxAdapter(
            [:],
            { method, url, headers, timeout, max ->
                response([[id: 121224, name: 'Organization B']])
            },
            { method, url, headers, payload, timeout, max ->
                writes++
                [code: 204, body: '', error: '']
            },
            true
        ).selectOrg('114895')
        String message = shouldFail(IllegalArgumentException) {
            adapter.validateOrgOverride('default', 5)
        }
        assertTrue(message.contains('114895'))
        assertEquals(0, writes)
    }

    void testAutomoxActionsDeclareValidatedOrganizationOption() {
        CommandContract contract = CommandContract.load(repository)
        List<Map> actions = contract.children(['service', 'automox'])
        actions.findAll { it.name != 'profiles' }.each { Map action ->
            assertTrue("${action.name} missing --org", action.options.any { it.name == '--org' })
            ParsedArguments parsed = new ArgumentParser(contract).parse(
                'service automox',
                action,
                requiredArguments(action) + ['--org', '114895'],
                rules
            )
            assertEquals('114895', parsed.value('--org'))
        }
    }

    void testLegacyTokenAndSetkeyParsedWithoutExecution() {
        File serviceDir = paths.serviceDir('automox')
        new File(serviceDir, 'token').text = '# comment\nlegacy-token-value\n'
        new File(serviceDir, 'server-id').text = "AUTOMOX_SETKEY='legacy-setkey-value'\n"
        Map report = automoxAdapter([:]).operatorProfiles()
        assertEquals(Status.READY, report.status)
        Map item = report.items[0]
        assertTrue(item.has_api_token)
        assertTrue(item.has_enrollment_key)
        assertEquals('legacy', item.source.api_token)
        assertEquals('legacy', item.source.enrollment_key)
        AutomoxCredentials.Resolved resolved = AutomoxCredentials.resolve(paths, [:], rules, 'default')
        assertEquals('legacy-token-value', resolved.apiToken)
        assertEquals('legacy-setkey-value', resolved.enrollmentKey)
    }

    void testMissingCredentialsBlocksMutations() {
        writeProperties('default.org=121224\n')
        shouldFail(IllegalStateException) {
            automoxAdapter([:]).operatorDevice('default', '100', 5)
        }
    }

    void testDeviceResolutionByNumericId() {
        writeProperties(defaultProperties())
        Map report = automoxAdapter([:], serverRouter([server(100, 'host.example.internal')])).operatorDevice('default', '100', 5)
        assertEquals(Status.READY, report.status)
        assertEquals(100, report.items[0].id)
    }

    void testDeviceResolutionByFqdnAndShortName() {
        writeProperties(defaultProperties())
        List servers = [
            server(101, 'alpha.example.internal', true),
            server(102, 'beta.example.internal', false)
        ]
        AutomoxAdapter deviceAdapter = automoxAdapter([:], serverRouter(servers))
        assertEquals(101, deviceAdapter.operatorDevice('default', 'alpha.example.internal', 5).items[0].id)
        assertEquals(101, deviceAdapter.operatorDevice('default', 'alpha', 5).items[0].id)
    }

    void testDeviceResolutionPaginatesBeyondFiveHundredDevices() {
        writeProperties(defaultProperties())
        List servers = (1..500).collect { int id -> server(id, "host-${id}.example.internal") }
        servers << server(501, 'target.example.internal')
        int requests = 0
        Closure handler = { method, url, headers, timeout, max ->
            requests++
            int page = (url =~ /page=(\d+)/)[0][1] as int
            int pageSize = (url =~ /limit=(\d+)/)[0][1] as int
            int from = page * pageSize
            List slice = servers.size() > from ?
                servers[from..<Math.min(from + pageSize, servers.size())] :
                []
            response(slice)
        }
        AutomoxAdapter adapter = automoxAdapter([:], handler)
        Map report = adapter.operatorDevice('default', 'target', 5)
        assertEquals(501, report.items[0].id)
        assertEquals(2, requests)
    }

    void testDeviceResolutionAmbiguityAndNotFound() {
        writeProperties(defaultProperties())
        List servers = [
            server(201, 'alpha.one.example.internal'),
            server(202, 'alpha.two.example.internal')
        ]
        AutomoxAdapter deviceAdapter = automoxAdapter([:], serverRouter(servers))
        shouldFail(IllegalArgumentException) {
            deviceAdapter.operatorDevice('default', 'alpha', 5)
        }
        shouldFail(IllegalArgumentException) {
            deviceAdapter.operatorDevice('default', 'missing-host', 5)
        }
    }

    void testDevicesConnectedFilters() {
        writeProperties(defaultProperties())
        List servers = [
            server(301, 'online.example.internal', true),
            server(302, 'offline.example.internal', false, [detail: [CONNECTED: false]])
        ]
        AutomoxAdapter deviceAdapter = automoxAdapter([:], serverRouter(servers))
        Map connected = deviceAdapter.operatorDevices('default', null, null, 'connected', 50, 5)
        Map disconnected = deviceAdapter.operatorDevices('default', null, null, 'disconnected', 50, 5)
        assertEquals(['online.example.internal'], connected.items*.name)
        assertEquals(['offline.example.internal'], disconnected.items*.name)
        assertEquals('connected', connected.filters.state)
    }

    void testDevicePackagesPaginationAndPendingState() {
        writeProperties(defaultProperties())
        Map rulesOverride = [pagination: [page_size: 2, max_pages: 100]]
        List packages = [
            packageRow('alpha', '1.0', true),
            packageRow('beta', '2.0', false),
            packageRow('gamma', '3.0', false)
        ]
        int packageRequests = 0
        Closure readHandler = { method, url, headers, timeout, max ->
            if (url.contains('/packages')) {
                packageRequests++
                return packageRouter(packages).call(method, url, headers, timeout, max)
            }
            if (url.contains('/servers/')) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        }
        AutomoxAdapter adapter = automoxAdapter([:], readHandler, null, false, rulesOverride)
        Map all = adapter.operatorDevicePackages('default', '100', 'all', null, 50, 5)
        assertEquals(3, all.items.size())
        assertEquals(2, packageRequests)
        packageRequests = 0
        Map pending = adapter.operatorDevicePackages('default', '100', 'pending', null, 50, 5)
        assertEquals(2, pending.items.size())
        assertTrue(pending.items.every { it.pending && !it.installed })
        assertEquals(2, packageRequests)
        assertEquals([], validateReport(all))
    }

    void testDateBoundaryUtcAndOffset() {
        writeProperties(defaultProperties())
        AutomoxAdapter adapter = automoxAdapter([:], eventsRouter([]))
        Instant start = adapter.parseDateBoundary('2026-09-01', true)
        Instant end = adapter.parseDateBoundary('2026-09-01', false)
        Instant offset = adapter.parseDateBoundary('2026-09-01T12:30:00+02:00', true)
        assertEquals('2026-09-01T00:00:00Z', start.toString())
        assertEquals('2026-09-01T23:59:59.999Z', end.toString())
        assertEquals('2026-09-01T10:30:00Z', offset.toString())
    }

    void testRepeatablePatchEventExpandsToMultipleRows() {
        writeProperties(defaultProperties())
        String patches = 'one|1.0|One|repo|0,two|2.0|Two|repo|0'
        List events = [
            event(99, 'system.patch.applied', '2026-09-02T12:00:00Z', 100, 'host.example.internal',
                [patches: patches, ip: '10.0.0.1', os: 'Linux'])
        ]
        Map report = automoxAdapter([:], eventsRouter(events)).operatorActivity(
            'default', '2026-09-01', '2026-09-03', ['system.patch.applied'], null, null, 50, 5
        )
        assertEquals(2, report.items.size())
        assertEquals([99, 99], report.items*.event_id)
        assertEquals(['one', 'two'] as Set, report.items*.package_name as Set)
    }

    void testActivityPaginationDeduplicationAndFilters() {
        writeProperties(defaultProperties())
        Map rulesOverride = [pagination: [page_size: 2, max_pages: 100]]
        List events = [
            event(1, 'system.patch.applied', '2026-09-02T10:00:00Z', 100, 'host.example.internal') - [id: 1],
            event(1, 'system.patch.applied', '2026-09-02T10:00:00Z', 100, 'host.example.internal') - [id: 1],
            event(2, 'system.patch.failed', '2026-09-01T08:00:00Z', 100, 'host.example.internal'),
            event(3, 'system.policy.action', '2026-08-31T08:00:00Z', 200, 'other.example.internal', [status: '0'])
        ]
        AutomoxAdapter adapter = automoxAdapter([:], eventsRouter(events), null, false, rulesOverride)
        Map report = adapter.operatorActivity(
            'default', '2026-09-01', '2026-09-02', null, null, null, 50, 5
        )
        assertEquals(2, report.items.size())
        assertEquals(['system.patch.applied', 'system.patch.failed'] as Set, report.items*.event_type as Set)
        assertEquals([], validateReport(report))
    }

    void testPatchAppliedQuotedPackageParsing() {
        writeProperties(defaultProperties())
        String patches = '"pkg-a|1.0|Display A|repo-a|1","pkg-b|2.0|Display B|repo-b|0"'
        List events = [
            event(10, 'system.patch.applied', '2026-09-02T12:00:00Z', 100, 'host.example.internal',
                [patches: patches, ip: '10.0.0.1', os: 'Ubuntu'])
        ]
        Map report = automoxAdapter([:], eventsRouter(events)).operatorActivity(
            'default', '2026-09-01', '2026-09-03', ['system.patch.applied'], null, null, 50, 5
        )
        assertEquals(2, report.items.size())
        assertEquals(['pkg-a', 'pkg-b'] as Set, report.items*.package_name as Set)
        assertEquals(['Display A', 'Display B'] as Set, report.items*.package_display_name as Set)
        assertTrue(report.items.every { it.outcome == 'applied' })
    }

    void testPatchFailedFailureFieldParsing() {
        writeProperties(defaultProperties())
        String failure = 'failed-pkg|9.9|Failed Display|main|1'
        List events = [
            event(11, 'system.patch.failed', '2026-09-02T13:00:00Z', 100, 'host.example.internal',
                [failure: failure, ip: '10.0.0.2', os: 'Windows'])
        ]
        Map report = automoxAdapter([:], eventsRouter(events)).operatorPatchSummary(
            'default', '2026-09-01', '2026-09-03', null, null, 50, 5
        )
        assertEquals('failed-pkg', report.items[0].package_name)
        assertEquals('failed', report.items[0].outcome)
    }

    void testPolicyActionStatusZeroIsSuccess() {
        writeProperties(defaultProperties())
        List events = [
            event(12, 'system.policy.action', '2026-09-02T14:00:00Z', 100, 'host.example.internal',
                [status: '0', text: 'completed', ip: '10.0.0.3', os: 'Linux'])
        ]
        Map report = automoxAdapter([:], eventsRouter(events)).operatorActivity(
            'default', '2026-09-01', '2026-09-03', ['system.policy.action'], null, null, 50, 5
        )
        assertEquals('success', report.items[0].outcome)
    }

    void testPatchSummaryTotalsWithoutInventoryEnrichment() {
        writeProperties(defaultProperties())
        List events = [
            event(20, 'system.patch.applied', '2026-09-02T10:00:00Z', 100, 'one.example.internal',
                [patches: 'pkg|1.0|Pkg|repo|0', ip: '10.0.0.1', os: 'Ubuntu'], 'Policy A'),
            event(21, 'system.patch.failed', '2026-09-02T11:00:00Z', 101, 'two.example.internal',
                [failure: 'bad|2.0|Bad|repo|0', ip: '10.0.0.2', os: 'Windows'], 'Policy B')
        ]
        Map report = automoxAdapter([:], eventsRouter(events)).operatorPatchSummary(
            'default', '2026-09-01', '2026-09-03', null, null, 50, 5
        )
        assertEquals(1, report.totals.hosts_updated)
        assertEquals(1, report.totals.hosts_failed)
        assertEquals(1, report.totals.package_applications)
        assertEquals(1, report.totals.package_attempts)
        assertEquals(1, report.totals.failed_packages)
        assertEquals(1, report.totals.successful_runs)
        assertEquals(1, report.totals.partial_runs)
        assertEquals(2, report.totals.distinct_packages)
        assertEquals(2, report.totals.policies)
        assertEquals(2, report.totals.operating_systems)
        assertFalse(report.items.any { it.containsKey('current_inventory') })
    }

    void testCsvRendererRfc4180AndFormulaProtection() {
        List<String> columns = ['hostname', 'package_name', 'package_version']
        List<Map> rows = [
            [hostname: 'host', package_name: 'safe', package_version: '1.0'],
            [hostname: 'quoted,host', package_name: '=SUM(1+1)', package_version: '2"beta']
        ]
        String csv = AutomoxCsvRenderer.render(columns, rows)
        List<String> lines = csv.readLines()
        assertEquals('hostname,package_name,package_version', lines[0])
        assertEquals('host,safe,1.0', lines[1])
        assertEquals('"quoted,host",\'=SUM(1+1),"2""beta"', lines[2])
    }

    void testScheduleDayBitDecoding() {
        writeProperties(defaultProperties())
        Map policy = [
            id: 900,
            name: 'Patch Policy',
            policy_type_name: 'patch',
            schedule_days: 42,
            schedule_weeks_of_month: 0,
            schedule_time: '03:30',
            configuration: [use_scheduled_timezone: true],
            server_groups: [1],
            notes: 'notes'
        ]
        Map report = automoxAdapter([:], policyRouter(policy)).operatorPolicy('default', '900', 5)
        assertEquals(['Mon', 'Wed', 'Fri'], report.schedule.days)
        assertEquals([], report.schedule.weeks_of_month)
        assertEquals('03:30', report.schedule.time)
        assertTrue(report.schedule.use_scheduled_timezone)
        assertFalse(report.schedule.next_remediation_reliable)
        assertEquals([], validateReport(report))
    }

    void testScheduleWeekBitDecoding() {
        writeProperties(defaultProperties())
        Map policy = [
            id: 900,
            name: 'Patch Policy',
            policy_type_name: 'patch',
            schedule_days: 0,
            schedule_weeks_of_month: 10,
            schedule_time: '03:30',
            configuration: [:],
            server_groups: [1],
            notes: 'notes'
        ]
        Map report = automoxAdapter([:], policyRouter(policy)).operatorPolicy('default', '900', 5)
        assertEquals([2, 4], report.schedule.weeks_of_month)
    }

    void testDeviceQueueFilteringTerminalAndTimeoutWithoutSleep() {
        writeProperties(defaultProperties())
        AutomoxAdapter adapter = automoxAdapter([:], queueRouter())
        Map filtered = adapter.operatorDeviceQueue('default', '100', '55', 'pending', 50, 0, 5)
        assertEquals(1, filtered.items.size())
        assertEquals(55, filtered.items[0].policy_id)
        assertEquals(Status.READY, filtered.status)

        int queuePolls = 0
        AutomoxAdapter terminalAdapter = automoxAdapter([:], { method, url, headers, timeout, max ->
            if (url.contains('/queues')) {
                queuePolls++
                return response([
                    [id: 2, command_type_name: 'policy', policy_id: 66, status: 'completed', response: 'done']
                ])
            }
            if (url =~ /\/servers\/\d+/) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        })
        MetaClass sleepMeta = Thread.metaClass
        Thread.metaClass.static.sleep = { long ms -> assert false, 'sleep should not run for terminal queue status' }
        try {
            Map terminal = terminalAdapter.operatorDeviceQueue('default', '100', null, null, 50, 30, 5)
            assertEquals(Status.READY, terminal.status)
            assertEquals('completed', terminal.items[0].status)
            assertEquals(1, queuePolls)
        } finally {
            Thread.metaClass = sleepMeta
        }

        int timeoutPolls = 0
        AutomoxAdapter timeoutAdapter = automoxAdapter([:], { method, url, headers, timeout, max ->
            timeoutPolls++
            if (url.contains('/queues')) {
                return response([
                    [id: 1, command_type_name: 'policy', policy_id: 55, status: 'pending']
                ])
            }
            if (url =~ /\/servers\/\d+/) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        })
        MetaClass original = Thread.metaClass
        Thread.metaClass.static.sleep = { long ms -> }
        try {
            Map timedOut = timeoutAdapter.operatorDeviceQueue('default', '100', null, null, 50, 1, 5)
            assertEquals(Status.DEGRADED, timedOut.status)
            assertTrue(timedOut.message.contains('timed out'))
            assertTrue(timeoutPolls >= 2)
        } finally {
            Thread.metaClass = original
        }
    }

    void testAllMutationsDryRunMakeZeroWriteCalls() {
        writeProperties(defaultProperties())
        int writes = 0
        Closure writeHandler = { method, url, headers, payload, timeout, max -> writes++; response([:]) }
        AutomoxAdapter adapter = automoxAdapter([:], fullRouter(), writeHandler, false)
        File evalFile = workletFile('eval.sh', '#!/bin/sh\necho ok\n')
        File remFile = workletFile('rem.sh', '#!/bin/sh\necho fix\n')
        adapter.operatorPolicyRun('default', '900', 'host', false, null, 5)
        adapter.operatorWorkletCreate('default', 'Worklet', evalFile, remFile, 'notes', 5)
        adapter.operatorPolicyDelete('default', '900', 'Patch Policy', 5)
        adapter.operatorDeviceMove('default', '100', '200', 5)
        adapter.operatorPolicyAddGroup('default', '900', '300', 5)
        assertEquals(0, writes)
    }

    void testPolicyRunApplyUsesExpectedJsonBodies() {
        writeProperties(defaultProperties())
        List<Map> posts = []
        AutomoxAdapter adapter = automoxAdapter([:], fullRouter(), { method, url, headers, payload, timeout, max ->
            posts << [method: method, url: url, payload: payload]
            response([:])
        }, true)
        adapter.operatorPolicyRun('default', '900', 'host', false, null, 5)
        shouldFail(IllegalArgumentException) {
            adapter.operatorPolicyRun('default', '900', null, true, '999', 5)
        }
        adapter.operatorPolicyRun('default', '900', null, true, '900', 5)
        assertEquals(1, posts.findAll { it.payload.action == 'remediateServer' }.size())
        Map deviceRun = posts.find { it.payload.action == 'remediateServer' }
        assertEquals(100, deviceRun.payload.serverId)
        assertEquals(1, posts.findAll { it.payload.action == 'remediateAll' }.size())
    }

    void testWorkletCreateValidationAndMetadata() {
        writeProperties(defaultProperties())
        File eval = workletFile('eval.sh', '#!/bin/sh\necho café\n')
        File rem = workletFile('rem.sh', '#!/bin/sh\necho fix\n')
        Map dry = automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Worklet', eval, rem, 'notes', 5)
        Map meta = dry.items[0].evaluation
        assertEquals('eval.sh', meta.path)
        assertEquals(eval.length(), meta.size_bytes)
        assertEquals(sha256(eval), meta.sha256)
        assertFalse(dry.items[0].evaluation.containsKey('content'))
        assertFalse(JsonOutput.toJson(dry).contains('echo café'))

        File outsideDir = File.createTempDir('automox-outside-', '-test')
        try {
            File outsideFile = new File(outsideDir, 'outside.sh')
            outsideFile.setText('#!/bin/sh\n', 'UTF-8')
            shouldFail(IllegalArgumentException) {
                automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Bad', outsideFile, rem, null, 5)
            }
            File link = new File(workspace, 'worklets/link.sh')
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), outsideFile.toPath())
            shouldFail(IllegalArgumentException) {
                automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Bad', link, rem, null, 5)
            }
        } finally {
            outsideDir.deleteDir()
        }

        File insideTarget = workletFile('inside-target.sh', '#!/bin/sh\n')
        File insideLink = new File(workspace, 'worklets/inside-link.sh')
        Files.createSymbolicLink(insideLink.toPath(), insideTarget.toPath())
        shouldFail(IllegalArgumentException) {
            automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Bad', insideLink, rem, null, 5)
        }

        File nulFile = workletFile('nul.sh', 'before\u0000after')
        shouldFail(IllegalArgumentException) {
            automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Bad', nulFile, rem, null, 5)
        }

        File invalidUtf8 = new File(workspace, 'worklets/invalid-utf8.sh')
        invalidUtf8.bytes = [(byte) 0xC3, (byte) 0x28] as byte[]
        shouldFail(IllegalArgumentException) {
            automoxAdapter([:], fullRouter()).operatorWorkletCreate('default', 'Bad', invalidUtf8, rem, null, 5)
        }

        Map smallRules = [worklet: [max_file_bytes: 8]]
        shouldFail(IllegalArgumentException) {
            automoxAdapter([:], fullRouter(), null, false, smallRules)
                .operatorWorkletCreate('default', 'Bad', eval, rem, null, 5)
        }
    }

    void testWorkletCreateIdFallbackOnApply() {
        writeProperties(defaultProperties())
        File eval = workletFile('eval.sh', '#!/bin/sh\n')
        File rem = workletFile('rem.sh', '#!/bin/sh\n')
        int policyReads = 0
        AutomoxAdapter adapter = automoxAdapter([:], { method, url, headers, timeout, max ->
            if (url.contains('/policies') && !url.contains('/policies/')) {
                policyReads++
                List policies = [[id: 900, name: 'Patch Policy']]
                if (policyReads > 1) {
                    policies << [id: 777, name: 'Worklet']
                }
                return response(policies)
            }
            response([server(100, 'host.example.internal')])
        }, { method, url, headers, payload, timeout, max ->
            [code: 204, body: '', error: '']
        }, true)
        Map report = adapter.operatorWorkletCreate('default', 'Worklet', eval, rem, null, 5)
        assertEquals('777', report.items[0].policy_id)
        assertTrue(report.applied)
    }

    void testPolicyDeleteExactNameGate() {
        writeProperties(defaultProperties())
        shouldFail(IllegalArgumentException) {
            automoxAdapter([:], fullRouter()).operatorPolicyDelete('default', '900', 'Wrong Name', 5)
        }
        Map dry = automoxAdapter([:], fullRouter()).operatorPolicyDelete('default', '900', 'Patch Policy', 5)
        assertTrue(dry.change.dry_run)
        assertFalse(dry.applied)
    }

    void testDeviceMoveApplyConfirmsGroup() {
        writeProperties(defaultProperties())
        List<Map> writes = []
        AutomoxAdapter adapter = automoxAdapter([:], { method, url, headers, timeout, max ->
            if (method == 'GET' && url.contains('/servers/100')) {
                long groupId = writes.any { it.method == 'PUT' } ? 200L : 100L
                return response(server(100, 'host.example.internal', true, [server_group_id: groupId]))
            }
            if (url.contains('/servers')) {
                return response([server(100, 'host.example.internal', true, [server_group_id: 100L])])
            }
            response([])
        }, { method, url, headers, payload, timeout, max ->
            writes << [method: method, payload: payload]
            [code: 204, body: '', error: '']
        }, true)
        Map report = adapter.operatorDeviceMove('default', '100', '200', 5)
        assertEquals(1, writes.size())
        assertEquals(200L, writes[0].payload.server_group_id)
        assertTrue(report.applied)
        assertEquals(200L, report.change.to as long)
    }

    void testPolicyAddGroupApplyOrderAndRenamedStateFailure() {
        writeProperties(defaultProperties())
        List<Map> puts = []
        AutomoxAdapter adapter = automoxAdapter([:], policyRouter([
            id: 900,
            name: 'Patch Policy',
            policy_type_name: 'patch',
            organization_id: 121224,
            configuration: [:],
            schedule_days: 0,
            schedule_weeks_of_month: 0,
            schedule_months: 0,
            schedule_time: '00:00',
            notes: '',
            server_groups: [100L]
        ]), { method, url, headers, payload, timeout, max ->
            puts << [name: payload.name, groups: payload.server_groups]
            if (puts.size() == 2) {
                return [code: 500, body: 'failed', error: 'HTTP 500']
            }
            [code: 204, body: '', error: '']
        }, true)
        Map report = adapter.operatorPolicyAddGroup('default', '900', '200', 5)
        assertEquals(Status.ERROR, report.status)
        assertTrue(report.message.contains('Patch Policy-TMP'))
        assertEquals('Patch Policy-TMP', puts[0].name)
        assertEquals([100L], puts[0].groups)
        assertEquals('Patch Policy', puts[1].name)
        assertEquals([100L, 200L], puts[1].groups)
    }

    void testPolicyAddGroupDryRunReportsTwoStepPlan() {
        writeProperties(defaultProperties())
        Map report = automoxAdapter([:], policyRouter([
            id: 900,
            name: 'Patch Policy',
            policy_type_name: 'patch',
            organization_id: 121224,
            configuration: [:],
            schedule_days: 0,
            schedule_weeks_of_month: 0,
            schedule_months: 0,
            schedule_time: '00:00',
            notes: '',
            server_groups: [100L]
        ])).operatorPolicyAddGroup('default', '900', '200', 5)
        assertEquals(['rename', 'restore'], report.change.steps*.action)
        assertEquals('100', report.change.from)
        assertEquals('100,200', report.change.to)
    }

    void testReportSchemaShapeForOperations() {
        writeProperties(defaultProperties())
        AutomoxAdapter adapter = automoxAdapter([:], fullRouter())
        [
            adapter.operatorProfiles(),
            adapter.operatorAuthTest('default', 5),
            adapter.operatorGroups('default', null, 50, 5),
            adapter.operatorDevices('default', null, null, 'all', 50, 5),
            adapter.operatorPolicies('default', null, 'all', 50, 5)
        ].each { Map report ->
            assertEquals([], validateReport(report))
        }
    }

    void testSensitiveOutputRedaction() {
        writeProperties(defaultProperties())
        Map payload = [
            operation: 'profiles',
            fetched_at: AutomoxAdapter.utcNow(),
            status: Status.READY,
            items: [[
                api_token: 'secret-api-token-value',
                enrollment_key: 'enrollment-secret-key',
                authorization: 'Bearer secret-api-token-value',
                has_api_token: true,
                has_enrollment_key: true
            ]]
        ]
        AutomoxOperatorReport report = AutomoxOperatorReport.fromPayload(payload)
        Redaction redaction = new Redaction(repository)
        String json = report.renderJson(redaction)
        String human = report.renderHuman(redaction)
        assertFalse(json.contains('secret-api-token-value'))
        assertFalse(json.contains('enrollment-secret-key'))
        assertFalse(human.contains('secret-api-token-value'))
        assertTrue(json.contains('se...ue'))
        assertTrue(json.contains('en...ey'))
        assertTrue(json.contains('"has_api_token": true'))
    }

    void testAuthTestBlockedOn401() {
        writeProperties(defaultProperties())
        Map report = automoxAdapter([:], { method, url, headers, timeout, max ->
            [code: 401, body: '', error: 'unauthorized']
        }).operatorAuthTest('default', 5)
        assertEquals(Status.BLOCKED, report.status)
    }

    void testCliProfilesJsonRedactsSecrets() {
        writeProperties(defaultProperties())
        Map captured = captureStreams {
            AutomoxCommands.run('profiles', ['--json'], repository, paths, ConfigLoader.load(workspace))
        }
        assertEquals(0, captured.code)
        assertFalse(captured.out.contains('secret-api-token-value'))
        assertTrue(captured.out.contains('"has_api_token": true'))
    }

    void testCliJsonReportsMissingCredentialsAsBlocked() {
        Map captured = captureStreams {
            AutomoxCommands.run('auth-test', ['--json'], repository, paths, ConfigLoader.load(workspace))
        }
        Map report = (Map) new JsonSlurper().parseText(captured.out)
        assertEquals(new ExitCodes(repository).blocked, captured.code)
        assertEquals('blocked', report.status)
        assertEquals('Automox API token unavailable', report.message)
        assertEquals('', captured.err)
    }

    private AutomoxAdapter automoxAdapter(
        Map config,
        Closure readHandler = null,
        Closure writeHandler = null,
        boolean apply = false,
        Map rulesOverride = null
    ) {
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: readHandler ?: { method, url, headers, timeout, max ->
            response([])
        })
        JsonWriteHttp writeHttp = new JsonWriteHttp(requestHandler: writeHandler ?: { method, url, headers, payload, timeout, max ->
            [code: 204, body: '', error: '']
        })
        Map effectiveRules = rulesOverride ? JsonFiles.deepMerge(rules, rulesOverride) : rules
        new AutomoxAdapter(
            paths,
            http,
            writeHttp,
            effectiveRules,
            rulesOverride ? null : repository,
            config,
            apply
        )
    }

    private Closure<Map> serverRouter(List servers) {
        return { method, url, headers, timeout, max ->
            if (url.contains('/packages')) {
                return response([])
            }
            if (url =~ /\/servers\/\d+/) {
                String id = (url =~ /\/servers\/(\d+)/)[0][1]
                Map device = servers.find { it.id.toString() == id }
                return device ? response(device) : [code: 404, body: '', error: '']
            }
            if (url.contains('/servers')) {
                return response(servers)
            }
            response([])
        }
    }

    private Closure<Map> queueRouter() {
        return { method, url, headers, timeout, max ->
            if (url.contains('/queues')) {
                return response([
                    [id: 1, command_type_name: 'policy', policy_id: 55, status: 'pending'],
                    [id: 2, command_type_name: 'policy', policy_id: 66, status: 'pending', response: 'done']
                ])
            }
            if (url =~ /\/servers\/\d+/) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        }
    }

    private Closure<Map> packageRouter(List packages) {
        return { method, url, headers, timeout, max ->
            if (url.contains('/packages')) {
                int page = (url =~ /page=(\d+)/)[0][1] as int
                int pageSize = (url =~ /limit=(\d+)/)[0][1] as int
                int from = page * pageSize
                List slice = packages.size() > from ? packages[from..<Math.min(from + pageSize, packages.size())] : []
                return response(slice)
            }
            if (url.contains('/servers/')) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        }
    }

    private Closure<Map> eventsRouter(List events) {
        return { method, url, headers, timeout, max ->
            if (url.contains('/events')) {
                int page = (url =~ /page=(\d+)/)[0][1] as int
                int pageSize = (url =~ /limit=(\d+)/)[0][1] as int
                int from = page * pageSize
                List slice = events.size() > from ? events[from..<Math.min(from + pageSize, events.size())] : []
                return response(slice)
            }
            if (url.contains('/servers/')) {
                return response(server(100, 'host.example.internal'))
            }
            response([server(100, 'host.example.internal')])
        }
    }

    private Closure<Map> policyRouter(Map policy) {
        return { method, url, headers, timeout, max ->
            if (url.contains('/policies/900') || url.contains('/policies/900?')) {
                return response(policy)
            }
            if (url.contains('/policies')) {
                return response([policy])
            }
            response([server(100, 'host.example.internal', true, [server_group_id: 100L])])
        }
    }

    private Closure<Map> fullRouter() {
        Map policy = [
            id: 900,
            name: 'Patch Policy',
            policy_type_name: 'patch',
            organization_id: 121224,
            configuration: [:],
            schedule_days: 0,
            schedule_weeks_of_month: 0,
            schedule_months: 0,
            schedule_time: '00:00',
            notes: '',
            server_groups: [100L]
        ]
        return { method, url, headers, timeout, max ->
            if (url.contains('/orgs')) {
                return response([[id: 121224, name: 'Org', device_count: 1]])
            }
            if (url.contains('/packages')) {
                return response([])
            }
            if (url.contains('/queues')) {
                return response([])
            }
            if (url.contains('/events')) {
                return response([])
            }
            if (url.contains('/policies/900') || url.contains('/policies/900?')) {
                return response(policy)
            }
            if (url.contains('/policies')) {
                return response([policy])
            }
            if (url.contains('/servers/100')) {
                return response(server(100, 'host.example.internal', true, [server_group_id: 100L]))
            }
            if (url.contains('/servers')) {
                return response([server(100, 'host.example.internal', true, [server_group_id: 100L])])
            }
            response([])
        }
    }

    private static Map server(int id, String name, boolean connected = true, Map extra = [:]) {
        Map base = [
            id: id,
            name: name,
            connected: connected,
            compliant: true,
            needs_reboot: false,
            server_group_id: 100,
            os_name: 'Linux',
            os_family: 'Linux'
        ]
        base.putAll(extra)
        base
    }

    private static Map packageRow(String name, String version, boolean installed) {
        [
            name: name,
            display_name: name,
            version: version,
            installed: installed,
            cve_score: installed ? null : '8.5',
            cves: installed ? null : ['CVE-1'],
            repo: 'main',
            requires_reboot: false
        ]
    }

    private static Map event(
        int id,
        String type,
        String createTime,
        int serverId,
        String serverName,
        Map data = [:],
        String policyName = null
    ) {
        Map event = [
            id: id,
            name: type,
            create_time: createTime,
            server_id: serverId,
            server_name: serverName,
            data: data
        ]
        if (policyName) {
            event.policy_name = policyName
            event.policy_id = 900
        }
        event
    }

    private File workletFile(String relativePath, String content) {
        File file = new File(workspace, relativePath)
        file.parentFile.mkdirs()
        file.setText(content, 'UTF-8')
        file
    }

    private static String sha256(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes)
            .collect { String.format('%02x', it) }.join('')
    }

    private void writeProperties(String content) {
        new File(workspace, 'integrations/automox/automox.properties').setText(content, 'UTF-8')
    }

    private static String defaultProperties() {
        '''default.api_token=secret-api-token-value
default.org=121224
default.api_base_url=https://console.automox.com/api
default.domain=example.internal
default.enrollment_key=enrollment-secret-key
'''
    }

    private static Map response(Object payload) {
        [code: 200, body: JsonOutput.toJson(payload), error: '']
    }

    private static List<String> requiredArguments(Map action) {
        ((List<Map>) (action.positionals ?: [])).findAll { it.required }.collect { Map positional ->
            positional.value?.pattern ? '1' : 'value'
        }
    }

    private int exitCode(Map payload) {
        AutomoxOperatorReport.exitCodeFor(
            AutomoxOperatorReport.fromPayload(payload),
            new ExitCodes(repository)
        )
    }

    private List<String> validateReport(Map report) {
        AutomoxOperatorReport rendered = AutomoxOperatorReport.fromPayload(report)
        Map payload = rendered.toMap(new Redaction(repository))
        validate(payload, schema, schema, '$')
    }

    private static List<String> validate(Object value, Map schema, Map root, String path) {
        if (schema.'$ref') {
            Map resolved = root
            schema.'$ref'.toString().substring(2).split('/').each { resolved = (Map) resolved[it] }
            return validate(value, resolved, root, path)
        }
        if (schema['oneOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['oneOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.count { !it } == 1 ? [] : ["${path}: expected exactly one schema match"]
        }
        if (schema['anyOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['anyOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.any { !it } ? [] : ["${path}: expected at least one schema match"]
        }
        List<String> errors = []
        if (schema['const'] != null && value != schema['const']) {
            errors << "${path}: expected ${schema['const']}"
        }
        if (schema['enum'] instanceof List && !((List) schema['enum']).contains(value)) {
            errors << "${path}: unexpected value ${value}"
        }
        if (schema['type'] == 'object' || schema['required'] || schema['properties'] || schema['not']) {
            if (!(value instanceof Map)) {
                return ["${path}: expected object"]
            }
            Map map = (Map) value
            ((List) (schema['required'] ?: [])).each {
                if (!map.containsKey(it)) {
                    errors << "${path}: missing ${it}"
                }
            }
            if (schema['not'] instanceof Map && !validate(value, (Map) schema['not'], root, path)) {
                errors << "${path}: matched forbidden schema"
            }
            Map properties = (Map) (schema['properties'] ?: [:])
            map.each { key, item ->
                if (properties[key] instanceof Map) {
                    errors.addAll(validate(item, (Map) properties[key], root, "${path}.${key}"))
                } else if (schema['additionalProperties'] == false) {
                    errors << "${path}: unexpected ${key}"
                }
            }
        } else if (schema['type'] == 'array') {
            if (!(value instanceof List)) {
                return ["${path}: expected array"]
            }
            if (schema['items'] instanceof Map) {
                ((List) value).eachWithIndex { item, index ->
                    errors.addAll(validate(item, (Map) schema['items'], root, "${path}[${index}]"))
                }
            }
        } else if (schema['type'] == 'string') {
            if (!(value instanceof String)) {
                errors << "${path}: expected string"
            }
        } else if (schema['type'] == 'boolean' && !(value instanceof Boolean)) {
            errors << "${path}: expected boolean"
        }
        errors
    }

    private static Map captureStreams(Closure<Integer> operation) {
        PrintStream originalOut = System.out
        PrintStream originalErr = System.err
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()
        try {
            System.setOut(new PrintStream(out))
            System.setErr(new PrintStream(err))
            int code = operation.call()
            return [code: code, out: out.toString('UTF-8'), err: err.toString('UTF-8')]
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
