package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths

class NewRelicCredentials {
    static Resolved resolve(FrameworkPaths paths, Map config, Map rules, String requestedProfile) {
        Map adapters = config.adapters instanceof Map ? (Map) config.adapters : [:]
        Map newrelicConfig = adapters.newrelic instanceof Map ? (Map) adapters.newrelic : [:]
        String profileId = requestedProfile ?:
            System.getenv('NEW_RELIC_PROFILE') ?:
            System.getenv('NEWRELIC_PROFILE') ?:
            newrelicConfig.default_profile?.toString() ?:
            rules.default_profile?.toString() ?:
            'default'
        File serviceDir = paths.serviceDir('newrelic')
        File propertiesFile = new File(serviceDir, 'newrelic.properties')
        Map flatProps = propertiesFile.isFile() ? PropertiesSupport.load(propertiesFile) : [:]
        Map profiles = propertiesFile.isFile() ? PropertiesSupport.controllers(propertiesFile) : [:]
        profiles.remove('newrelic')
        Map profileProps = profiles[profileId] instanceof Map ? (Map) profiles[profileId] : [:]
        String apiKeyFromEnv = firstNonEmpty(System.getenv('NEW_RELIC_API_KEY'), System.getenv('NEWRELIC_API_KEY'))
        String accountFromEnv = firstNonEmpty(System.getenv('NEW_RELIC_ACCOUNT_ID'), System.getenv('NEWRELIC_ACCOUNT_ID'))
        String restFromEnv = firstNonEmpty(System.getenv('NEW_RELIC_REST_URL'), System.getenv('NEWRELIC_REST_URL'))
        String graphqlFromEnv = firstNonEmpty(System.getenv('NEW_RELIC_GRAPHQL_URL'), System.getenv('NEWRELIC_GRAPHQL_URL'))
        String globalRestUrl = flatProps['newrelic.url']?.toString() ?: ''
        String legacyApiKey = profileId == 'default' ? flatProps['newrelic.api_key']?.toString() ?: '' : ''
        String legacyAccountId = profileId == 'default' ? flatProps['newrelic.account_id']?.toString() ?: '' : ''
        String apiKey = apiKeyFromEnv ?:
            profileProps.api_key?.toString() ?:
            profileProps['newrelic.api_key']?.toString() ?:
            legacyApiKey ?:
            ''
        String accountId = accountFromEnv ?:
            profileProps.account_id?.toString() ?:
            profileProps['newrelic.account_id']?.toString() ?:
            legacyAccountId ?:
            newrelicConfig.account_id?.toString() ?:
            ''
        String restUrl = restFromEnv ?:
            profileProps.rest_url?.toString() ?:
            globalRestUrl ?:
            newrelicConfig.rest_base_url?.toString() ?:
            rules.rest_base_url?.toString() ?:
            'https://api.eu.newrelic.com/v2'
        String compatibleGraphqlUrl = profileProps.rest_url || globalRestUrl ?
            deriveGraphqlUrl(restUrl) :
            ''
        String graphqlUrl = graphqlFromEnv ?:
            profileProps.graphql_url?.toString() ?:
            compatibleGraphqlUrl ?:
            newrelicConfig.graphql_url?.toString() ?:
            rules.graphql_url?.toString() ?:
            deriveGraphqlUrl(restUrl) ?:
            'https://api.eu.newrelic.com/graphql'
        Map sources = [
            api_key: apiKeyFromEnv ? 'env' :
                profileProps.api_key ? 'properties' :
                profileProps['newrelic.api_key'] ? 'properties' :
                legacyApiKey ? 'legacy' : 'default',
            account_id: accountFromEnv ? 'env' :
                profileProps.account_id ? 'properties' :
                profileProps['newrelic.account_id'] ? 'properties' :
                legacyAccountId ? 'legacy' :
                newrelicConfig.account_id ? 'default' : 'default',
            rest_url: restFromEnv ? 'env' :
                profileProps.rest_url ? 'properties' :
                globalRestUrl ? 'properties' :
                newrelicConfig.rest_base_url ? 'default' :
                rules.rest_base_url ? 'default' : 'default',
            graphql_url: graphqlFromEnv ? 'env' :
                profileProps.graphql_url ? 'properties' :
                compatibleGraphqlUrl ? 'derived' :
                newrelicConfig.graphql_url ? 'default' :
                rules.graphql_url ? 'default' :
                deriveGraphqlUrl(restUrl) ? 'derived' : 'default'
        ]
        new Resolved(
            id: profileId,
            accountId: accountId,
            restUrl: restUrl.replaceAll(/\/+$/, ''),
            graphqlUrl: graphqlUrl.replaceAll(/\/+$/, ''),
            apiKey: apiKey,
            sources: sources,
            configuredProfiles: profiles.keySet().sort() as List<String>
        )
    }

    static Map publicProfile(String id, Resolved resolved) {
        [
            id: id,
            account_id: resolved.accountId,
            rest_url: resolved.restUrl,
            graphql_url: resolved.graphqlUrl,
            source: resolved.sources,
            has_api_key: !!resolved.apiKey
        ]
    }

    static List<Map> publicProfiles(FrameworkPaths paths, Map config, Map rules) {
        File serviceDir = paths.serviceDir('newrelic')
        File propertiesFile = new File(serviceDir, 'newrelic.properties')
        Map flatProps = propertiesFile.isFile() ? PropertiesSupport.load(propertiesFile) : [:]
        Map profiles = propertiesFile.isFile() ? PropertiesSupport.controllers(propertiesFile) : [:]
        profiles.remove('newrelic')
        List<String> profileIds = profiles.keySet().collect { it.toString() }
        if (flatProps['newrelic.api_key'] || flatProps['newrelic.account_id']) {
            profileIds << 'default'
        }
        profileIds = profileIds.unique().sort()
        if (!profileIds) {
            String fallback = firstNonEmpty(
                System.getenv('NEW_RELIC_PROFILE'),
                System.getenv('NEWRELIC_PROFILE'),
                config.adapters?.newrelic?.default_profile?.toString(),
                rules.default_profile?.toString(),
                'default'
            )
            Resolved resolved = resolve(paths, config, rules, fallback)
            return [publicProfile(resolved.id, resolved)]
        }
        profileIds.collect { String id ->
            publicProfile(id, resolve(paths, config, rules, id))
        }
    }

    static Map authorizationHeaders(Resolved resolved) {
        if (!resolved.apiKey) {
            return [:]
        }
        [
            'Api-Key': resolved.apiKey,
            Accept: 'application/json'
        ]
    }

    private static String firstNonEmpty(String... values) {
        values.find { it?.trim() } ?: ''
    }

    private static String deriveGraphqlUrl(String restUrl) {
        if (!restUrl?.trim()) {
            return ''
        }
        try {
            URI uri = new URI(restUrl.trim())
            if (!uri.host) {
                return ''
            }
            return "https://${uri.host}/graphql"
        } catch (Exception ignored) {
            return ''
        }
    }

    static class Resolved {
        String id
        String accountId
        String restUrl
        String graphqlUrl
        String apiKey
        Map sources
        List<String> configuredProfiles
    }
}
