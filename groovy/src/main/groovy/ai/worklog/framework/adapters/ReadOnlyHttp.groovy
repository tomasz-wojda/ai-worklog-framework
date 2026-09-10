package ai.worklog.framework.adapters

import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets

class ReadOnlyHttp {
    Closure<Map> requestHandler

    Map request(
        String method,
        String url,
        Map headers = [:],
        int timeoutSeconds = 10,
        int responseBodyMaxCharacters = 0
    ) {
        if (requestHandler) {
            if (requestHandler.maximumNumberOfParameters >= 5) {
                return requestHandler(method, url, headers, timeoutSeconds, responseBodyMaxCharacters)
            }
            return requestHandler(method, url, headers, timeoutSeconds)
        }
        URI uri
        try {
            uri = new URI(url)
        } catch (Exception ignored) {
            return [code: 0, body: '', error: 'Invalid URL']
        }
        if (!(uri.scheme in ['http', 'https']) || !uri.host) {
            return [code: 0, body: '', error: 'Invalid URL']
        }
        HttpURLConnection connection = null
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection()
            if (connection instanceof HttpsURLConnection) {
                InternalSslSupport.applyHttpsConnection((HttpsURLConnection) connection, uri)
            }
            connection.requestMethod = method
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            headers.each { key, value ->
                connection.setRequestProperty(key.toString(), value.toString())
            }
            int code = connection.responseCode
            InputStream stream = code >= 400 ? connection.errorStream : connection.inputStream
            String body
            if (stream) {
                if (responseBodyMaxCharacters > 0) {
                    body = new String(stream.readNBytes(responseBodyMaxCharacters), StandardCharsets.UTF_8)
                } else {
                    body = new String(stream.bytes, StandardCharsets.UTF_8)
                }
            } else {
                body = ''
            }
            [code: code, body: body, error: code >= 400 ? body : '']
        } catch (Exception exception) {
            [code: 0, body: '', error: exception.message ?: exception.class.simpleName]
        } finally {
            connection?.disconnect()
        }
    }

    Map get(String url, Map headers = [:], int timeoutSeconds = 10, int responseBodyMaxCharacters = 0) {
        request('GET', url, headers, timeoutSeconds, responseBodyMaxCharacters)
    }
}
