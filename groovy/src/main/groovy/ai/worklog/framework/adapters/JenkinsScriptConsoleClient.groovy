package ai.worklog.framework.adapters

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.util.Base64

class JenkinsScriptConsoleClient {
    final ReadOnlyHttp readHttp
    final FormHttp formHttp

    JenkinsScriptConsoleClient(
        ReadOnlyHttp readHttp,
        FormHttp formHttp = null
    ) {
        this.readHttp = readHttp
        this.formHttp = formHttp ?: new FormHttp()
    }

    Map execute(
        String baseUrl,
        String user,
        String token,
        String script,
        int timeoutSeconds,
        int responseBodyMaxCharacters
    ) {
        post(baseUrl, user, token, '/scriptText', [script: script], timeoutSeconds, responseBodyMaxCharacters)
    }

    Map post(
        String baseUrl,
        String user,
        String token,
        String path,
        Map form,
        int timeoutSeconds,
        int responseBodyMaxCharacters
    ) {
        String root = baseUrl.replaceAll(/\/+$/, '')
        Map headers = authorizationHeaders(user, token)
        Map crumbResponse = readHttp.get(
            "${root}/crumbIssuer/api/json",
            headers,
            timeoutSeconds,
            8192
        )
        if (crumbResponse.code == 200) {
            Map crumb
            try {
                crumb = (Map) new JsonSlurper().parseText(
                    crumbResponse.body?.toString() ?: ''
                )
            } catch (Exception ignored) {
                return [code: 0, body: '', error: 'Malformed crumb response']
            }
            if (!crumb.crumbRequestField || !crumb.crumb) {
                return [code: 0, body: '', error: 'Malformed crumb response']
            }
            headers[crumb.crumbRequestField.toString()] = crumb.crumb.toString()
        } else if (!(crumbResponse.code in [404, 405])) {
            return [
                code: crumbResponse.code ?: 0,
                body: '',
                error: 'Jenkins crumb request failed'
            ]
        }
        formHttp.post(
            "${root}${path}",
            headers,
            form,
            timeoutSeconds,
            responseBodyMaxCharacters
        )
    }

    private static Map authorizationHeaders(String user, String token) {
        String value = Base64.encoder.encodeToString(
            "${user}:${token}".getBytes(StandardCharsets.UTF_8)
        )
        [
            Authorization: "Basic ${value}",
            Accept: 'text/plain'
        ]
    }
}
