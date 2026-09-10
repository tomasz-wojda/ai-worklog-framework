package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.JsonFiles

class AutomoxCredentials {
    static Resolved resolve(
        FrameworkPaths paths,
        Map config,
        Map rules,
        String requestedProfile,
        String requestedOrg = null
    ) {
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map automoxConfig = adapters.automox instanceof Map ? (Map) adapters.automox : [:]
        String profileId = requestedProfile ?:
            System.getenv('AUTOMOX_PROFILE') ?:
            automoxConfig.default_profile?.toString() ?:
            rules.default_profile?.toString() ?:
            'default'
        File serviceDir = paths.serviceDir('automox')
        File propertiesFile = new File(serviceDir, 'automox.properties')
        Map profiles = propertiesFile.isFile() ? PropertiesSupport.controllers(propertiesFile) : [:]
        Map profileProps = profiles[profileId] instanceof Map ? (Map) profiles[profileId] : [:]
        String tokenFromEnv = System.getenv('AUTOMOX_API_TOKEN')
        String enrollmentFromEnv = System.getenv('AUTOMOX_ENROLLMENT_KEY')
        String orgFromEnv = System.getenv('AUTOMOX_ORG')
        String orgFromArgument = requestedOrg?.trim()
        String baseFromEnv = System.getenv('AUTOMOX_API_BASE_URL')
        String legacyToken = parseLegacyToken(serviceDir)
        String legacySetkey = parseLegacySetkey(serviceDir)
        String apiToken = tokenFromEnv ?: profileProps.api_token?.toString() ?: legacyToken ?: ''
        String enrollmentKey = enrollmentFromEnv ?:
            profileProps.enrollment_key?.toString() ?:
            legacySetkey ?:
            ''
        String org = orgFromArgument ?:
            orgFromEnv ?:
            profileProps.org?.toString() ?:
            automoxConfig.org?.toString() ?:
            ''
        String apiBaseUrl = baseFromEnv ?:
            profileProps.api_base_url?.toString() ?:
            automoxConfig.api_base_url?.toString() ?:
            rules.api_base_url?.toString() ?:
            'https://console.automox.com/api'
        String domain = profileProps.domain?.toString() ?: automoxConfig.domain?.toString() ?: ''
        String sshUser = profileProps.ssh_user?.toString() ?: ''
        Map sources = [
            api_token: tokenFromEnv ? 'env' : profileProps.api_token ? 'properties' : legacyToken ? 'legacy' : 'default',
            enrollment_key: enrollmentFromEnv ? 'env' :
                profileProps.enrollment_key ? 'properties' :
                legacySetkey ? 'legacy' : 'default',
            org: orgFromArgument ? 'argument' :
                orgFromEnv ? 'env' :
                profileProps.org ? 'properties' :
                automoxConfig.org ? 'default' : 'default',
            api_base_url: baseFromEnv ? 'env' :
                profileProps.api_base_url ? 'properties' :
                automoxConfig.api_base_url ? 'default' :
                rules.api_base_url ? 'default' : 'default',
            domain: profileProps.domain ? 'properties' : automoxConfig.domain ? 'default' : 'default'
        ]
        new Resolved(
            id: profileId,
            org: org,
            apiBaseUrl: apiBaseUrl.replaceAll(/\/+$/, ''),
            domain: domain,
            sshUser: sshUser,
            apiToken: apiToken,
            enrollmentKey: enrollmentKey,
            sources: sources,
            configuredProfiles: profiles.keySet().sort() as List<String>
        )
    }

    static Map publicProfile(String id, Resolved resolved) {
        [
            id: id,
            org: resolved.org,
            api_base_url: resolved.apiBaseUrl,
            domain: resolved.domain,
            source: resolved.sources,
            has_api_token: !!resolved.apiToken,
            has_enrollment_key: !!resolved.enrollmentKey
        ]
    }

    static List<Map> publicProfiles(FrameworkPaths paths, Map config, Map rules) {
        File serviceDir = paths.serviceDir('automox')
        File propertiesFile = new File(serviceDir, 'automox.properties')
        Map profiles = propertiesFile.isFile() ? PropertiesSupport.controllers(propertiesFile) : [:]
        if (!profiles) {
            String fallback = System.getenv('AUTOMOX_PROFILE') ?: rules.default_profile?.toString() ?: 'default'
            Resolved resolved = resolve(paths, config, rules, fallback)
            return [publicProfile(resolved.id, resolved)]
        }
        profiles.keySet().sort().collect { String id ->
            publicProfile(id, resolve(paths, config, rules, id))
        }
    }

    static Map authorizationHeaders(Resolved resolved) {
        if (!resolved.apiToken) {
            return [:]
        }
        [
            Authorization: "Bearer ${resolved.apiToken}",
            Accept: 'application/json'
        ]
    }

    private static String parseLegacyToken(File serviceDir) {
        File tokenFile = new File(serviceDir, 'token')
        if (!tokenFile.isFile()) {
            return ''
        }
        String last = ''
        tokenFile.eachLine('UTF-8') { line ->
            String trimmed = line.trim()
            if (trimmed && !trimmed.startsWith('#')) {
                last = trimmed
            }
        }
        last
    }

    private static String parseLegacySetkey(File serviceDir) {
        File serverIdFile = new File(serviceDir, 'server-id')
        if (!serverIdFile.isFile()) {
            return ''
        }
        def matcher = serverIdFile.getText('UTF-8') =~ /(?m)^\s*AUTOMOX_SETKEY\s*=\s*['"]?([^'"\r\n]+)['"]?\s*$/
        matcher.find() ? matcher.group(1).trim() : ''
    }

    static class Resolved {
        String id
        String org
        String apiBaseUrl
        String domain
        String sshUser
        String apiToken
        String enrollmentKey
        Map sources
        List<String> configuredProfiles
    }
}
