package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    final Map operatorRules
    Pattern sensitiveParameterPattern

    JenkinsAdapter(
        FrameworkPaths paths,
        ReadOnlyHttp http,
        Map rules,
        File frameworkRoot = null,
        Map config = [:],
        ReadOnlyProcess process = null
    ) {
        this.paths = paths
        this.http = http
        this.rules = rules
        this.frameworkRoot = frameworkRoot
        this.config = config ?: [:]
        this.process = process ?: new ReadOnlyProcess()
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
        [
            operation: 'plugins',
            controller: controller,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Plugins fetched',
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
        String fetchedAt = utcNow()
        validateControllerId(controller)
        validateJobName(jobName)
        validateJobName(jobName)
        List selectorParts = resolveBuildSelector(buildSelector)
        String requestedSelector = selectorParts[0]
        String apiSegment = selectorParts[1]
        Map controllers = loadControllers()
        if (!controllers[controller]) {
            return errorReport('artifacts', controller, fetchedAt, "Controller '${controller}' not found")
        }
        Map info = (Map) controllers[controller]
        if (!info.url?.toString() || !info.user?.toString() || !info.token?.toString()) {
            return blockedReport('artifacts', controller, fetchedAt, 'Controller credentials unavailable')
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
            return blockedReport('artifacts', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        if (statusCode == 404) {
            String notFoundMessage
            if (apiSegment in ['lastSuccessfulBuild', 'lastCompletedBuild']) {
                String label = apiSegment == 'lastSuccessfulBuild' ? 'last successful build' : 'last completed build'
                notFoundMessage = "${label} not found for job '${jobName}'"
            } else {
                notFoundMessage = "Build '${buildSelector}' not found for job '${jobName}'"
            }
            return errorReport('artifacts', controller, fetchedAt, notFoundMessage)
        }
        if (statusCode == 0 || payload == null) {
            return errorReport('artifacts', controller, fetchedAt, 'Jenkins query failed')
        }
        if (!(payload instanceof Map)) {
            return errorReport('artifacts', controller, fetchedAt, 'Malformed Jenkins response')
        }
        if (statusCode >= 400) {
            return degradedReport('artifacts', controller, fetchedAt, "Jenkins returned HTTP ${statusCode}")
        }
        Map body = (Map) payload
        int artifactsMax = operatorLimits().artifacts_max as int
        List<Map> rawArtifacts = []
        ((List) (body.artifacts ?: [])).each { entry ->
            if (entry instanceof Map) {
                rawArtifacts << [
                    file_name: entry.fileName,
                    relative_path: entry.relativePath
                ]
            }
        }
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
            build_selector: requestedSelector,
            fetched_at: fetchedAt,
            status: status,
            message: message,
            items: [[
                build_selector: requestedSelector,
                resolved_build_number: body.number,
                url: body.url,
                result: body.result,
                artifact_count: rawArtifacts.size(),
                truncated: truncated,
                artifacts: artifacts
            ]]
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
        [response.code as int, payload]
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
        String envRoot = System.getenv('AI_VAULT_ROOT')
        if (envRoot) {
            candidates << new File(envRoot, 'skills/jenkins-pipeline-architect/scripts/syntax_check.sh').canonicalFile
        }
        if (cfg.ai_vault_root) {
            candidates << new File(cfg.ai_vault_root, 'skills/jenkins-pipeline-architect/scripts/syntax_check.sh').canonicalFile
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
