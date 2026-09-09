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
        if (requestHandler) {
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
            connection.requestMethod = 'POST'
            connection.doOutput = true
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            headers.each { key, value ->
                connection.setRequestProperty(key.toString(), value.toString())
            }
            connection.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
            byte[] body = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.length)
            connection.outputStream.withCloseable { it.write(body) }
            int code = connection.responseCode
            InputStream stream = code >= 400 ? connection.errorStream : connection.inputStream
            String responseBody = stream ?
                new String(stream.readNBytes(errorBodyMaxCharacters), StandardCharsets.UTF_8) :
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
