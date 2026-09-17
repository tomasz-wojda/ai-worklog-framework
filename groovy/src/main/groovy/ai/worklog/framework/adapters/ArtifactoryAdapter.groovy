package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.core.Status
import groovy.json.JsonSlurper

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

class ArtifactoryAdapter {
    private static final Pattern REPOSITORY = ~/^[A-Za-z0-9][A-Za-z0-9._-]*$/

    final FrameworkPaths paths
    final ReadOnlyHttp http
    final Map rules
    final Map config
    final Map environment

    ArtifactoryAdapter(
        FrameworkPaths paths,
        ReadOnlyHttp http,
        Map rules,
        Map config = [:],
        Map environment = System.getenv()
    ) {
        this.paths = paths
        this.http = http
        this.rules = rules
        this.config = config ?: [:]
        this.environment = environment
    }

    Map settings() {
        Map adapterConfig = config.adapters?.artifactory instanceof Map ?
            (Map) config.adapters.artifactory :
            [:]
        Map limits = rules.limits instanceof Map ? (Map) rules.limits : [:]
        Map timeouts = rules.timeouts instanceof Map ? (Map) rules.timeouts : [:]
        [
            timeout_seconds: (adapterConfig.timeout_seconds ?: timeouts.default_seconds ?: 15) as int,
            timeout_max_seconds: (timeouts.max_seconds ?: 120) as int,
            repositories_default: (limits.repositories_default ?: 200) as int,
            repositories_max: (limits.repositories_max ?: 1000) as int,
            artifacts_default: (limits.artifacts_default ?: 200) as int,
            artifacts_max: (limits.artifacts_max ?: 1000) as int,
            manifest_default_bytes: (limits.manifest_default_bytes ?: 65536) as int,
            manifest_max_bytes: (limits.manifest_max_bytes ?: 1048576) as int,
            response_max_bytes: (limits.response_max_bytes ?: 4194304) as int
        ]
    }

    Map operatorProfiles() {
        [
            operation: 'profiles',
            fetched_at: utcNow(),
            status: Status.READY,
            message: 'Artifactory profiles resolved',
            items: ArtifactoryCredentials.publicProfiles(paths, config, rules, environment)
        ]
    }

