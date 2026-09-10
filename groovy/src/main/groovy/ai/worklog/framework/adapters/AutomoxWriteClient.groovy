package ai.worklog.framework.adapters

class AutomoxWriteClient {
    final JsonWriteHttp http
    final boolean apply

    AutomoxWriteClient(JsonWriteHttp http, boolean apply) {
        this.http = http
        this.apply = apply
    }

    Map send(String method, String url, Map headers, Object body, int timeoutSeconds, int errorBodyMaxCharacters) {
        if (!apply) {
            throw new IllegalStateException('Mutation blocked: apply is false')
        }
        http.send(method, url, headers, body, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map post(String url, Map headers, Map payload, int timeoutSeconds, int errorBodyMaxCharacters) {
        send('POST', url, headers, payload, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map put(String url, Map headers, Map payload, int timeoutSeconds, int errorBodyMaxCharacters) {
        send('PUT', url, headers, payload, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map delete(String url, Map headers, int timeoutSeconds, int errorBodyMaxCharacters) {
        send('DELETE', url, headers, null, timeoutSeconds, errorBodyMaxCharacters)
    }
}
