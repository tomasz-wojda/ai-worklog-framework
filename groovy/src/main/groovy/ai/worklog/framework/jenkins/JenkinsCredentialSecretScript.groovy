package ai.worklog.framework.jenkins

import java.nio.charset.StandardCharsets
import java.util.Base64

class JenkinsCredentialSecretScript {
    private static final String TEMPLATE = '''
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64

def requestedDomain = new String(
    Base64.decoder.decode('__DOMAIN__'),
    StandardCharsets.UTF_8
)
def requestedId = new String(
    Base64.decoder.decode('__ID__'),
    StandardCharsets.UTF_8
)
def maxBytes = __MAX_BYTES__

def fail = { code ->
    print JsonOutput.toJson([status: 'error', code: code])
}

try {
    def provider = SystemCredentialsProvider.getInstance()
    def domainEntry = provider.domainCredentialsMap.find { domain, credentials ->
        def name = domain?.name
        requestedDomain == '_' ?
            (name == null || name == '' || name == '_') :
            name == requestedDomain
    }
    if (!domainEntry) {
        fail('domain_not_found')
        return
    }
    def matches = domainEntry.value.findAll {
        it?.id?.toString() == requestedId
    }
    if (matches.size() != 1) {
        fail(matches ? 'credential_ambiguous' : 'credential_not_found')
        return
    }

    def credential = matches[0]
    def components = []
    def componentNames = [] as Set
    long totalBytes = 0

    def addBinary = { String name, byte[] value ->
        if (value == null || componentNames.contains(name)) {
            return
        }
        totalBytes += value.length
        if (totalBytes > maxBytes) {
            throw new IllegalStateException('limit')
        }
        componentNames << name
        components << [
            name: name,
            encoding: 'base64',
            value: Base64.encoder.encodeToString(value)
        ]
    }

    def addText = { String name, Object value ->
        if (value == null || componentNames.contains(name)) {
            return
        }
        String text = value.toString()
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8)
        boolean terminalSafe = text.every { character ->
            !Character.isISOControl((char) character) ||
                character in ['\\r' as char, '\\n' as char, '\\t' as char]
        }
        if (!terminalSafe) {
            addBinary(name, bytes)
            return
        }
        totalBytes += bytes.length
        if (totalBytes > maxBytes) {
            throw new IllegalStateException('limit')
        }
        componentNames << name
        components << [name: name, encoding: 'text', value: text]
    }

    Closure extract
    extract = { String name, Object value ->
        if (value == null) {
            return
        }
        String className = value.class.name
        if (className == 'hudson.util.Secret') {
            addText(name, value.getPlainText())
            return
        }
        if (className == 'com.cloudbees.plugins.credentials.SecretBytes') {
            addBinary(name, (byte[]) value.getPlainData())
            return
        }
        if (value instanceof byte[]) {
            addBinary(name, (byte[]) value)
            return
        }
        if (value instanceof char[]) {
            addText(name, new String((char[]) value))
            return
        }
        if (value instanceof InputStream) {
            InputStream stream = (InputStream) value
            try {
                addBinary(name, stream.bytes)
            } finally {
                stream.close()
            }
            return
        }
        if (value instanceof KeyStore) {
            def passwordValue = credential.metaClass.respondsTo(
                credential,
                'getPassword'
            ) ? credential.getPassword() : null
            String password = passwordValue?.class?.name == 'hudson.util.Secret' ?
                passwordValue.getPlainText() :
                passwordValue?.toString()
            ByteArrayOutputStream output = new ByteArrayOutputStream()
            ((KeyStore) value).store(
                output,
                (password ?: '').toCharArray()
            )
            addBinary(name, output.toByteArray())
            return
        }
        if (value instanceof Collection) {
            ((Collection) value).eachWithIndex { item, index ->
                extract("${name}[${index}]", item)
            }
            return
        }
        if (value.class.isArray()) {
            ((Object[]) value).eachWithIndex { item, index ->
                extract("${name}[${index}]", item)
            }
            return
        }
        if (value instanceof CharSequence || value instanceof Number) {
            addText(name, value)
        }
    }

    def attempted = [] as Set
    credential.class.methods
        .findAll {
            it.parameterCount == 0 &&
                it.name.startsWith('get') &&
                it.name != 'getClass' &&
                it.name.toLowerCase() ==~
                    /get.*(secret|password|passphrase|private.?key|token|content|key.?store|certificate|file).*/
        }
        .sort { it.name }
        .each { method ->
            if (attempted.add(method.name)) {
                String label = method.name.substring(3)
                label = label[0].toLowerCase() + label.substring(1)
                try {
                    extract(label, method.invoke(credential))
                } catch (IllegalStateException exception) {
                    throw exception
                } catch (Exception ignored) {
                }
            }
        }

    if (!components) {
        fail('unsupported_credential_type')
        return
    }

    print JsonOutput.toJson([
        status: 'ok',
        id: requestedId,
        credential_type: credential.class.name,
        components: components
    ])
} catch (IllegalStateException exception) {
    fail(exception.message == 'limit' ? 'secret_too_large' : 'extraction_failed')
} catch (Exception ignored) {
    fail('extraction_failed')
}
'''

    static String render(
        String domain,
        String credentialId,
        int maxBytes
    ) {
        TEMPLATE
            .replace(
                '__DOMAIN__',
                encode(domain)
            )
            .replace(
                '__ID__',
                encode(credentialId)
            )
            .replace(
                '__MAX_BYTES__',
                maxBytes.toString()
            )
    }

    private static String encode(String value) {
        Base64.encoder.encodeToString(
            value.getBytes(StandardCharsets.UTF_8)
        )
    }
}
