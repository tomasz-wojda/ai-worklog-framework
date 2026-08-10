package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.regex.Pattern

class JenkinsAdapter {
    private static final Pattern SAFE_COMPONENT = ~/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

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
            if (!part || part in ['.', '..'] || !SAFE_COMPONENT.matcher(part).matches()) {
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
            api_trees: [
                health: 'mode,quietingDown,numExecutors,nodeDescription',
                job: 'name,url,color,buildable,inQueue,lastBuild[number,result,timestamp,duration,building],builds[number,result,timestamp,duration,building]',
                job_parameters: 'name,url,color,buildable,inQueue,actions[parameterDefinitions[name]],lastBuild[number,result,timestamp,duration,building,actions[parameters[name,value]]],builds[number,result,timestamp,duration,building]',
                plugins: 'plugins[shortName,version,active,enabled]',
                credentials: 'credentials[id,typeName,displayName,description]'
            ]
        ]
        JsonFiles.deepMerge(defaults, JsonFiles.read(new File(frameworkRoot, 'shared/jenkins-operator-rules.json'), [:]))
    }

    static String utcNow() {
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString().replace('+00:00', 'Z')
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

    private int timeout() {
        int configured = settings().timeout_seconds as int
        if (rules.timeouts instanceof Map && rules.timeouts.http_seconds != null) {
            return rules.timeouts.http_seconds as int
        }
        configured
    }
}
