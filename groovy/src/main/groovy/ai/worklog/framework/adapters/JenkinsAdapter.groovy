package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import ai.worklog.framework.jenkins.JenkinsVersioning
import ai.worklog.framework.setup.SetupResolver
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.regex.Pattern

class JenkinsAdapter {
    private static final Pattern SAFE_COMPONENT = ~/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
    private static final Pattern JOB_NAME_PART = ~/^[A-Za-z0-9_~][A-Za-z0-9._~-]{0,127}$/

    final FrameworkPaths paths
    final ReadOnlyHttp http
    final Map rules
    final File frameworkRoot
    final Map config
    final ReadOnlyProcess process
    final BinaryDownloadClient binaryDownload
    final Map operatorRules
    Pattern sensitiveParameterPattern

    JenkinsAdapter(
        FrameworkPaths paths,
        ReadOnlyHttp http,
        Map rules,
        File frameworkRoot = null,
        Map config = [:],
        ReadOnlyProcess process = null,
        BinaryDownloadClient binaryDownload = null
    ) {
        this.paths = paths
        this.http = http
        this.rules = rules
        this.frameworkRoot = frameworkRoot
        this.config = config ?: [:]
        this.process = process ?: new ReadOnlyProcess()
        this.binaryDownload = binaryDownload ?: new BinaryDownloadClient()
        this.operatorRules = frameworkRoot ? loadOperatorRules(frameworkRoot) : [:]
        this.sensitiveParameterPattern = buildSensitivePattern()
    }

    Map settings() {
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map jenkins = adapters.jenkins instanceof Map ? (Map) adapters.jenkins : [:]
        [
            timeout_seconds: (jenkins.timeout_seconds ?: operatorRules.timeouts?.http_seconds ?: 10) as int,
            process_timeout_seconds: (operatorRules.timeouts?.process_seconds ?: 15) as int,
            max_builds: (jenkins.max_builds ?: operatorRules.max_builds ?: 5) as int,
            required_plugins: (jenkins.required_plugins ?: operatorRules.required_plugins ?: []) as List,
            credential_domain: jenkins.credential_domain?.toString() ?: operatorRules.credential_domain?.toString() ?: '_',
            download_timeout_seconds: (operatorRules.downloads?.timeout_seconds ?: 300) as int,
            download_max_bytes: (operatorRules.downloads?.max_bytes ?: 1073741824L) as long,
            download_buffer_bytes: (operatorRules.downloads?.buffer_bytes ?: 65536) as int,
            vulnerabilities: jenkins.vulnerabilities instanceof Map ?
                (Map) jenkins.vulnerabilities : [:],
            ai_vault_root: jenkins.ai_vault_root?.toString(),
            syntax_check_script: jenkins.syntax_check_script?.toString()
        ]
    }

    Map operatorControllers() {
        String fetchedAt = utcNow()
        Map controllers = loadControllers()
        if (!controllers) {
            return [
                operation: 'controllers',
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: 'No jenkins.properties found or no controllers configured',
                items: []
            ]
        }
        [
            operation: 'controllers',
            fetched_at: fetchedAt,
            status: Status.READY,
            items: controllerPublicInfo(controllers)
        ]
    }

