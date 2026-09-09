package ai.worklog.framework.adapters

import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class BinaryDownloadClient {
    Closure<Map> requestHandler

    Map download(
        String url,
        Map headers,
        File target,
        int timeoutSeconds,
        long maxBytes,
        int bufferBytes,
        boolean force
    ) {
        URI uri
        try {
            uri = new URI(url)
        } catch (Exception ignored) {
            return failure(0, 'Invalid URL')
        }
        if (!(uri.scheme in ['http', 'https']) || !uri.host) {
            return failure(0, 'Invalid URL')
        }

        Map destinationError = validateDestination(target, force)
        if (destinationError) {
            return destinationError
        }

        if (requestHandler) {
            Map response
            try {
                response = requestHandler(url, headers, timeoutSeconds)
            } catch (Exception exception) {
                return failure(0, exception.message ?: exception.class.simpleName)
            }
            return consumeResponse(response ?: [:], target, maxBytes, bufferBytes, force)
        }

        HttpURLConnection connection
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection()
            if (connection instanceof HttpsURLConnection) {
                InternalSslSupport.applyHttpsConnection((HttpsURLConnection) connection, uri)
            }
            connection.requestMethod = 'GET'
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            headers.each { key, value ->
                connection.setRequestProperty(key.toString(), value.toString())
            }
            int code = connection.responseCode
            long contentLength = connection.getHeaderFieldLong('Content-Length', -1L)
            if (code != 200) {
                InputStream errorStream = connection.errorStream
                String error = errorStream ?
                    new String(errorStream.readNBytes(4096), StandardCharsets.UTF_8) :
                    "HTTP ${code}"
                errorStream?.close()
                return failure(code, error, contentLength)
            }
            return consumeResponse([
                code: code,
                stream: connection.inputStream,
                content_length: contentLength
            ], target, maxBytes, bufferBytes, force)
        } catch (Exception exception) {
            return failure(0, exception.message ?: exception.class.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private static Map consumeResponse(
        Map response,
        File target,
        long maxBytes,
        int bufferBytes,
        boolean force
    ) {
        int code = (response.code ?: 0) as int
        long contentLength = response.content_length == null ?
            -1L :
            response.content_length as long
        if (code != 200) {
            closeQuietly(response.stream)
            return failure(code, response.error?.toString() ?: "HTTP ${code}", contentLength)
        }
        if (contentLength > maxBytes) {
            closeQuietly(response.stream)
            return failure(
                code,
                "Artifact exceeds download limit: ${contentLength} > ${maxBytes}",
                contentLength
            )
        }
        InputStream input = response.stream instanceof InputStream ?
            (InputStream) response.stream :
            null
        if (!input) {
            return failure(code, 'Download response has no stream', contentLength)
        }

        Path targetPath = target.toPath()
        Path parent = targetPath.parent
        if (!parent || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            closeQuietly(input)
            return failure(0, "Destination directory not found: ${parent}")
        }

        Path temporary
        long written = 0L
        boolean replaced = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
        try {
            temporary = Files.createTempFile(parent, ".${target.name}.", '.part')
            byte[] buffer = new byte[bufferBytes]
            OutputStream output = Files.newOutputStream(temporary)
            try {
                int count
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue
                    }
                    written += count
                    if (written > maxBytes) {
                        throw new IOException(
                            "Artifact exceeds download limit: ${written} > ${maxBytes}"
                        )
                    }
                    output.write(buffer, 0, count)
                }
            } finally {
                output.close()
                input.close()
            }
            List<StandardCopyOption> options = [StandardCopyOption.ATOMIC_MOVE]
            if (force) {
                options << StandardCopyOption.REPLACE_EXISTING
            }
            Files.move(
                temporary,
                targetPath,
                options as StandardCopyOption[]
            )
            temporary = null
            [
                code: code,
                bytes: written,
                content_length: contentLength,
                replaced: replaced,
                error: ''
            ]
        } catch (Exception exception) {
            closeQuietly(input)
            failure(code, exception.message ?: exception.class.simpleName, contentLength)
        } finally {
            if (temporary) {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private static Map validateDestination(File target, boolean force) {
        Path path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return [:]
        }
        if (Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return failure(0, "Invalid destination: ${target}")
        }
        if (!force) {
            return failure(0, "Destination already exists: ${target}")
        }
        [:]
    }

    private static Map failure(int code, String error, long contentLength = -1L) {
        [
            code: code,
            bytes: 0L,
            content_length: contentLength,
            replaced: false,
            error: error
        ]
    }

    private static void closeQuietly(Object stream) {
        if (stream instanceof Closeable) {
            try {
                ((Closeable) stream).close()
            } catch (Exception ignored) {
            }
        }
    }
}
