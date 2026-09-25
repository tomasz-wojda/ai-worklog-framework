package ai.worklog.framework.adapters

import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets

class FormHttp {
    Closure<Map> requestHandler

    Map post(
        String url,
        Map headers,
        Map form,
        int timeoutSeconds,
        int responseBodyMaxCharacters
    ) {
        if (requestHandler) {
            return requestHandler(
                url,
                headers,
                form,
                timeoutSeconds,
                responseBodyMaxCharacters
            )
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
                InternalSslSupport.applyHttpsConnection(
                    (HttpsURLConnection) connection,
                    uri
                )
            }
            connection.requestMethod = 'POST'
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            headers.each { key, value ->
                connection.setRequestProperty(
                    key.toString(),
                    value.toString()
                )
            }
            connection.setRequestProperty(
                'Content-Type',
                'application/x-www-form-urlencoded; charset=UTF-8'
            )
            byte[] body = form.collect { key, value ->
                "${encode(key)}=${encode(value)}"
            }.join('&').getBytes(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.length)
            connection.outputStream.withCloseable { it.write(body) }
            int code = connection.responseCode
            InputStream stream = code >= 400 ?
                connection.errorStream :
                connection.inputStream
            String responseBody = stream ?
                new String(
                    stream.readNBytes(responseBodyMaxCharacters),
                    StandardCharsets.UTF_8
                ) :
                ''
            stream?.close()
            [
                code: code,
                body: responseBody,
                error: code >= 400 ? "HTTP ${code}" : ''
            ]
        } catch (Exception exception) {
            [
                code: 0,
                body: '',
                error: exception.message ?: exception.class.simpleName
            ]
        } finally {
            connection?.disconnect()
        }
    }

    private static String encode(Object value) {
        URLEncoder.encode(
            value?.toString() ?: '',
            StandardCharsets.UTF_8.name()
        )
    }
}