    Map operatorStatus(String profile, int timeout) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        if (!resolved.baseUrl) {
            return blocked('status', resolved, fetchedAt, 'Artifactory URL unavailable')
        }
        Map ping = http.get(
            "${resolved.baseUrl}/api/system/ping",
            [Accept: 'text/plain'],
            validateTimeout(timeout),
            1024
        )
        if ((ping.code as int) == 0) {
            return systemError('status', resolved, fetchedAt, 'Artifactory ping failed')
        }
        if ((ping.code as int) >= 400) {
            return httpError('status', resolved, fetchedAt, ping.code as int)
        }
        Map versionResponse = http.get(
            "${resolved.baseUrl}/api/system/version",
            [Accept: 'application/json'],
            validateTimeout(timeout),
            settings().response_max_bytes as int
        )
        if ((versionResponse.code as int) != 200) {
            return httpError('status', resolved, fetchedAt, versionResponse.code as int)
        }
        Object parsed = parseJson(versionResponse.body?.toString())
        if (!(parsed instanceof Map)) {
            return systemError('status', resolved, fetchedAt, 'Malformed Artifactory version response')
        }
        Map body = (Map) parsed
        [
            operation: 'status',
            profile: resolved.id,
            server: resolved.baseUrl,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Artifactory is reachable',
            items: [[
                reachable: true,
                ping: ping.body?.toString()?.trim(),
                version: body.version,
                revision: body.revision
            ]]
        ]
    }

    Map operatorAuthTest(String profile, String repository, String path, int timeout) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        Map configurationError = configured('auth-test', resolved, fetchedAt)
        if (configurationError) {
            return configurationError
        }
        validateRepository(repository)
        String normalizedPath = normalizePath(path, true)
        Map response = authenticatedGet(
            resolved,
            storageUrl(resolved, repository, normalizedPath),
            validateTimeout(timeout),
            settings().response_max_bytes as int
        )
        if ((response.code as int) != 200) {
            return httpError(
                'auth-test',
                resolved,
                fetchedAt,
                response.code as int,
                repository,
                normalizedPath
            )
        }
        Object parsed = parseJson(response.body?.toString())
        if (!(parsed instanceof Map)) {
            return systemError(
                'auth-test',
                resolved,
                fetchedAt,
                'Malformed Artifactory storage response',
                repository,
                normalizedPath
            )
        }
        [
            operation: 'auth-test',
            profile: resolved.id,
            server: resolved.baseUrl,
            repository: repository,
            path: normalizedPath,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Authenticated read access confirmed',
            items: [[authenticated: true, readable: true]]
        ]
    }

    Map operatorRepositories(
        String profile,
        String query,
        String type,
        String packageType,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        Map configurationError = configured('repositories', resolved, fetchedAt)
        if (configurationError) {
            return configurationError
        }
        validateQuery(query)
        String normalizedType = type ?: 'all'
        if (!(normalizedType in ['all', 'local', 'remote', 'virtual', 'federated'])) {
            throw new IllegalArgumentException("Invalid repository type: ${normalizedType}")
        }
        int effectiveLimit = validateLimit(limit, settings().repositories_max as int)
        Map response = authenticatedGet(
            resolved,
            "${resolved.baseUrl}/api/repositories",
            validateTimeout(timeout),
            settings().response_max_bytes as int
        )
        if ((response.code as int) != 200) {
            return httpError('repositories', resolved, fetchedAt, response.code as int)
        }
        Object parsed = parseJson(response.body?.toString())
        if (!(parsed instanceof List)) {
            return systemError('repositories', resolved, fetchedAt, 'Malformed Artifactory repositories response')
        }
        List<Map> items = []
        ((List) parsed).each { entry ->
            if (entry instanceof Map && entry.key) {
                items << [
                    key: entry.key,
                    type: entry.type?.toString()?.toLowerCase(),
                    package_type: entry.packageType,
                    url: entry.url
                ]
            }
        }
        String queryNeedle = query?.toLowerCase()
        String packageNeedle = packageType?.toLowerCase()
        items = items.findAll { item ->
            (!queryNeedle || item.key.toString().toLowerCase().contains(queryNeedle)) &&
                (normalizedType == 'all' || item.type?.toString()?.toLowerCase() == normalizedType) &&
                (!packageNeedle || item.package_type?.toString()?.toLowerCase() == packageNeedle)
        }
        items.sort { a, b -> a.key.toString().toLowerCase() <=> b.key.toString().toLowerCase() }
        int matched = items.size()
        boolean truncated = matched > effectiveLimit
        if (truncated) {
            items = items.take(effectiveLimit)
        }
        [
            operation: 'repositories',
            profile: resolved.id,
            server: resolved.baseUrl,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: items ? 'Repositories fetched' : 'No repositories found',
            filters: [
                query: query ?: '',
                type: normalizedType,
                package_type: packageType ?: ''
            ],
            totals: [matched: matched, returned: items.size()],
            truncated: truncated,
            items: items
        ]
    }

    Map operatorArtifacts(
        String profile,
        String repository,
        String path,
        String query,
        String since,
        String until,
        boolean recursive,
        int limit,
        int timeout
    ) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        Map configurationError = configured('artifacts', resolved, fetchedAt)
        if (configurationError) {
            return configurationError
        }
        validateRepository(repository)
        String normalizedPath = normalizePath(path, true)
        validateQuery(query)
        Instant lower = parseBoundary(since, false)
        Instant upper = parseBoundary(until, true)
        if (lower && upper && lower.isAfter(upper)) {
            throw new IllegalArgumentException('Invalid date range: --since is after --until')
        }
        int effectiveLimit = validateLimit(limit, settings().artifacts_max as int)
        String listUrl = storageUrl(resolved, repository, normalizedPath) +
            "?list&deep=${recursive ? '1' : '0'}&listFolders=1&mdTimestamps=1&includeRootPath=0"
        Map response = authenticatedGet(
            resolved,
            listUrl,
            validateTimeout(timeout),
            settings().response_max_bytes as int
        )
        if ((response.code as int) != 200) {
            return httpError(
                'artifacts',
                resolved,
                fetchedAt,
                response.code as int,
                repository,
                normalizedPath
            )
        }
        Object parsed = parseJson(response.body?.toString())
        if (!(parsed instanceof Map) || !(((Map) parsed).files instanceof List)) {
            return systemError(
                'artifacts',
                resolved,
                fetchedAt,
                'Malformed Artifactory file-list response',
                repository,
                normalizedPath
            )
        }
        List<Map> items = []
        ((List) ((Map) parsed).files).each { entry ->
            if (entry instanceof Map && entry.uri != null) {
                String uri = entry.uri.toString()
                Instant modified = parseTimestamp(entry.lastModified?.toString())
                items << [
                    repository: repository,
                    path: joinPath(normalizedPath, uri.replaceFirst('^/+', '')),
                    uri: uri,
                    folder: entry.folder == true,
                    size: numeric(entry.size),
                    last_modified: entry.lastModified
                ]
            }
        }
        String needle = query?.toLowerCase()
        items = items.findAll { item ->
            Instant modified = parseTimestamp(item.last_modified?.toString())
            (!needle || item.uri.toString().toLowerCase().contains(needle)) &&
                (!lower || (modified && !modified.isBefore(lower))) &&
                (!upper || (modified && !modified.isAfter(upper)))
        }
        items.sort { a, b ->
            Instant left = parseTimestamp(a.last_modified?.toString()) ?: Instant.EPOCH
            Instant right = parseTimestamp(b.last_modified?.toString()) ?: Instant.EPOCH
            int byDate = right <=> left
            byDate != 0 ? byDate : a.uri.toString().toLowerCase() <=> b.uri.toString().toLowerCase()
        }
        int matched = items.size()
        boolean truncated = matched > effectiveLimit
        if (truncated) {
            items = items.take(effectiveLimit)
        }
        [
            operation: 'artifacts',
            profile: resolved.id,
            server: resolved.baseUrl,
            repository: repository,
            path: normalizedPath,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: items ? 'Artifacts fetched' : 'No artifacts found',
            filters: [
                query: query ?: '',
                since: since ?: '',
                until: until ?: '',
                recursive: recursive
            ],
            totals: [matched: matched, returned: items.size()],
            truncated: truncated,
            items: items
        ]
    }

    Map operatorArtifact(String profile, String repository, String path, int timeout) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        Map configurationError = configured('artifact', resolved, fetchedAt)
        if (configurationError) {
            return configurationError
        }
        validateRepository(repository)
        String normalizedPath = normalizePath(path, false)
        Map response = authenticatedGet(
            resolved,
            storageUrl(resolved, repository, normalizedPath),
            validateTimeout(timeout),
            settings().response_max_bytes as int
        )
        if ((response.code as int) != 200) {
            return httpError(
                'artifact',
                resolved,
                fetchedAt,
                response.code as int,
                repository,
                normalizedPath
            )
        }
        Object parsed = parseJson(response.body?.toString())
        if (!(parsed instanceof Map)) {
            return systemError(
                'artifact',
                resolved,
                fetchedAt,
                'Malformed Artifactory item response',
                repository,
                normalizedPath
            )
        }
        Map body = (Map) parsed
        if (body.children instanceof List) {
            return userError(
                'artifact',
                resolved,
                fetchedAt,
                "Path is a folder; use 'artifacts'",
                repository,
                normalizedPath
            )
        }
        if (body.size == null || !body.downloadUri) {
            return systemError(
                'artifact',
                resolved,
                fetchedAt,
                'Malformed Artifactory file response',
                repository,
                normalizedPath
            )
        }
        Map checksums = body.checksums instanceof Map ? (Map) body.checksums : [:]
        [
            operation: 'artifact',
            profile: resolved.id,
            server: resolved.baseUrl,
            repository: repository,
            path: normalizedPath,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: 'Artifact metadata fetched',
            items: [[
                repository: body.repo ?: repository,
                path: body.path ?: normalizedPath,
                size: numeric(body.size),
                created: body.created,
                modified: body.lastModified,
                updated: body.lastUpdated,
                download_uri: body.downloadUri,
                mime_type: body.mimeType,
                checksums: [
                    md5: checksums.md5,
                    sha1: checksums.sha1,
                    sha256: checksums.sha256
                ].findAll { key, value -> value != null }
            ]]
        ]
    }

    Map operatorManifest(
        String profile,
        String repository,
        String path,
        int maxBytes,
        int timeout
    ) {
        String fetchedAt = utcNow()
        ArtifactoryCredentials.Resolved resolved = resolve(profile)
        Map configurationError = configured('manifest', resolved, fetchedAt)
        if (configurationError) {
            return configurationError
        }
        validateRepository(repository)
        String normalizedPath = normalizePath(path, false)
        int maximum = settings().manifest_max_bytes as int
        if (maxBytes < 1 || maxBytes > maximum) {
            throw new IllegalArgumentException("Invalid --max-bytes: ${maxBytes}")
        }
        String url = "${resolved.baseUrl}/${encodeSegment(repository)}/${encodePath(normalizedPath)}"
        Map response = authenticatedGet(
            resolved,
            url,
            validateTimeout(timeout),
            maxBytes + 1,
            'text/plain, application/json, application/xml, text/xml, */*'
        )
        if ((response.code as int) != 200) {
            return httpError(
                'manifest',
                resolved,
                fetchedAt,
                response.code as int,
                repository,
                normalizedPath
            )
        }
        String content = response.body?.toString() ?: ''
        if (content.indexOf('\u0000') >= 0) {
            return userError(
                'manifest',
                resolved,
                fetchedAt,
                'Artifact content is not text',
                repository,
                normalizedPath
            )
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8)
        boolean truncated = bytes.length > maxBytes
        if (truncated) {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(bytes, 0, maxBytes))
                .toString()
        }
        int returnedBytes = content.getBytes(StandardCharsets.UTF_8).length
        [
            operation: 'manifest',
            profile: resolved.id,
            server: resolved.baseUrl,
            repository: repository,
            path: normalizedPath,
            fetched_at: fetchedAt,
            status: Status.READY,
            message: truncated ? 'Manifest fetched and truncated' : 'Manifest fetched',
            totals: [bytes: returnedBytes],
            truncated: truncated,
            items: [[
                repository: repository,
                path: normalizedPath,
                content: content,
                bytes: returnedBytes,
                truncated: truncated
            ]]
        ]
    }

    static Map loadOperatorRules(File frameworkRoot) {
        Map defaults = [
            default_profile: 'default',
            auth_scheme: 'auto',
            timeouts: [default_seconds: 15, max_seconds: 120],
            limits: [
                repositories_default: 200,
                repositories_max: 1000,
                artifacts_default: 200,
                artifacts_max: 1000,
                manifest_default_bytes: 65536,
                manifest_max_bytes: 1048576,
                response_max_bytes: 4194304
            ]
        ]
        JsonFiles.deepMerge(
            defaults,
            (Map) JsonFiles.read(new File(frameworkRoot, 'shared/artifactory-operator-rules.json'), [:])
        )
    }

    static String utcNow() {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .format(OffsetDateTime.now(ZoneOffset.UTC))
    }

    static void validateRepository(String repository) {
        if (!repository || !REPOSITORY.matcher(repository).matches() || repository in ['.', '..']) {
            throw new IllegalArgumentException("Invalid repository: ${repository}")
        }
    }

    static String normalizePath(String path, boolean optional) {
        if (!path) {
            if (optional) {
                return ''
            }
            throw new IllegalArgumentException("Invalid artifact path: ${path}")
        }
        if (path.startsWith('/') || path.endsWith('/') || path.contains('\\')) {
            throw new IllegalArgumentException("Invalid artifact path: ${path}")
        }
        List<String> parts = path.split('/', -1).toList()
        if (parts.any { part ->
            !part || part in ['.', '..'] || part.any { ch -> Character.isISOControl(ch as char) }
        }) {
            throw new IllegalArgumentException("Invalid artifact path: ${path}")
        }
        parts.join('/')
    }

    private ArtifactoryCredentials.Resolved resolve(String profile) {
        ArtifactoryCredentials.resolve(paths, config, rules, profile, environment)
    }

    private Map configured(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt
    ) {
        if (!resolved.baseUrl) {
            return blocked(operation, resolved, fetchedAt, 'Artifactory URL unavailable')
        }
        if (!resolved.token) {
            return blocked(operation, resolved, fetchedAt, 'Artifactory token unavailable')
        }
        null
    }

    private Map authenticatedGet(
        ArtifactoryCredentials.Resolved resolved,
        String url,
        int timeout,
        int maximum,
        String accept = 'application/json'
    ) {
        Map headers = ArtifactoryCredentials.authorizationHeaders(resolved)
        headers.Accept = accept
        Map response = http.get(url, headers, timeout, maximum)
        if ((response.code as int) == 401 && resolved.configuredScheme == 'auto') {
            String alternate = ArtifactoryCredentials.alternateScheme(resolved.selectedScheme)
            headers = ArtifactoryCredentials.authorizationHeaders(resolved, alternate)
            headers.Accept = accept
            response = http.get(url, headers, timeout, maximum)
        }
        response
    }

    private static Object parseJson(String body) {
        if (!body?.trim()) {
            return null
        }
        try {
            return new JsonSlurper().parseText(body)
        } catch (Exception ignored) {
            return null
        }
    }

    private int validateTimeout(int timeout) {
        int maximum = settings().timeout_max_seconds as int
        if (timeout < 1 || timeout > maximum) {
            throw new IllegalArgumentException("Invalid --timeout: ${timeout}")
        }
        timeout
    }

    private static int validateLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) {
            throw new IllegalArgumentException("Invalid --limit: ${limit}")
        }
        limit
    }

    private static void validateQuery(String query) {
        if (!query) {
            return
        }
        if (query.length() > 256 || query.any { ch -> Character.isISOControl(ch as char) }) {
            throw new IllegalArgumentException('Invalid query')
        }
    }

    private static Instant parseBoundary(String value, boolean upper) {
        if (!value) {
            return null
        }
        try {
            if (value ==~ /\d{4}-\d{2}-\d{2}/) {
                LocalDate date = LocalDate.parse(value)
                return upper ?
                    date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1) :
                    date.atStartOfDay(ZoneOffset.UTC).toInstant()
            }
            return Instant.parse(value)
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid date/time: ${value}")
        }
    }

    private static Instant parseTimestamp(String value) {
        if (!value) {
            return null
        }
        try {
            return Instant.parse(value)
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant()
            } catch (Exception ignoredAgain) {
                return null
            }
        }
    }

    private static Long numeric(Object value) {
        if (value == null || !value.toString().matches('\\d+')) {
            return null
        }
        value.toString() as Long
    }

    private static String storageUrl(
        ArtifactoryCredentials.Resolved resolved,
        String repository,
        String path
    ) {
        String url = "${resolved.baseUrl}/api/storage/${encodeSegment(repository)}"
        path ? "${url}/${encodePath(path)}" : url
    }

    private static String encodePath(String path) {
        path.split('/', -1).collect { encodeSegment(it) }.join('/')
    }

    private static String encodeSegment(String segment) {
        URLEncoder.encode(segment, 'UTF-8').replace('+', '%20')
    }

    private static String joinPath(String parent, String child) {
        parent ? "${parent}/${child}" : child
    }

    private static Map httpError(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt,
        int code,
        String repository = null,
        String path = null
    ) {
        if (code in [401, 403]) {
            return blocked(
                operation,
                resolved,
                fetchedAt,
                "Artifactory returned HTTP ${code}",
                repository,
                path
            )
        }
        if (code == 404) {
            return userError(
                operation,
                resolved,
                fetchedAt,
                'Artifactory resource not found',
                repository,
                path
            )
        }
        if (code == 0) {
            return systemError(
                operation,
                resolved,
                fetchedAt,
                'Artifactory request failed',
                repository,
                path
            )
        }
        systemError(
            operation,
            resolved,
            fetchedAt,
            "Artifactory returned HTTP ${code}",
            repository,
            path
        )
    }

    private static Map blocked(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt,
        String message,
        String repository = null,
        String path = null
    ) {
        report(operation, resolved, fetchedAt, Status.BLOCKED, message, 'blocked', repository, path)
    }

    private static Map userError(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt,
        String message,
        String repository = null,
        String path = null
    ) {
        report(operation, resolved, fetchedAt, Status.ERROR, message, 'user', repository, path)
    }

    private static Map systemError(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt,
        String message,
        String repository = null,
        String path = null
    ) {
        report(operation, resolved, fetchedAt, Status.ERROR, message, 'system', repository, path)
    }

    private static Map report(
        String operation,
        ArtifactoryCredentials.Resolved resolved,
        String fetchedAt,
        Status status,
        String message,
        String errorKind,
        String repository,
        String path
    ) {
        Map payload = [
            operation: operation,
            profile: resolved?.id,
            server: resolved?.baseUrl,
            fetched_at: fetchedAt,
            status: status,
            message: message,
            error_kind: errorKind,
            items: []
        ]
        if (repository) payload.repository = repository
        if (path) payload.path = path
        payload
    }
}
