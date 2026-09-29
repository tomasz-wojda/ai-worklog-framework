package ai.worklog.framework.jenkins

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class JenkinsScriptSource {
    static Map read(String scriptFile, String inline, InputStream stdin, long maxBytes) {
        String source
        String path = null
        byte[] content
        if (inline != null) {
            source = 'inline'
            content = inline.getBytes(StandardCharsets.UTF_8)
        } else if (scriptFile == '-') {
            source = 'stdin'
            content = readLimited(stdin, maxBytes)
        } else {
            source = 'file'
            path = scriptFile
            File file = new File(scriptFile)
            if (!file.isFile()) {
                throw new IllegalArgumentException("Script file not found: ${scriptFile}")
            }
            content = file.withInputStream { InputStream input -> readLimited(input, maxBytes) }
        }
        if (content.length > maxBytes) {
            throw new IllegalArgumentException("Invalid script: exceeds ${maxBytes} bytes")
        }
        String text = decodeUtf8(content)
        if (!text.trim()) {
            throw new IllegalArgumentException('Invalid script: empty')
        }
        [
            source: source,
            path: path,
            text: text,
            bytes: content.length,
            sha256: sha256(content)
        ]
    }

    private static byte[] readLimited(InputStream input, long maxBytes) {
        input.readNBytes((int) Math.min(maxBytes + 1L, (long) Integer.MAX_VALUE - 8L))
    }

    private static String decodeUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (CharacterCodingException ignored) {
            throw new IllegalArgumentException('Invalid script: not UTF-8')
        }
    }

    private static String sha256(byte[] content) {
        MessageDigest.getInstance('SHA-256').digest(content).encodeHex().toString()
    }
}
