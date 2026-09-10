package ai.worklog.framework.adapters

import groovy.json.JsonOutput

import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets

class JsonWriteHttp {
    Closure<Map> requestHandler

    Map post(
        String url,
        Map headers,
        Map payload,
        int timeoutSeconds,
        int errorBodyMaxCharacters
    ) {
        return send('POST', url, headers, payload, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map post(
        String url,
        Map headers,
        Map payload,
        int timeoutSeconds,
        int errorBodyMaxCharacters,
        int responseBodyMaxCharacters
    ) {
        return send('POST', url, headers, payload, timeoutSeconds, errorBodyMaxCharacters, responseBodyMaxCharacters)
    }

    Map put(
        String url,
        Map headers,
        Map payload,
        int timeoutSeconds,
        int errorBodyMaxCharacters
    ) {
        return send('PUT', url, headers, payload, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map delete(
        String url,
        Map headers,
        int timeoutSeconds,
        int errorBodyMaxCharacters
    ) {
        send('DELETE', url, headers, null, timeoutSeconds, errorBodyMaxCharacters)
    }

    Map send(
        String method,
        String url,
        Map headers,
        Object payload,
        int timeoutSeconds,
        int errorBodyMaxCharacters
    ) {
        send(method, url, headers, payload, timeoutSeconds, errorBodyMaxCharacters, 0)
    }

    Map send(
        String method,
        String url,
        Map headers,
        Object payload,
        int timeoutSeconds,
        int errorBodyMaxCharacters,
        int responseBodyMaxCharacters
    ) {
        if (requestHandler) {
            if (requestHandler.maximumNumberOfParameters >= 7) {
                return requestHandler(method, url, headers, payload, timeoutSeconds,
                    errorBodyMaxCharacters, responseBodyMaxCharacters)
            }
            if (requestHandler.maximumNumberOfParameters >= 6) {
                return requestHandler(method, url, headers, payload, timeoutSeconds, errorBodyMaxCharacters)
            }
            return requestHandler(url, headers, payload, timeoutSeconds)
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
        HttpURLConnection connection
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection()
            if (connection instanceof HttpsURLConnection) {
                InternalSslSupport.applyHttpsConnection((HttpsURLConnection) connection, uri)
            }
            connection.requestMethod = method
            connection.doOutput = payload != null
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            headers.each { key, value ->
                connection.setRequestProperty(key.toString(), value.toString())
            }
            if (payload != null) {
                connection.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
                byte[] body = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.length)
                connection.outputStream.withCloseable { it.write(body) }
            }
            int code = connection.responseCode
            InputStream stream = code >= 400 ? connection.errorStream : connection.inputStream
            int bodyLimit = code >= 400 ?
                errorBodyMaxCharacters :
                (responseBodyMaxCharacters > 0 ? responseBodyMaxCharacters : errorBodyMaxCharacters)
            String responseBody = stream ?
                new String(stream.readNBytes(bodyLimit), StandardCharsets.UTF_8) :
                ''
            stream?.close()
            [
                code: code,
                body: responseBody,
                error: code >= 400 ? "HTTP ${code}" : ''
            ]
        } catch (Exception exception) {
            [code: 0, body: '', error: exception.message ?: exception.class.simpleName]
        } finally {
            connection?.disconnect()
        }
    }
}