    Map operatorHealth(String controller, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('health', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        String url = info.url?.toString()?.replaceAll(/\/+$/, '') ?: ''
        String user = info.user?.toString() ?: ''
        String token = info.token?.toString() ?: ''
        if (!url || !user || !token) {
            return [
                operation: 'health',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: 'Controller credentials unavailable',
                items: [[
                    id: controller,
                    url: url,
                    has_user: !!user,
                    has_token: !!token
                ]]
            ]
        }
        String tree = operatorRules.api_trees?.health?.toString() ?: 'mode,quietingDown,numExecutors,nodeDescription'
        List response = jenkinsGet(controller, "/api/json?tree=${tree}", timeout)
        int statusCode = response[0] as int
        Object payload = response[1]
        if (accessBlocked(statusCode)) {
            return blockedReport('health', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 0 || payload == null) {
            return errorReport('health', controller, fetchedAt, 'Jenkins query failed')
        }
        if (!(payload instanceof Map)) {
            return errorReport('health', controller, fetchedAt, 'Malformed Jenkins response')
        }
        if (statusCode >= 400) {
            return degradedReport('health', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        Map body = (Map) payload
        boolean quieting = body.quietingDown == true
        [
            operation: 'health',
            controller: controller,
            fetched_at: fetchedAt,
            status: quieting ? Status.DEGRADED : Status.READY,
            message: quieting ? 'Controller is quieting down' : 'Controller is reachable',
            items: [[
                mode: body.mode,
                quieting_down: quieting,
                num_executors: body.numExecutors,
                node_description: body.nodeDescription
            ]]
        ]
    }

    Map operatorJob(
        String controller,
        String jobName,
        int builds,
        boolean includeParameters,
        int timeout
    ) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        validateJobName(jobName)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('job', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('job', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = includeParameters ?
            (operatorRules.api_trees?.job_parameters?.toString() ?: defaultJobParametersTree()) :
            (operatorRules.api_trees?.job?.toString() ?: defaultJobTree())
        List response = jenkinsGet(controller, "/${encodeJobPath(jobName)}/api/json?tree=${tree}", timeout)
        int statusCode = response[0] as int
        Object payload = response[1]
        if (accessBlocked(statusCode)) {
            return blockedReport('job', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 404) {
            return errorReport('job', controller, fetchedAt, "Job '${jobName}' not found")
        }
        if (statusCode == 0 || payload == null) {
            return errorReport('job', controller, fetchedAt, 'Jenkins query failed')
        }
        if (!(payload instanceof Map)) {
            return errorReport('job', controller, fetchedAt, 'Malformed Jenkins response')
        }
        if (statusCode >= 400) {
            return degradedReport('job', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        Map body = (Map) payload
        Map item = [
            job: jobName,
            name: body.name,
            url: body.url,
            color: body.color,
            buildable: body.buildable,
            in_queue: body.inQueue,
            last_build: projectBuild(body.lastBuild instanceof Map ? (Map) body.lastBuild : [:]),
            recent_builds: ((List) (body.builds ?: [])).take(builds).collect { projectBuild((Map) it) }
        ]
        if (includeParameters) {
            item.parameters = extractParameters(body)
        }
        [
            operation: 'job',
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Jenkins job fetched',
            items: [item]
        ]
    }

    Map operatorPlugins(String controller, List<String> required, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('plugins', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('plugins', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.plugins?.toString() ?: 'plugins[shortName,version,active,enabled]'
        List response = jenkinsGet(controller, "/pluginManager/api/json?depth=1&tree=${tree}", timeout)
        int statusCode = response[0] as int
        Object payload = response[1]
        if (accessBlocked(statusCode)) {
            return blockedReport('plugins', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 0 || payload == null) {
            return errorReport('plugins', controller, fetchedAt, 'Jenkins query failed')
        }
        if (!(payload instanceof Map)) {
            return errorReport('plugins', controller, fetchedAt, 'Malformed Jenkins response')
        }
        if (statusCode >= 400) {
            return degradedReport('plugins', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        Map byName = [:]
        ((List) (((Map) payload).plugins ?: [])).each { entry ->
            if (entry instanceof Map && entry.shortName) {
                byName[entry.shortName.toString()] = entry
            }
        }
        List<Map> items = byName.keySet().sort().collect { name ->
            Map entry = (Map) byName[name]
            [
                short_name: name,
                version: entry.version,
                active: entry.active,
                enabled: entry.enabled
            ]
        }
        List<String> missing = []
        List<String> inactive = []
        required.each { plugin ->
            Map entry = byName[plugin]
            if (!entry) {
                missing << plugin
            } else if (!entry.active) {
                inactive << plugin
            }
        }
        if (missing || inactive) {
            List<String> parts = []
            if (missing) {
                parts << "missing: ${missing.sort().join(', ')}"
            }
            if (inactive) {
                parts << "inactive: ${inactive.sort().join(', ')}"
            }
            Map report = [
                operation: 'plugins',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: parts.join('; '),
                items: items,
                required: [
                    requested: required.sort(),
                    missing: missing.sort(),
                    inactive: inactive.sort()
                ]
            ]
            return report
        }
        Map report = [
            operation: 'plugins',
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Plugins fetched',
            items: items
        ]
        if (required) {
            report.required = [
                requested: required.sort(),
                missing: [],
                inactive: []
            ]
        }
        report
    }

    Map operatorPluginVulnerabilities(
        String controller,
        List<String> pluginFilter,
        List<String> enrich,
        int timeout
    ) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                "Controller '${controller}' not found"
            )
        }
        Map controllerInfo = (Map) controllers[controller]
        if (!controllerInfo.url?.toString() || !controllerInfo.user?.toString() ||
            !controllerInfo.token?.toString()) {
            return blockedReport(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                'Controller credentials unavailable'
            )
        }
        String tree = operatorRules.api_trees?.plugins?.toString() ?:
            'plugins[shortName,version,active,enabled]'
        List pluginResponse = jenkinsGet(
            controller,
            "/pluginManager/api/json?depth=1&tree=${tree}",
            timeout
        )
        int pluginStatus = pluginResponse[0] as int
        Object pluginPayload = pluginResponse[1]
        if (pluginStatus != 200 || !(pluginPayload instanceof Map)) {
            return operatorHttpError(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                pluginStatus
            )
        }
        Map pluginHeaders = pluginResponse.size() > 2 && pluginResponse[2] instanceof Map ?
            (Map) pluginResponse[2] : [:]
        String coreVersion = pluginHeaders['x-jenkins']?.toString()
        if (!coreVersion) {
            return errorReport(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                'Malformed Jenkins response: missing X-Jenkins header'
            )
        }
        Map<String, Map> installed = [:]
        ((List) (((Map) pluginPayload).plugins ?: [])).each { entry ->
            if (entry instanceof Map && entry.shortName) {
                installed[entry.shortName.toString()] = (Map) entry
            }
        }
        List<String> requested = pluginFilter.collect { it.toString() }.unique().sort()
        List<String> selectedNames = requested ?
            requested.findAll { installed.containsKey(it) } :
            installed.keySet().toList().sort()
        List<String> missing = requested.findAll { !installed.containsKey(it) }.sort()
        Map vulnerabilityRules = operatorRules.vulnerabilities instanceof Map ?
            new LinkedHashMap((Map) operatorRules.vulnerabilities) : [:]
        vulnerabilityRules.putAll((Map) (settings().vulnerabilities ?: [:]))
        String updateUrl = vulnerabilityRules.update_center_url_template.toString()
            .replace('{core}', URLEncoder.encode(coreVersion, 'UTF-8').replace('+', '%20'))
        Map updateResponse = http.get(
            updateUrl,
            [Accept: 'application/json'],
            timeout,
            vulnerabilityRules.update_center_max_characters as int
        )
        if ((updateResponse.code as int) == 404) {
            updateUrl = vulnerabilityRules.update_center_fallback_url.toString()
            updateResponse = http.get(
                updateUrl,
                [Accept: 'application/json'],
                timeout,
                vulnerabilityRules.update_center_max_characters as int
            )
        }
        if ((updateResponse.code as int) != 200) {
            return errorReport(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                "Jenkins Update Center returned HTTP ${updateResponse.code}"
            )
        }
        Map updateData
        try {
            Object parsed = updateResponse.body?.trim() ?
                new JsonSlurper().parseText(updateResponse.body.toString()) : null
            updateData = parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            updateData = null
        }
        if (!updateData) {
            return errorReport(
                'plugin-vulnerabilities',
                controller,
                fetchedAt,
                'Malformed Jenkins Update Center response'
            )
        }
        Map<String, List<Map>> warningsByPlugin = [:].withDefault { [] }
        ((List) (updateData.warnings ?: [])).each { warning ->
            if (warning instanceof Map && warning.type == 'plugin' && warning.name) {
                warningsByPlugin[warning.name.toString()] << (Map) warning
            }
        }
        Map updatePlugins = updateData.plugins instanceof Map ? (Map) updateData.plugins : [:]
        Set<String> requestedEnrichment = enrich.collect { it.toString() } as Set
        if (requestedEnrichment.contains('nvd')) {
            requestedEnrichment << 'advisory'
        }
        Map<String, Map> advisoryCache = [:]
        Map<String, Map> nvdCache = [:]
        List<String> enrichmentErrors = []
        String nvdKey = System.getenv('NVD_API_KEY') ?: ''
        int nvdInterval = (vulnerabilityRules[
            nvdKey ? 'nvd_authenticated_interval_ms' : 'nvd_unauthenticated_interval_ms'
        ] ?: (nvdKey ? 600 : 6000)) as int
        long lastNvdAt = 0L
        List<Map> items = []
        selectedNames.each { String name ->
            Map installedEntry = installed[name]
            String installedVersion = installedEntry.version?.toString() ?: ''
            List<Map> applicable = warningsByPlugin[name].findAll {
                JenkinsVersioning.warningMatches(it, installedVersion)
            }
            if (!applicable) {
                return
            }
            Map candidate = updatePlugins[name] instanceof Map ? (Map) updatePlugins[name] : [:]
            String candidateVersion = candidate.version?.toString() ?: ''
            boolean candidateVulnerable = !candidateVersion ||
                JenkinsVersioning.compare(candidateVersion, installedVersion) <= 0 ||
                warningsByPlugin[name].any {
                    JenkinsVersioning.warningMatches(it, candidateVersion)
                }
            String requiredCore = candidate.requiredCore?.toString() ?: ''
            List<Map> blockingDependencies = []
            List<String> reasons = []
            String remediationStatus
            if (candidateVulnerable) {
                remediationStatus = 'UNFIXABLE'
                reasons << 'No warning-free plugin version is published'
            } else {
                if (requiredCore && !JenkinsVersioning.atLeast(coreVersion, requiredCore)) {
                    reasons << "Requires Jenkins core ${requiredCore}"
                }
                ((List) (candidate.dependencies ?: [])).each { dependency ->
                    if (!(dependency instanceof Map) || dependency.optional == true) {
                        return
                    }
                    String dependencyName = dependency.name?.toString() ?: ''
                    String requiredVersion = dependency.version?.toString() ?: ''
                    Map current = installed[dependencyName]
                    String currentVersion = current?.version?.toString() ?: ''
                    if (!current || (requiredVersion &&
                        JenkinsVersioning.compare(currentVersion, requiredVersion) < 0)) {
                        blockingDependencies << [
                            name: dependencyName,
                            required_version: requiredVersion,
                            installed_version: currentVersion ?: null
                        ]
                    }
                }
                if (blockingDependencies) {
                    reasons << 'Plugin dependencies are not currently compatible'
                }
                remediationStatus = reasons ? 'BLOCKED' : 'REMEDIABLE'
            }
            Set<String> cves = [] as Set
            List<Map> advisoryRecords = []
            applicable.each { Map warning ->
                if (!requestedEnrichment.contains('advisory')) {
                    return
                }
                String warningUrl = warning.url?.toString() ?: ''
                String advisoryUrl = warningUrl.contains('#') ?
                    warningUrl.substring(0, warningUrl.indexOf('#')) : warningUrl
                if (!advisoryUrl) {
                    enrichmentErrors << "${warning.id}: advisory URL unavailable"
                    return
                }
                if (!advisoryCache.containsKey(advisoryUrl)) {
                    Map response = http.get(
                        advisoryUrl,
                        [Accept: 'text/html'],
                        timeout,
                        vulnerabilityRules.advisory_max_characters as int
                    )
                    advisoryCache[advisoryUrl] = response
                }
                Map response = advisoryCache[advisoryUrl]
                if ((response.code as int) != 200) {
                    enrichmentErrors << "${warning.id}: advisory HTTP ${response.code}"
                    return
                }
                Map details = advisoryDetails(response.body?.toString() ?: '', warning)
                cves.addAll((List<String>) details.cves)
                advisoryRecords << [
                    warning_id: warning.id,
                    severity: details.severity,
                    cves: details.cves
                ]
            }
            List<Map> nvdRecords = []
            if (requestedEnrichment.contains('nvd')) {
                cves.toList().sort().each { String cve ->
                    if (!nvdCache.containsKey(cve)) {
                        long waitMillis = Math.max(0L, lastNvdAt + nvdInterval - System.currentTimeMillis())
                        if (waitMillis) {
                            Thread.sleep(waitMillis)
                        }
                        Map headers = [Accept: 'application/json']
                        if (nvdKey) {
                            headers.apiKey = nvdKey
                        }
                        String nvdUrl = "${vulnerabilityRules.nvd_api_url}?cveId=" +
                            URLEncoder.encode(cve, 'UTF-8')
                        int retries = (vulnerabilityRules.nvd_retry_count ?: 1) as int
                        Map response = [:]
                        for (int attempt = 0; attempt <= retries; attempt++) {
                            response = http.get(nvdUrl, headers, timeout)
                            lastNvdAt = System.currentTimeMillis()
                            if ((response.code as int) != 429 || attempt == retries) {
                                break
                            }
                            int retryAfter = Math.min(
                                ((Map) (response.headers ?: [:]))['retry-after']?.toString()?.isInteger() ?
                                    ((Map) response.headers)['retry-after'].toString().toInteger() : 1,
                                (vulnerabilityRules.nvd_max_retry_after_seconds ?: 30) as int
                            )
                            Thread.sleep(Math.max(0, retryAfter) * 1000L)
                        }
                        if ((response.code as int) == 200) {
                            try {
                                Object parsedNvd = new JsonSlurper().parseText(response.body.toString())
                                Map details = parsedNvd instanceof Map ?
                                    nvdDetails((Map) parsedNvd) : null
                                nvdCache[cve] = details ?: [:]
                                if (!details) {
                                    enrichmentErrors << "${cve}: malformed NVD response"
                                }
                            } catch (Exception ignored) {
                                nvdCache[cve] = [:]
                                enrichmentErrors << "${cve}: malformed NVD response"
                            }
                        } else {
                            nvdCache[cve] = [:]
                            enrichmentErrors << "${cve}: NVD HTTP ${response.code}"
                        }
                    }
                    if (nvdCache[cve]) {
                        nvdRecords << ([cve: cve] + nvdCache[cve])
                    }
                }
            }
            Map highest = highestSeverity(advisoryRecords + nvdRecords)
            items << [
                short_name: name,
                installed_version: installedVersion,
                candidate_version: candidateVersion ?: null,
                active: installedEntry.active,
                enabled: installedEntry.enabled,
                remediation_status: remediationStatus,
                reasons: reasons,
                required_core: requiredCore ?: null,
                blocking_dependencies: blockingDependencies,
                warnings: applicable.collect { warningProjection(it) },
                cves: cves.toList().sort(),
                highest_severity: highest.severity,
                highest_cvss: highest.score,
                advisories: advisoryRecords,
                nvd: nvdRecords
            ]
        }
        Map counts = [REMEDIABLE: 0, UNFIXABLE: 0, BLOCKED: 0]
        items.each { counts[it.remediation_status] = (counts[it.remediation_status] as int) + 1 }
        items.sort { a, b -> a.short_name.toString() <=> b.short_name.toString() }
        [
            operation: 'plugin-vulnerabilities',
            controller: controller,
            fetched_at: fetchedAt,
            status: enrichmentErrors ? Status.DEGRADED : Status.READY,
            message: 'Plugin vulnerability scan completed',
            core_version: coreVersion,
            update_center: [
                url: updateUrl,
                generated_at: updateData.generationTimestamp
            ],
            filter: [requested: requested, missing: missing],
            enrichment: [
                requested: requestedEnrichment.toList().sort(),
                errors: enrichmentErrors.unique().sort()
            ],
            summary: [
                installed: installed.size(),
                scanned: selectedNames.size(),
                affected: items.size(),
                REMEDIABLE: counts.REMEDIABLE,
                UNFIXABLE: counts.UNFIXABLE,
                BLOCKED: counts.BLOCKED
            ],
            items: items
        ]
    }

    Map operatorCredentials(String controller, String domain, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        validateDomain(domain)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('credentials', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('credentials', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.credentials?.toString() ?: 'credentials[id,typeName,displayName,description]'
        String encodedDomain = URLEncoder.encode(domain, 'UTF-8').replace('+', '%20')
        List response = jenkinsGet(
            controller,
            "/credentials/store/system/domain/${encodedDomain}/api/json?tree=${tree}",
            timeout
        )
        int statusCode = response[0] as int
        Object payload = response[1]
        if (accessBlocked(statusCode)) {
            return blockedReport('credentials', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 404) {
            return errorReport('credentials', controller, fetchedAt, "Domain '${domain}' not found")
        }
        if (statusCode == 0 || payload == null) {
            return errorReport('credentials', controller, fetchedAt, 'Jenkins query failed')
        }
        if (!(payload instanceof Map)) {
            return errorReport('credentials', controller, fetchedAt, 'Malformed Jenkins response')
        }
        if (statusCode >= 400) {
            return degradedReport('credentials', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        List<Map> items = []
        ((List) (((Map) payload).credentials ?: [])).each { entry ->
            if (entry instanceof Map) {
                items << [
                    id: entry.id,
                    type_name: entry.typeName,
                    display_name: entry.displayName,
                    description: entry.description
                ]
            }
        }
        items.sort { a, b ->
            int byId = (a.id ?: '').toString() <=> (b.id ?: '').toString()
            byId != 0 ? byId : (a.display_name ?: '').toString() <=> (b.display_name ?: '').toString()
        }
        [
            operation: 'credentials',
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Credential metadata fetched',
            items: items,
            domain: domain
        ]
    }

    Map operatorCredentialDomains(String controller, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('credential-domains', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('credential-domains', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.credential_domains?.toString() ?:
            'domains[domainName,displayName,description,url]'
        List response = jenkinsGet(
            controller,
            "/credentials/store/system/api/json?tree=${tree}",
            timeout
        )
        return handleOperatorResponse('credential-domains', controller, fetchedAt, response) { Map body ->
            List<Map> items = projectCredentialDomains(body)
            [
                operation: 'credential-domains',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.READY,
                message: items ? 'Credential domains fetched' : 'No credential domains found',
                items: items
            ]
        }
    }

    Map operatorWhoami(String controller, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('whoami', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('whoami', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.whoami?.toString() ?: 'name,authenticated'
        List response = jenkinsGet(controller, "/whoAmI/api/json?tree=${tree}", timeout)
        return handleOperatorResponse('whoami', controller, fetchedAt, response) { Map body ->
            [
                operation: 'whoami',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.READY,
                message: 'Identity fetched',
                items: [[
                    name: body.name,
                    authenticated: body.authenticated
                ]]
            ]
        }
    }

    Map operatorViews(String controller, String viewName, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('views', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('views', controller, fetchedAt, 'Controller credentials unavailable')
        }
        if (viewName) {
            validateViewName(viewName)
            String tree = operatorRules.api_trees?.view_detail?.toString() ?:
                'name,url,description,jobs[name,url,color,buildable,inQueue]'
            String encoded = URLEncoder.encode(viewName, 'UTF-8').replace('+', '%20')
            List response = jenkinsGet(controller, "/view/${encoded}/api/json?tree=${tree}", timeout)
            int statusCode = response[0] as int
            Object payload = response[1]
            if (accessBlocked(statusCode)) {
                return blockedReport('views', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
            }
            if (statusCode == 404) {
                return errorReport('views', controller, fetchedAt, "View '${viewName}' not found")
            }
            if (statusCode == 0 || payload == null) {
                return errorReport('views', controller, fetchedAt, 'Jenkins query failed')
            }
            if (!(payload instanceof Map)) {
                return errorReport('views', controller, fetchedAt, 'Malformed Jenkins response')
            }
            if (statusCode >= 400) {
                return degradedReport('views', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
            }
            Map body = (Map) payload
            List<Map> jobs = projectViewJobs((List) (body.jobs ?: []))
            return [
                operation: 'views',
                controller: controller,
                view: viewName,
                fetched_at: fetchedAt,
                status: Status.READY,
                message: 'View fetched',
                items: [[
                    name: body.name,
                    url: body.url,
                    description: body.description,
                    jobs: jobs
                ]]
            ]
        }
        String tree = operatorRules.api_trees?.views?.toString() ?: 'views[name,url,description]'
        List response = jenkinsGet(controller, "/api/json?tree=${tree}", timeout)
        return handleOperatorResponse('views', controller, fetchedAt, response) { Map body ->
            List<Map> items = []
            ((List) (body.views ?: [])).each { entry ->
                if (entry instanceof Map && entry.name) {
                    items << [
                        name: entry.name,
                        url: entry.url,
                        description: entry.description
                    ]
                }
            }
            items.sort { a, b ->
                (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase()
            }
            [
                operation: 'views',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.READY,
                message: items ? 'Views fetched' : 'No views found',
                items: items
            ]
        }
    }

    Map operatorArtifacts(String controller, String jobName, String buildSelector, int timeout) {
        Map lookup = fetchArtifactBuild('artifacts', controller, jobName, buildSelector, timeout)
        if (lookup.report) {
            return (Map) lookup.report
        }
        Map body = (Map) lookup.body
        int artifactsMax = operatorLimits().artifacts_max as int
        List<Map> rawArtifacts = (List<Map>) lookup.artifacts
        rawArtifacts.sort { a, b ->
            (a.relative_path ?: '').toString().toLowerCase() <=> (b.relative_path ?: '').toString().toLowerCase()
        }
        boolean truncated = rawArtifacts.size() > artifactsMax
        List<Map> artifacts = truncated ? rawArtifacts.take(artifactsMax) : rawArtifacts
        Status status = truncated ? Status.DEGRADED : Status.READY
        String message = 'Artifact metadata fetched'
        if (truncated) {
            message = "Artifact metadata fetched; truncated to ${artifactsMax} items"
        } else if (!artifacts) {
            message = 'No artifacts found'
        }
        [
            operation: 'artifacts',
            controller: controller,
            job: jobName,
            build_selector: lookup.requested_selector,
            fetched_at: lookup.fetched_at,
            status: status,
            message: message,
            items: [[
                build_selector: lookup.requested_selector,
                resolved_build_number: body.number,
                url: body.url,
                result: body.result,
                artifact_count: rawArtifacts.size(),
                truncated: truncated,
                artifacts: artifacts
            ]]
        ]
    }

    Map operatorDownloadArtifact(
        String controller,
        String jobName,
        String buildSelector,
        String artifactPath,
        boolean apply,
        boolean force
    ) {
        Map settings = settings()
        Map lookup = fetchArtifactBuild(
            'download-artifact',
            controller,
            jobName,
            buildSelector,
            settings.timeout_seconds as int
        )
        if (lookup.report) {
            return (Map) lookup.report
        }
        Map artifact = ((List<Map>) lookup.artifacts).find {
            it.relative_path?.toString() == artifactPath
        }
        if (!artifact) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                "Artifact not found: ${artifactPath}"
            )
        }
        if (!(lookup.body.number instanceof Number) || (lookup.body.number as int) < 1) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                'Malformed Jenkins response'
            )
        }
        int resolvedBuild = lookup.body.number as int
        Map destination = resolveArtifactDestination(
            controller,
            jobName,
            resolvedBuild,
            artifactPath
        )
        File target = (File) destination.file
        boolean exists = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (exists && (Files.isSymbolicLink(target.toPath()) ||
            !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                "Invalid destination: ${destination.local_path}"
            )
        }
        if (exists && !force) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                "Invalid destination: already exists: ${destination.local_path}"
            )
        }

        Map item = [
            artifact: artifact.file_name,
            relative_path: artifactPath,
            resolved_build_number: resolvedBuild,
            local_path: destination.local_path,
            bytes: 0L,
            applied: false,
            dry_run: !apply,
            force: force,
            replaced: false,
            max_bytes: settings.download_max_bytes
        ]
        if (!apply) {
            return [
                operation: 'download-artifact',
                controller: controller,
                job: jobName,
                build_selector: lookup.requested_selector,
                fetched_at: lookup.fetched_at,
                status: Status.READY,
                message: 'Artifact download planned',
                items: [item]
            ]
        }

        Files.createDirectories(target.parentFile.toPath())
        rejectSymbolicLinks((Path) destination.root, target.toPath())
        Map info = (Map) lookup.controller
        String url = artifactDownloadUrl(info.url.toString(), jobName, resolvedBuild, artifactPath)
        Map headers = authHeaders(info.user.toString(), info.token.toString())
        headers.Accept = 'application/octet-stream'
        Map result = binaryDownload.download(
            url,
            headers,
            target,
            settings.download_timeout_seconds as int,
            settings.download_max_bytes as long,
            settings.download_buffer_bytes as int,
            force
        )
        if ((result.code as int) in [401, 403]) {
            return blockedReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                "Jenkins returned HTTP ${result.code}"
            )
        }
        if ((result.code as int) == 404) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                "Artifact not found: ${artifactPath}"
            )
        }
        if ((result.code as int) != 200 || result.error) {
            return errorReport(
                'download-artifact',
                controller,
                lookup.fetched_at.toString(),
                result.error?.toString() ?: 'Artifact download failed'
            )
        }
        item.bytes = result.bytes as long
        if ((result.content_length as long) >= 0L) {
            item.content_length = result.content_length as long
        }
        item.applied = true
        item.dry_run = false
        item.replaced = result.replaced as boolean
        [
            operation: 'download-artifact',
            controller: controller,
            job: jobName,
            build_selector: lookup.requested_selector,
            fetched_at: lookup.fetched_at,
            status: Status.READY,
            message: 'Artifact downloaded',
            items: [item]
        ]
    }

    Map operatorJobs(String controller, String folder, String query, int limit, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        String effectiveFolder = folder ?: ''
        if (effectiveFolder) {
            validateJobName(effectiveFolder)
        }
        String effectiveQuery = query ?: ''
        if (effectiveQuery) {
            validateJobQuery(effectiveQuery)
        }
        Map limits = operatorLimits()
        int effectiveLimit = validateLimit(limit, 'limit', limits.jobs_default as int, limits.jobs_max as int)
        int maxDepth = limits.jobs_max_depth as int
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('jobs', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('jobs', controller, fetchedAt, 'Controller credentials unavailable')
        }
        if (effectiveFolder) {
            List folderResponse = jenkinsGet(
                controller,
                "/${encodeJobPath(effectiveFolder)}/api/json",
                timeout
            )
            int folderStatus = folderResponse[0] as int
            Object folderPayload = folderResponse[1]
            if (accessBlocked(folderStatus)) {
                return blockedReport('jobs', controller, fetchedAt, "Jenkins returned HTTP ${folderStatus}")
            }
            if (folderStatus == 404) {
                return errorReport('jobs', controller, fetchedAt, "Folder '${effectiveFolder}' not found")
            }
            if (folderStatus != 200 || !(folderPayload instanceof Map)) {
                return operatorHttpError('jobs', controller, fetchedAt, folderStatus)
            }
        } else {
            String tree = operatorRules.api_trees?.jobs?.toString() ?:
                'jobs[name,url,color,buildable,inQueue,_class]'
            List rootResponse = jenkinsGet(
                controller,
                "/api/json?tree=${tree}",
                timeout
            )
            int rootStatus = rootResponse[0] as int
            Object rootPayload = rootResponse[1]
            if (rootStatus != 200 || !(rootPayload instanceof Map)) {
                return operatorHttpError('jobs', controller, fetchedAt, rootStatus)
            }
        }
        List<Map> collected = collectJobsRecursive(controller, effectiveFolder, 0, maxDepth, timeout)
        if (effectiveQuery) {
            String needle = effectiveQuery.toLowerCase()
            collected = collected.findAll { item ->
                (item.full_path ?: '').toString().toLowerCase().contains(needle)
            }
        }
        collected.sort { a, b ->
            (a.full_path ?: '').toString().toLowerCase() <=> (b.full_path ?: '').toString().toLowerCase()
        }
        boolean truncated = collected.size() > effectiveLimit
        if (truncated) {
            collected = collected.take(effectiveLimit)
        }
        Status status = truncated ? Status.DEGRADED : Status.READY
        String message
        if (truncated) {
            message = "Jobs fetched; truncated to ${effectiveLimit} items"
        } else if (collected) {
            message = 'Jobs fetched'
        } else {
            message = 'No jobs found'
        }
        Map report = [
            operation: 'jobs',
            controller: controller,
            fetched_at: fetchedAt,
            status: status,
            message: message,
            items: collected
        ]
        if (effectiveFolder) {
            report.folder = effectiveFolder
        }
        if (effectiveQuery) {
            report.query = effectiveQuery
        }
        report
    }

    Map operatorQueue(String controller, int limit, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map limits = operatorLimits()
        int effectiveLimit = validateLimit(limit, 'limit', limits.queue_default as int, limits.queue_max as int)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('queue', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('queue', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.queue?.toString() ?:
            'items[id,why,stuck,inQueueSince,blocked,buildable,task[name,url,color]]'
        List response = jenkinsGet(controller, "/queue/api/json?tree=${tree}", timeout)
        return handleOperatorResponse('queue', controller, fetchedAt, response) { Map body ->
            List<Map> items = []
            ((List) (body.items ?: [])).each { entry ->
                if (entry instanceof Map) {
                    Map task = entry.task instanceof Map ? (Map) entry.task : [:]
                    items << [
                        id: entry.id,
                        why: entry.why,
                        stuck: entry.stuck,
                        in_queue_since: entry.inQueueSince,
                        blocked: entry.blocked,
                        buildable: entry.buildable,
                        task_name: task.name,
                        task_url: task.url,
                        task_color: task.color
                    ]
                }
            }
            items.sort { a, b -> (a.id ?: 0) <=> (b.id ?: 0) }
            boolean truncated = items.size() > effectiveLimit
            if (truncated) {
                items = items.take(effectiveLimit)
            }
            Status status = truncated ? Status.DEGRADED : Status.READY
            String message = 'Queue fetched'
            if (truncated) {
                message = "Queue fetched; truncated to ${effectiveLimit} items"
            } else if (!items) {
                message = 'Queue is empty'
            }
            [
                operation: 'queue',
                controller: controller,
                fetched_at: fetchedAt,
                status: status,
                message: message,
                items: items
            ]
        }
    }

    Map operatorNodes(String controller, int timeout) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('nodes', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('nodes', controller, fetchedAt, 'Controller credentials unavailable')
        }
        String tree = operatorRules.api_trees?.nodes?.toString() ?:
            'computer[displayName,description,numExecutors,idle,offline,temporarilyOffline,busyExecutors,assignedLabels[name]]'
        List response = jenkinsGet(controller, "/computer/api/json?tree=${tree}", timeout)
        return handleOperatorResponse('nodes', controller, fetchedAt, response) { Map body ->
            List<Map> items = []
            ((List) (body.computer ?: [])).each { entry ->
                if (entry instanceof Map) {
                    List labels = []
                    ((List) (entry.assignedLabels ?: [])).each { label ->
                        if (label instanceof Map && label.name) {
                            labels << label.name.toString()
                        }
                    }
                    labels.sort()
                    items << [
                        display_name: entry.displayName,
                        description: entry.description,
                        num_executors: entry.numExecutors,
                        idle: entry.idle,
                        offline: entry.offline,
                        temporarily_offline: entry.temporarilyOffline,
                        busy_executors: entry.busyExecutors,
                        assigned_labels: labels
                    ]
                }
            }
            items.sort { a, b ->
                (a.display_name ?: '').toString().toLowerCase() <=> (b.display_name ?: '').toString().toLowerCase()
            }
            [
                operation: 'nodes',
                controller: controller,
                fetched_at: fetchedAt,
                status: Status.READY,
                message: items ? 'Nodes fetched' : 'No nodes found',
                items: items
            ]
        }
    }

    Map operatorLimits() {
        Map limits = operatorRules.limits instanceof Map ? (Map) operatorRules.limits : [:]
        [
            queue_default: (limits.queue_default ?: 50) as int,
            queue_max: (limits.queue_max ?: 50) as int,
            jobs_default: (limits.jobs_default ?: 100) as int,
            jobs_max: (limits.jobs_max ?: 100) as int,
            jobs_max_depth: (limits.jobs_max_depth ?: limits.jobs_depth_max ?: 2) as int,
            jobs_query_max_length: (limits.jobs_query_max_length ?: limits.query_max_length ?: 128) as int,
            artifacts_max: (limits.artifacts_max ?: 200) as int
        ]
    }

    Map operatorSeed(String controller, String jobName, int timeout, int maxBuilds) {
        String fetchedAt = utcNow()
        Map jobReport = operatorJob(controller, jobName, maxBuilds, false, timeout)
        Set failureResults = ((List) (operatorRules.seed_failure_results ?: ['FAILURE', 'failure', 'UNSTABLE', 'unstable']))
            .collect { it.toString().toUpperCase() } as Set
        List items = []
        ((List) (jobReport.items ?: [])).each { item ->
            Map lastBuild = item.last_build instanceof Map ? (Map) item.last_build : [:]
            List recent = item.recent_builds instanceof List ? (List) item.recent_builds : []
            boolean recentFailure = recent.any { build ->
                failureResults.contains((build.result ?: '').toString().toUpperCase())
            }
            items << [
                job: item.job,
                available: true,
                buildable: item.buildable,
                in_queue: item.in_queue,
                last_build: lastBuild,
                recent_failure: recentFailure
            ]
        }
        Status status = jobReport.status instanceof Status ? (Status) jobReport.status : Status.UNKNOWN
        String message = jobReport.message?.toString() ?: ''
        if (items && items[0].recent_failure) {
            status = Status.DEGRADED
            message = 'Seed job has recent failures'
        }
        [
            operation: 'seed',
            controller: controller,
            fetched_at: fetchedAt,
            status: status,
            message: message,
            items: items
        ]
    }

    Map operatorSyntaxCheck(List<String> files, int timeout) {
        String fetchedAt = utcNow()
        if (!files) {
            return [
                operation: 'syntax-check',
                fetched_at: fetchedAt,
                status: Status.ERROR,
                message: 'No files provided',
                items: []
            ]
        }
        File script = resolveSyntaxCheckScript()
        if (!script) {
            return [
                operation: 'syntax-check',
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: 'Syntax check script unavailable',
                items: []
            ]
        }
        List<File> resolvedFiles = []
        for (String filePath : files) {
            try {
                resolvedFiles << validateSyntaxFile(filePath)
            } catch (IllegalArgumentException exception) {
                return [
                    operation: 'syntax-check',
                    fetched_at: fetchedAt,
                    status: Status.ERROR,
                    message: exception.message,
                    items: []
                ]
            }
        }
        Map result = process.execute([script.absolutePath] + resolvedFiles*.absolutePath, timeout)
        Map item = [
            script: script.absolutePath,
            files: resolvedFiles*.absolutePath,
            exit_code: result.code,
            stdout: (result.out ?: '').trim(),
            stderr: (result.err ?: '').trim()
        ]
        if (result.code == 124) {
            return [
                operation: 'syntax-check',
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: 'Syntax check timed out',
                items: [item]
            ]
        }
        if (result.code == 127) {
            return [
                operation: 'syntax-check',
                fetched_at: fetchedAt,
                status: Status.BLOCKED,
                message: 'Syntax check runtime unavailable',
                items: [item]
            ]
        }
        if (result.code != 0) {
            return [
                operation: 'syntax-check',
                fetched_at: fetchedAt,
                status: Status.ERROR,
                message: 'Syntax check failed',
                items: [item]
            ]
        }
        [
            operation: 'syntax-check',
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Syntax check passed',
            items: [item]
        ]
    }

    List<Observation> observe(Map state, List<Map> targets) {
        String fetchedAt = utcNow()
        Map controllers = loadControllers()
        if (!controllers) {
            return [new Observation(
                system: 'jenkins',
                source: 'jenkins',
                status: Status.UNKNOWN,
                message: 'Jenkins credentials unavailable',
                details: [fetched_at: fetchedAt]
            )]
        }
        if (!targets) {
            if (state.builds) {
                return [new Observation(
                    system: 'jenkins',
                    source: 'jenkins',
                    status: Status.UNKNOWN,
                    message: 'Builds recorded but no resolvable Jenkins jobs',
                    details: [fetched_at: fetchedAt]
                )]
            }
            return [new Observation(
                system: 'jenkins',
                source: 'jenkins',
                status: Status.UNKNOWN,
                message: 'No Jenkins jobs configured',
                details: [fetched_at: fetchedAt]
            )]
        }
        int maxBuilds = (rules.jenkins_max_builds ?: settings().max_builds ?: 5) as int
        String tree = 'name,color,lastBuild[number,result,timestamp,duration,building],builds[number,result,timestamp,duration,building]'
        List<Observation> observations = []
        targets.each { target ->
            String controller = target.controller?.toString() ?: ''
            String jobName = target.job?.toString() ?: ''
            String source = "jenkins:${controller}/${jobName}"
            if (!controllers[controller]) {
                observations << unresolvedObservation(source, controller, jobName, fetchedAt, Status.DEGRADED, 'Controller not configured')
                return
            }
            List response = jenkinsGet(controller, "/${encodeJobPath(jobName)}/api/json?tree=${tree}", timeout())
            int statusCode = response[0] as int
            Object payload = response[1]
            if (statusCode == 0 || !(payload instanceof Map)) {
                observations << unresolvedObservation(
                    source,
                    controller,
                    jobName,
                    fetchedAt,
                    statusCode != 0 ? Status.ERROR : Status.DEGRADED,
                    statusCode != 0 ? 'Malformed Jenkins response' : 'Jenkins query failed'
                )
                return
            }
            if (statusCode >= 400) {
                observations << unresolvedObservation(
                    source,
                    controller,
                    jobName,
                    fetchedAt,
                    Status.DEGRADED,
                    "Jenkins returned HTTP ${statusCode}"
                )
                return
            }
            Map body = (Map) payload
            List builds = ((List) (body.builds ?: [])).take(maxBuilds)
            observations << new Observation(
                system: 'jenkins',
                source: source,
                status: Status.READY,
                message: 'Jenkins job fetched',
                details: [
                    controller: controller,
                    job: jobName,
                    color: body.color,
                    fetched_at: fetchedAt,
                    last_build: projectBuild(body.lastBuild instanceof Map ? (Map) body.lastBuild : [:]),
                    recent_builds: builds.collect { projectBuild((Map) it) }
                ]
            )
        }
        observations
    }

    static void validateControllerId(String controllerId) {
        if (!SAFE_COMPONENT.matcher(controllerId).matches() || controllerId in ['.', '..']) {
            throw new IllegalArgumentException("Invalid controller: ${controllerId}")
        }
    }

    static void validateJobName(String jobName) {
        if (!jobName || jobName in ['.', '..']) {
            throw new IllegalArgumentException("Invalid job: ${jobName}")
        }
        jobName.split('/').each { part ->
            if (!part || part in ['.', '..'] || !JOB_NAME_PART.matcher(part).matches()) {
                throw new IllegalArgumentException("Invalid job: ${jobName}")
            }
        }
    }

    static void validateDomain(String domain) {
        if (!domain || domain in ['.', '..']) {
            throw new IllegalArgumentException("Invalid domain: ${domain}")
        }
        if (domain == '_') {
            return
        }
        if (!(domain ==~ /[A-Za-z0-9._-]{1,128}/)) {
            throw new IllegalArgumentException("Invalid domain: ${domain}")
        }
    }

    static File validateSyntaxFile(String path) {
        File file = new File(path).canonicalFile
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found: ${path}")
        }
        file
    }

    static void validateViewName(String viewName) {
        if (!viewName || viewName in ['.', '..']) {
            throw new IllegalArgumentException("Invalid view: ${viewName}")
        }
        if (!JOB_NAME_PART.matcher(viewName).matches()) {
            throw new IllegalArgumentException("Invalid view: ${viewName}")
        }
    }

    static void validateJobQuery(String query) {
        if (!query) {
            return
        }
        int maxLength = 128
        if (query.length() > maxLength) {
            throw new IllegalArgumentException("Invalid query: exceeds ${maxLength} characters")
        }
        query.each { ch ->
            if (Character.isISOControl(ch as char)) {
                throw new IllegalArgumentException('Invalid query: control characters are not allowed')
            }
        }
    }

    static List resolveBuildSelector(String selector) {
        if (selector == 'last-successful') {
            return [selector, 'lastSuccessfulBuild']
        }
        if (selector == 'last-completed') {
            return [selector, 'lastCompletedBuild']
        }
        if (selector ==~ /[1-9]\d*/) {
            return [selector, selector]
        }
        throw new IllegalArgumentException("Invalid build selector: ${selector}")
    }

    static void validateBuildSelector(String selector) {
        resolveBuildSelector(selector)
    }

    static String encodeJobPath(String jobName) {
        validateJobName(jobName)
        'job/' + jobName.split('/').collect {
            URLEncoder.encode(it, 'UTF-8').replace('+', '%20')
        }.join('/job/')
    }

    static List<Map> controllerPublicInfo(Map controllers) {
        controllers.keySet().sort().collect { id ->
            PropertiesSupport.publicController(id.toString(), (Map) controllers[id])
        }
    }

    static Map loadOperatorRules(File frameworkRoot) {
        Map defaults = [
            timeouts: [http_seconds: 10],
            max_builds: 5,
            credential_domain: '_',
            required_plugins: [],
            sensitive_parameter_patterns: ['password', 'secret', 'token', 'credential', 'key', 'auth'],
            seed_failure_results: ['FAILURE', 'failure', 'UNSTABLE', 'unstable'],
            vulnerabilities: [
                update_center_url_template: 'https://updates.jenkins.io/{core}/update-center.actual.json',
                update_center_fallback_url: 'https://updates.jenkins.io/current/update-center.actual.json',
                update_center_max_characters: 25000000,
                advisory_max_characters: 2000000,
                nvd_api_url: 'https://services.nvd.nist.gov/rest/json/cves/2.0',
                nvd_unauthenticated_interval_ms: 6000,
                nvd_authenticated_interval_ms: 600,
                nvd_retry_count: 1,
                nvd_max_retry_after_seconds: 30
            ],
            downloads: [
                timeout_seconds: 300,
                max_bytes: 1073741824L,
                buffer_bytes: 65536
            ],
            limits: [
                queue_default: 50,
                queue_max: 50,
                jobs_default: 100,
                jobs_max: 100,
                jobs_max_depth: 2,
                jobs_query_max_length: 128,
                artifacts_max: 200
            ],
            api_trees: [
                health: 'mode,quietingDown,numExecutors,nodeDescription',
                job: 'name,url,color,buildable,inQueue,lastBuild[number,result,timestamp,duration,building],builds[number,result,timestamp,duration,building]',
                job_parameters: 'name,url,color,buildable,inQueue,actions[parameterDefinitions[name]],lastBuild[number,result,timestamp,duration,building,actions[parameters[name,value]]],builds[number,result,timestamp,duration,building]',
                plugins: 'plugins[shortName,version,active,enabled]',
                credentials: 'credentials[id,typeName,displayName,description]',
                nodes: 'computer[displayName,description,numExecutors,idle,offline,temporarilyOffline,busyExecutors,assignedLabels[name]]',
                queue: 'items[id,why,stuck,inQueueSince,blocked,buildable,task[name,url,color]]',
                jobs: 'jobs[name,url,color,buildable,inQueue,_class]',
                artifacts: 'number,url,result,artifacts[fileName,relativePath]',
                views: 'views[name,url,description]',
                view_detail: 'name,url,description,jobs[name,url,color,buildable,inQueue]',
                whoami: 'name,authenticated',
                credential_domains: 'domains[domainName,displayName,description,url]'
            ]
        ]
        JsonFiles.deepMerge(defaults, JsonFiles.read(new File(frameworkRoot, 'shared/jenkins-operator-rules.json'), [:]))
    }

    static String utcNow() {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .format(OffsetDateTime.now(ZoneOffset.UTC))
    }

    private Map fetchArtifactBuild(
        String operation,
        String controller,
        String jobName,
        String buildSelector,
        int timeout
    ) {
        String fetchedAt = utcNow()
        validateControllerId(controller)
        validateJobName(jobName)
        List selectorParts = resolveBuildSelector(buildSelector)
        String requestedSelector = selectorParts[0]
        String apiSegment = selectorParts[1]
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return [report: errorReport(
                operation,
                controller,
                fetchedAt,
                "Controller '${controller}' not found"
            )]
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return [report: blockedReport(
                operation,
                controller,
                fetchedAt,
                'Controller credentials unavailable'
            )]
        }
        String tree = operatorRules.api_trees?.artifacts?.toString() ?:
            'number,url,result,artifacts[fileName,relativePath]'
        List response = jenkinsGet(
            controller,
            "/${encodeJobPath(jobName)}/${apiSegment}/api/json?tree=${tree}",
            timeout
        )
        int statusCode = response[0] as int
        Object payload = response[1]
        if (accessBlocked(statusCode)) {
            return [report: blockedReport(
                operation,
                controller,
                fetchedAt,
                "Jenkins returned HTTP ${statusCode}"
            )]
        }
        if (statusCode == 404) {
            String message
            if (apiSegment in ['lastSuccessfulBuild', 'lastCompletedBuild']) {
                String label = apiSegment == 'lastSuccessfulBuild' ?
                    'last successful build' :
                    'last completed build'
                message = "${label} not found for job '${jobName}'"
            } else {
                message = "Build '${buildSelector}' not found for job '${jobName}'"
            }
            return [report: errorReport(operation, controller, fetchedAt, message)]
        }
        if (statusCode == 0 || payload == null) {
            return [report: errorReport(
                operation,
                controller,
                fetchedAt,
                'Jenkins query failed'
            )]
        }
        if (!(payload instanceof Map)) {
            return [report: errorReport(
                operation,
                controller,
                fetchedAt,
                'Malformed Jenkins response'
            )]
        }
        if (statusCode >= 400) {
            return [report: degradedReport(
                operation,
                controller,
                fetchedAt,
                "Jenkins returned HTTP ${statusCode}"
            )]
        }
        List<Map> artifacts = []
        ((List) (((Map) payload).artifacts ?: [])).each { entry ->
            if (entry instanceof Map) {
                artifacts << [
                    file_name: entry.fileName,
                    relative_path: entry.relativePath
                ]
            }
        }
        [
            fetched_at: fetchedAt,
            requested_selector: requestedSelector,
            body: payload,
            artifacts: artifacts,
            controller: info
        ]
    }

    private Map resolveArtifactDestination(
        String controller,
        String jobName,
        int resolvedBuild,
        String artifactPath
    ) {
        validateControllerId(controller)
        validateJobName(jobName)
        List<String> artifactParts = validateArtifactPath(artifactPath)
        Path workspaceRoot = paths.root.toPath().toAbsolutePath().normalize()
        Path downloadRoot = workspaceRoot.resolve('tmp/services/jenkins').normalize()
        Path target = downloadRoot.resolve(controller)
        jobName.split('/').each { target = target.resolve(it) }
        target = target.resolve(resolvedBuild.toString())
        artifactParts.each { target = target.resolve(it) }
        target = target.toAbsolutePath().normalize()
        if (!target.startsWith(downloadRoot) || target == downloadRoot) {
            throw new IllegalArgumentException("Invalid artifact path: ${artifactPath}")
        }
        rejectSymbolicLinks(workspaceRoot, target)
        [
            root: workspaceRoot,
            file: target.toFile(),
            local_path: workspaceRoot.relativize(target).toString().replace(File.separatorChar, '/' as char)
        ]
    }

    private static List<String> validateArtifactPath(String artifactPath) {
        if (!artifactPath || artifactPath.startsWith('/') ||
            artifactPath.contains('\\') || artifactPath.contains('\u0000')) {
            throw new IllegalArgumentException("Invalid artifact path: ${artifactPath}")
        }
        List<String> parts = artifactPath.split('/', -1).toList()
        if (!parts || parts.any { !it || it in ['.', '..'] }) {
            throw new IllegalArgumentException("Invalid artifact path: ${artifactPath}")
        }
        try {
            if (new File(artifactPath).isAbsolute()) {
                throw new IllegalArgumentException("Invalid artifact path: ${artifactPath}")
            }
        } catch (InvalidPathException ignored) {
            throw new IllegalArgumentException("Invalid artifact path: ${artifactPath}")
        }
        parts
    }

    private static void rejectSymbolicLinks(Path base, Path target) {
        Path normalizedBase = base.toAbsolutePath().normalize()
        Path normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Invalid destination: ${target}")
        }
        Path current = normalizedBase
        normalizedBase.relativize(normalizedTarget).each { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Invalid destination: symbolic link: ${current}")
            }
        }
    }

    private static String artifactDownloadUrl(
        String controllerUrl,
        String jobName,
        int resolvedBuild,
        String artifactPath
    ) {
        String baseUrl = controllerUrl.replaceAll(/\/+$/, '')
        String encodedArtifact = artifactPath.split('/', -1).collect {
            URLEncoder.encode(it, 'UTF-8').replace('+', '%20')
        }.join('/')
        "${baseUrl}/${encodeJobPath(jobName)}/${resolvedBuild}/artifact/${encodedArtifact}"
    }

    private Map loadControllers() {
        File properties = new File(paths.serviceDir('jenkins'), 'jenkins.properties')
        properties.isFile() ? PropertiesSupport.controllers(properties) : [:]
    }

    private List jenkinsGet(String controller, String path, int timeoutSeconds) {
        Map controllers = loadControllers()
        Map info = (Map) controllers[controller]
        String baseUrl = info.url.toString().replaceAll(/\/+$/, '')
        String apiUrl = "${baseUrl}${path.startsWith('/') ? path : "/${path}"}"
        Map response = http.get(apiUrl, authHeaders(info.user.toString(), info.token.toString()), timeoutSeconds)
        if (response.code == 0) {
            return [0, null]
        }
        Object payload
        try {
            payload = response.body?.trim() ? new JsonSlurper().parseText(response.body) : null
        } catch (Exception ignored) {
            payload = null
        }
        [response.code as int, payload, response.headers instanceof Map ? (Map) response.headers : [:]]
    }

    private List<Map> extractParameters(Map payload) {
        List<String> definitions = []
        ((List) (payload.actions ?: [])).each { action ->
            if (action instanceof Map) {
                ((List) (action.parameterDefinitions ?: [])).each { definition ->
                    if (definition instanceof Map && definition.name) {
                        definitions << definition.name.toString()
                    }
                }
            }
        }
        Map<String, Boolean> valuesPresent = definitions.collectEntries { [(it): false] }
        Map lastBuild = payload.lastBuild instanceof Map ? (Map) payload.lastBuild : [:]
        ((List) (lastBuild.actions ?: [])).each { action ->
            if (action instanceof Map) {
                ((List) (action.parameters ?: [])).each { parameter ->
                    if (parameter instanceof Map && parameter.name) {
                        String name = parameter.name.toString()
                        if (!valuesPresent.containsKey(name)) {
                            valuesPresent[name] = false
                        }
                        def value = parameter.value
                        valuesPresent[name] = value != null && value != ''
                    }
                }
            }
        }
        valuesPresent.keySet().sort().collect { name ->
            [name: parameterName(name), value_present: valuesPresent[name]]
        }
    }

    private String parameterName(String name) {
        sensitiveParameterPattern.matcher(name).find() ? '***REDACTED***' : name
    }

    private static Map warningProjection(Map warning) {
        List<Map> ranges = []
        ((List) (warning.versions ?: [])).each { entry ->
            if (entry instanceof Map) {
                ranges << [
                    pattern: entry.pattern,
                    last_version: entry.lastVersion
                ]
            }
        }
        [
            id: warning.id,
            message: warning.message,
            url: warning.url,
            ranges: ranges
        ]
    }

    private static Map advisoryDetails(String html, Map warning) {
        String warningId = warning.id?.toString() ?: ''
        String url = warning.url?.toString() ?: ''
        String fragment = url.contains('#') ?
            url.substring(url.indexOf('#') + 1) : warningId
        String section = html
        def marker = Pattern.compile(
            "id=[\"']${Pattern.quote(fragment)}[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (marker.find()) {
            section = html.substring(marker.start())
            def boundary = Pattern.compile(
                /<h[23][^>]+id=["']/,
                Pattern.CASE_INSENSITIVE
            ).matcher(section.substring(marker.end() - marker.start()))
            if (boundary.find()) {
                section = section.substring(
                    0,
                    marker.end() - marker.start() + boundary.start()
                )
            }
        }
        Set<String> cves = [] as Set
        def cveMatcher = Pattern.compile(
            /CVE-\d{4}-\d{4,}/,
            Pattern.CASE_INSENSITIVE
        ).matcher(section)
        while (cveMatcher.find()) {
            cves << cveMatcher.group().toUpperCase()
        }
        def severityMatcher = Pattern.compile(
            /\b(Critical|High|Medium|Moderate|Low)\b/,
            Pattern.CASE_INSENSITIVE
        ).matcher(section)
        [
            cves: cves.toList().sort(),
            severity: severityMatcher.find() ?
                severityMatcher.group(1).toUpperCase() : null
        ]
    }

    private static Map nvdDetails(Map payload) {
        List vulnerabilities = payload.vulnerabilities instanceof List ?
            (List) payload.vulnerabilities : []
        if (!vulnerabilities || !(vulnerabilities[0] instanceof Map) ||
            !(((Map) vulnerabilities[0]).cve instanceof Map)) {
            return null
        }
        Map cve = (Map) ((Map) vulnerabilities[0]).cve
        Map metrics = cve.metrics instanceof Map ? (Map) cve.metrics : [:]
        List<Map> candidates = []
        ['cvssMetricV40', 'cvssMetricV31', 'cvssMetricV30', 'cvssMetricV2'].each { key ->
            ((List) (metrics[key] ?: [])).each { metric ->
                if (metric instanceof Map && metric.cvssData instanceof Map &&
                    ((Map) metric.cvssData).baseScore != null) {
                    candidates << [data: (Map) metric.cvssData, metric: (Map) metric]
                }
            }
        }
        if (!candidates) {
            return [
                published: cve.published,
                last_modified: cve.lastModified
            ]
        }
        Map selected = candidates.max {
            ((Map) it.data).baseScore as BigDecimal
        }
        Map data = (Map) selected.data
        Map metric = (Map) selected.metric
        [
            cvss_version: data.version,
            base_score: data.baseScore,
            severity: data.baseSeverity ?: metric.baseSeverity,
            vector: data.vectorString,
            published: cve.published,
            last_modified: cve.lastModified
        ]
    }

    private static Map highestSeverity(List<Map> records) {
        Map<String, Integer> ranks = [
            CRITICAL: 4,
            HIGH: 3,
            MEDIUM: 2,
            MODERATE: 2,
            LOW: 1
        ]
        String severity
        BigDecimal score
        records.each { record ->
            if (record.base_score != null) {
                BigDecimal current = record.base_score as BigDecimal
                if (score == null || current > score) {
                    score = current
                }
            }
            String currentSeverity = record.severity?.toString()?.toUpperCase()
            if (currentSeverity &&
                (ranks[currentSeverity] ?: 0) > (ranks[severity] ?: 0)) {
                severity = currentSeverity
            }
        }
        [severity: severity, score: score]
    }

    private Pattern buildSensitivePattern() {
        List patterns = (List) (operatorRules.sensitive_parameter_patterns ?: ['password', 'secret', 'token', 'credential', 'key', 'auth'])
        Pattern.compile(patterns.join('|'), Pattern.CASE_INSENSITIVE)
    }

    private static Map projectBuild(Map build) {
        [
            number: build.number,
            result: build.result,
            timestamp: build.timestamp,
            duration: build.duration,
            building: build.building
        ]
    }

    private static boolean accessBlocked(int statusCode) {
        statusCode in [401, 403]
    }

    private static Map errorReport(String operation, String controller, String fetchedAt, String message) {
        [
            operation: operation,
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.ERROR,
            message: message,
            items: []
        ]
    }

    private static Map blockedReport(String operation, String controller, String fetchedAt, String message) {
        [
            operation: operation,
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.BLOCKED,
            message: message,
            items: []
        ]
    }

    private static Map degradedReport(String operation, String controller, String fetchedAt, String message) {
        [
            operation: operation,
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.DEGRADED,
            message: message,
            items: []
        ]
    }

    private File resolveSyntaxCheckScript() {
        Map cfg = settings()
        List<File> candidates = []
        if (cfg.syntax_check_script) {
            candidates << new File(cfg.syntax_check_script).canonicalFile
        }
        if (cfg.ai_vault_root) {
            candidates << new File(
                cfg.ai_vault_root,
                'skills/jenkins-pipeline-architect/scripts/syntax_check.sh'
            ).canonicalFile
        }
        List resolution = SetupResolver.resolveAiVaultRoot(paths.root)
        File resolvedRoot = resolution[0] as File
        if (resolvedRoot) {
            candidates << new File(
                resolvedRoot,
                'skills/jenkins-pipeline-architect/scripts/syntax_check.sh'
            ).canonicalFile
        }
        candidates.find { it.isFile() }
    }

    private static Observation unresolvedObservation(
        String source,
        String controller,
        String jobName,
        String fetchedAt,
        Status status,
        String message
    ) {
        new Observation(
            system: 'jenkins',
            source: source,
            status: status,
            message: message,
            details: [
                controller: controller,
                job: jobName,
                fetched_at: fetchedAt
            ]
        )
    }

    private static String defaultJobTree() {
        'name,url,color,buildable,inQueue,lastBuild[number,result,timestamp,duration,building],builds[number,result,timestamp,duration,building]'
    }

    private static String defaultJobParametersTree() {
        'name,url,color,buildable,inQueue,actions[parameterDefinitions[name]],lastBuild[number,result,timestamp,duration,building,actions[parameters[name,value]]],builds[number,result,timestamp,duration,building]'
    }

    private static Map authHeaders(String user, String token) {
        [
            Authorization: "Basic ${Base64.encoder.encodeToString("${user}:${token}".bytes)}",
            Accept: 'application/json'
        ]
    }

    private static int validateLimit(int value, String label, int defaultValue, int maximum) {
        if (value < 1) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }
        Math.min(value, maximum)
    }

    private Map operatorHttpError(String operation, String controller, String fetchedAt, int statusCode, String notFoundMessage = null) {
        if (accessBlocked(statusCode)) {
            return blockedReport(operation, controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 404 && notFoundMessage) {
            return errorReport(operation, controller, fetchedAt, notFoundMessage)
        }
        if (statusCode == 0) {
            return errorReport(operation, controller, fetchedAt, 'Jenkins query failed')
        }
        if (statusCode >= 400) {
            return degradedReport(operation, controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        errorReport(operation, controller, fetchedAt, 'Malformed Jenkins response')
    }

    private Map handleOperatorResponse(
        String operation,
        String controller,
        String fetchedAt,
        List response,
        Closure<Map> onSuccess
    ) {
        int statusCode = response[0] as int
        Object payload = response[1]
        if (statusCode != 200 || !(payload instanceof Map)) {
            return operatorHttpError(operation, controller, fetchedAt, statusCode)
        }
        onSuccess((Map) payload)
    }

    private List<Map> collectJobsRecursive(
        String controller,
        String folderPath,
        int currentDepth,
        int maxDepth,
        int timeout
    ) {
        String tree = operatorRules.api_trees?.jobs?.toString() ?:
            'jobs[name,url,color,buildable,inQueue,_class]'
        String apiPath = folderPath ?
            "/${encodeJobPath(folderPath)}/api/json?tree=${tree}" :
            "/api/json?tree=${tree}"
        List response = jenkinsGet(controller, apiPath, timeout)
        int statusCode = response[0] as int
        Object payload = response[1]
        if (statusCode != 200 || !(payload instanceof Map)) {
            return []
        }
        List<Map> collected = []
        ((List) (((Map) payload).jobs ?: [])).each { entry ->
            if (!(entry instanceof Map) || !entry.name) {
                return
            }
            String name = entry.name.toString()
            String fullPath = folderPath ? "${folderPath}/${name}" : name
            if (isFolderJob(entry)) {
                if (currentDepth < maxDepth) {
                    collected.addAll(collectJobsRecursive(
                        controller,
                        fullPath,
                        currentDepth + 1,
                        maxDepth,
                        timeout
                    ))
                }
                return
            }
            collected << [
                name: entry.name ?: name,
                full_path: fullPath,
                url: entry.url,
                color: entry.color,
                buildable: entry.buildable,
                in_queue: entry.inQueue,
                job_class: entry._class
            ]
        }
        collected
    }

    private static boolean isFolderJob(Map entry) {
        String jobClass = entry._class?.toString() ?: ''
        jobClass.toLowerCase().contains('folder')
    }

    private static List<Map> projectCredentialDomains(Map body) {
        List<Map> items = []
        Object raw = body.domains
        if (raw instanceof List) {
            ((List) raw).each { entry ->
                if (entry instanceof Map) {
                    items << [
                        domain_name: entry.domainName,
                        display_name: entry.displayName,
                        description: entry.description,
                        url: entry.url
                    ]
                }
            }
        } else if (raw instanceof Map) {
            ((Map) raw).each { domainName, entry ->
                if (entry instanceof Map) {
                    items << [
                        domain_name: domainName?.toString(),
                        display_name: entry.displayName,
                        description: entry.description,
                        url: entry.url
                    ]
                }
            }
        }
        items.sort { a, b ->
            (a.domain_name ?: '').toString().toLowerCase() <=> (b.domain_name ?: '').toString().toLowerCase()
        }
        items
    }

    private static List<Map> projectViewJobs(List jobs) {
        List<Map> items = []
        jobs.each { entry ->
            if (entry instanceof Map && entry.name) {
                items << [
                    name: entry.name,
                    url: entry.url,
                    color: entry.color,
                    buildable: entry.buildable,
                    in_queue: entry.inQueue
                ]
            }
        }
        items.sort { a, b ->
            (a.name ?: '').toString().toLowerCase() <=> (b.name ?: '').toString().toLowerCase()
        }
        items
    }

    private int timeout() {
        int configured = settings().timeout_seconds as int
        if (rules.timeouts instanceof Map && rules.timeouts.http_seconds != null) {
            return rules.timeouts.http_seconds as int
        }
        configured
    }
}
