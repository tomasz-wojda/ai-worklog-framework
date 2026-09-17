package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths

class ArtifactoryCredentials {
    static Resolved resolve(
        FrameworkPaths paths,
        Map config,
        Map rules,
        String requestedProfile,
        Map environment = System.getenv()
    ) {
        Map adapterConfig = config.adapters?.artifactory instanceof Map ?
            (Map) config.adapters.artifactory :
            [:]
        File serviceDir = paths.serviceDir('artifactory')
        File propertiesFile = new File(serviceDir, 'artifactory.properties')
        Map properties = propertiesFile.isFile() ? parseAssignments(propertiesFile, false) : [:]
        String profileId = firstNonEmpty(
            requestedProfile,
            environment.ARTIFACTORY_PROFILE?.toString(),
            adapterConfig.default_profile?.toString(),
            rules.default_profile?.toString(),
            'default'
        )
        Map legacy = [:]
        File creds = new File(serviceDir, 'creds')
        File credentials = new File(serviceDir, 'credentials')
        if (creds.isFile()) {
            legacy.putAll(parseAssignments(creds, true))
        }
        if (credentials.isFile()) {
            legacy.putAll(parseAssignments(credentials, true))
        }

        Map profileValues = profileAssignments(properties, profileId)
        Map defaultValues = defaultAssignments(properties)
        String environmentUrl = environment.ARTIFACTORY_URL?.toString()?.trim()
        String environmentToken = environment.ARTIFACTORY_TOKEN?.toString()?.trim()
        String environmentScheme = environment.ARTIFACTORY_AUTH_SCHEME?.toString()?.trim()
        String url = firstNonEmpty(
            environmentUrl,
            value(profileValues, ['url', 'base_url', 'artifactory_url']),
            value(defaultValues, ['url', 'base_url', 'artifactory_url']),
            value(legacy, ['ARTIFACTORY_URL', 'JFROG_URL', 'url']),
            adapterConfig.url?.toString(),
            adapterConfig.base_url?.toString(),
            rules.base_url?.toString()
        )
        String token = firstNonEmpty(
            environmentToken,
            value(profileValues, ['token', 'api_key', 'artifactory_token']),
            value(defaultValues, ['token', 'api_key', 'artifactory_token']),
            value(legacy, ['ARTIFACTORY_TOKEN', 'ARTIFACTORY_API_KEY', 'JFROG_TOKEN', 'token'])
        )
        String configuredScheme = firstNonEmpty(
            environmentScheme,
            value(profileValues, ['auth_scheme', 'scheme']),
            value(defaultValues, ['auth_scheme', 'scheme']),
            value(legacy, ['ARTIFACTORY_AUTH_SCHEME', 'auth_scheme']),
            adapterConfig.auth_scheme?.toString(),
            rules.auth_scheme?.toString(),
            'auto'
        ).toLowerCase()
        if (!(configuredScheme in ['auto', 'bearer', 'api-key'])) {
            throw new IllegalArgumentException("Invalid Artifactory authentication scheme: ${configuredScheme}")
        }
        String normalizedUrl = url ? normalizeUrl(url) : ''
        String selectedScheme = configuredScheme == 'auto' ?
            (token.startsWith('AKCp') ? 'api-key' : 'bearer') :
            configuredScheme
        Map sources = [
            url: sourceFor(
                environmentUrl,
                value(profileValues, ['url', 'base_url', 'artifactory_url']),
                value(defaultValues, ['url', 'base_url', 'artifactory_url']),
                value(legacy, ['ARTIFACTORY_URL', 'JFROG_URL', 'url']),
                firstNonEmpty(adapterConfig.url?.toString(), adapterConfig.base_url?.toString(), rules.base_url?.toString())
            ),
            token: sourceFor(
                environmentToken,
                value(profileValues, ['token', 'api_key', 'artifactory_token']),
                value(defaultValues, ['token', 'api_key', 'artifactory_token']),
                value(legacy, ['ARTIFACTORY_TOKEN', 'ARTIFACTORY_API_KEY', 'JFROG_TOKEN', 'token']),
                ''
            ),
            auth_scheme: sourceFor(
                environmentScheme,
                value(profileValues, ['auth_scheme', 'scheme']),
                value(defaultValues, ['auth_scheme', 'scheme']),
                value(legacy, ['ARTIFACTORY_AUTH_SCHEME', 'auth_scheme']),
                firstNonEmpty(adapterConfig.auth_scheme?.toString(), rules.auth_scheme?.toString())
            )
        ]
        new Resolved(
            id: profileId,
            baseUrl: normalizedUrl,
            token: token,
            configuredScheme: configuredScheme,
            selectedScheme: selectedScheme,
            sources: sources,
            configuredProfiles: configuredProfiles(properties)
        )
    }

