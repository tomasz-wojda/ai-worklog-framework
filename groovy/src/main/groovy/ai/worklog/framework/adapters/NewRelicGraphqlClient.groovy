package ai.worklog.framework.adapters

import groovy.json.JsonSlurper

class NewRelicGraphqlClient {
    final JsonWriteHttp http
    final boolean apply
    final Set<String> allowedMutations
    final List<Integer> readSuccessCodes
    final List<Integer> mutationSuccessCodes
    final JsonSlurper slurper = new JsonSlurper()

    NewRelicGraphqlClient(
        JsonWriteHttp http,
        boolean apply,
        Collection<String> allowedMutations,
        List<Integer> readSuccessCodes = [200],
        List<Integer> mutationSuccessCodes = [200]
    ) {
        this.http = http ?: new JsonWriteHttp()
        this.apply = apply
        this.allowedMutations = (allowedMutations ?: []).collect { it.toString() } as Set
        this.readSuccessCodes = readSuccessCodes ?: [200]
        this.mutationSuccessCodes = mutationSuccessCodes ?: [200]
    }

    Map query(
        String graphqlUrl,
        Map headers,
        String operation,
        Map variables,
        int timeoutSeconds,
        int responseBodyMaxCharacters,
        int errorBodyMaxCharacters
    ) {
        if (operation?.toString()?.toLowerCase()?.contains('mutation')) {
            throw new IllegalStateException('GraphQL query blocked: mutation operations require mutate()')
        }
        Map response = http.post(
            graphqlUrl,
            headers,
            [query: operation, variables: variables ?: [:]],
            timeoutSeconds,
            errorBodyMaxCharacters,
            responseBodyMaxCharacters
        )
        parseGraphqlResponse(response, readSuccessCodes)
    }

    Map mutate(
        String mutationName,
        String graphqlUrl,
        Map headers,
        String operation,
        Map variables,
        int timeoutSeconds,
        int responseBodyMaxCharacters,
        int errorBodyMaxCharacters
    ) {
        if (!apply) {
            throw new IllegalStateException('Mutation blocked: apply is false')
        }
        String name = mutationName?.toString()
        if (!name || !allowedMutations.contains(name)) {
            throw new IllegalArgumentException("GraphQL mutation not allowlisted: ${name ?: '(missing)'}")
        }
        Map response = http.post(
            graphqlUrl,
            headers,
            [query: operation, variables: variables ?: [:]],
            timeoutSeconds,
            errorBodyMaxCharacters,
            responseBodyMaxCharacters
        )
        parseGraphqlResponse(response, mutationSuccessCodes)
    }

    private Map parseGraphqlResponse(Map response, List<Integer> successCodes) {
        int code = (response.code ?: 0) as int
        if (!successCodes.collect { it as int }.contains(code)) {
            throw new IllegalStateException(sanitizeError(response.error ?: "NerdGraph returned HTTP ${code}"))
        }
        Object parsed = parseJson(response.body?.toString())
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException('NerdGraph returned an invalid JSON payload')
        }
        Map payload = (Map) parsed
        if (payload.errors instanceof List && payload.errors) {
            List messages = payload.errors.collect { item ->
                item instanceof Map ? item.message?.toString() : item?.toString()
            }.findAll { it }
            throw new IllegalStateException(sanitizeError(messages ? messages.join('; ') : 'NerdGraph returned errors'))
        }
        [
            code: code,
            data: payload.data instanceof Map ? (Map) payload.data : [:],
            body: response.body?.toString() ?: ''
        ]
    }

    private Object parseJson(String body) {
        if (!body?.trim()) {
            return [:]
        }
        slurper.parseText(body)
    }

    private static String sanitizeError(String message) {
        String safe = message ?: 'NerdGraph request failed'
        safe = safe.replaceAll(/(?i)(api[-_]?key|authorization|bearer|NRAK-[A-Z0-9]+)[^\s]*/,'[redacted]')
        safe
    }
}