    static List<Map> publicProfiles(
        FrameworkPaths paths,
        Map config,
        Map rules,
        Map environment = System.getenv()
    ) {
        File propertiesFile = new File(paths.serviceDir('artifactory'), 'artifactory.properties')
        Map properties = propertiesFile.isFile() ? parseAssignments(propertiesFile, false) : [:]
        List<String> ids = configuredProfiles(properties)
        String selected = firstNonEmpty(
            environment.ARTIFACTORY_PROFILE?.toString(),
            config.adapters?.artifactory?.default_profile?.toString(),
            rules.default_profile?.toString(),
            'default'
        )
        ids << selected
        ids.unique().sort().collect { String id ->
            publicProfile(resolve(paths, config, rules, id, environment), id == selected)
        }
    }

    static Map publicProfile(Resolved resolved, boolean defaultProfile = false) {
        [
            id: resolved.id,
            default: defaultProfile,
            base_url: resolved.baseUrl,
            auth_scheme: resolved.selectedScheme,
            source: new LinkedHashMap(resolved.sources),
            has_url: !!resolved.baseUrl,
            has_token: !!resolved.token
        ]
    }

    static Map authorizationHeaders(Resolved resolved, String scheme = null) {
        if (!resolved.token) {
            return [:]
        }
        String selected = scheme ?: resolved.selectedScheme
        Map headers = [Accept: 'application/json']
        if (selected == 'api-key') {
            headers['X-JFrog-Art-Api'] = resolved.token
        } else {
            headers.Authorization = "Bearer ${resolved.token}"
        }
        headers
    }

    static String alternateScheme(String scheme) {
        scheme == 'api-key' ? 'bearer' : 'api-key'
    }

    static String normalizeUrl(String input) {
        URI uri
        try {
            uri = new URI(input.trim())
        } catch (Exception ignored) {
            throw new IllegalArgumentException('Invalid Artifactory URL')
        }
        if (!(uri.scheme in ['http', 'https']) || !uri.host || uri.userInfo || uri.query || uri.fragment) {
            throw new IllegalArgumentException('Invalid Artifactory URL')
        }
        String path = (uri.path ?: '').replaceAll(/\/+$/, '')
        if (!path.endsWith('/artifactory')) {
            path = path ? "${path}/artifactory" : '/artifactory'
        }
        new URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString()
    }

    static Map parseAssignments(File file, boolean shellSyntax) {
        Map values = [:]
        file.eachLine('UTF-8') { line ->
            String trimmed = line.trim()
            if (!trimmed || trimmed.startsWith('#')) {
                return
            }
            if (shellSyntax && trimmed.startsWith('export ')) {
                trimmed = trimmed.substring(7).trim()
            }
            int separator = trimmed.indexOf('=')
            if (separator < 1) {
                return
            }
            String key = trimmed.substring(0, separator).trim()
            String rawValue = trimmed.substring(separator + 1).trim()
            if (!(key ==~ /[A-Za-z_][A-Za-z0-9_.-]*/)) {
                return
            }
            String parsed = parseValue(rawValue)
            if (parsed != null) {
                values[key] = parsed
            }
        }
        values
    }

    private static String parseValue(String rawValue) {
        if (!rawValue) {
            return ''
        }
        if (rawValue.startsWith("'") || rawValue.startsWith('"')) {
            char quote = rawValue.charAt(0)
            if (rawValue.length() < 2 || rawValue.charAt(rawValue.length() - 1) != quote) {
                return null
            }
            return rawValue.substring(1, rawValue.length() - 1)
        }
        if (rawValue.contains("'") || rawValue.contains('"') || rawValue =~ /\s+#/) {
            return null
        }
        rawValue
    }

    private static Map profileAssignments(Map properties, String profileId) {
        Map values = [:]
        String prefix = "${profileId}."
        properties.each { key, entry ->
            String name = key.toString()
            if (name.startsWith(prefix)) {
                values[name.substring(prefix.length())] = entry
            }
        }
        values
    }

    private static Map defaultAssignments(Map properties) {
        Map values = [:]
        properties.each { key, entry ->
            String name = key.toString()
            if (!name.contains('.')) {
                values[name] = entry
            } else if (name.startsWith('artifactory.')) {
                values[name.substring('artifactory.'.length())] = entry
            } else if (name.startsWith('default.')) {
                values[name.substring('default.'.length())] = entry
            }
        }
        values
    }

    private static List<String> configuredProfiles(Map properties) {
        Set<String> profiles = [] as Set
        properties.keySet().each { key ->
            List<String> parts = key.toString().split('\\.', 2).toList()
            if (parts.size() == 2 && !(parts[0] in ['artifactory', 'default'])) {
                profiles << parts[0]
            }
        }
        profiles.sort()
    }

    private static String value(Map values, List<String> names) {
        names.collect { values[it]?.toString()?.trim() }.find { it } ?: ''
    }

    private static String sourceFor(
        String environmentValue,
        String profileValue,
        String defaultValue,
        String legacyValue,
        String configuredValue
    ) {
        if (environmentValue) return 'env'
        if (profileValue) return 'properties'
        if (defaultValue) return 'properties'
        if (legacyValue) return 'legacy'
        configuredValue ? 'default' : 'default'
    }

    private static String firstNonEmpty(String... values) {
        values.find { it?.trim() }?.trim() ?: ''
    }

    static class Resolved {
        String id
        String baseUrl
        String token
        String configuredScheme
        String selectedScheme
        Map sources
        List<String> configuredProfiles
    }
}
