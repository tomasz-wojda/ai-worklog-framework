package ai.worklog.framework.jenkins

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.Pattern

class JenkinsScriptGuard {
    static final String MARKER_PREFIX = '__AIWL_'
    static final int TRAILER_RESERVE_BYTES = 1024
    private static final Pattern IMPORT_NAME = ~/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/
    private static final Pattern NONCE = ~/^[A-Za-z0-9]+$/
    private static final String TEMPLATE = '''
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import java.nio.charset.StandardCharsets
import java.util.Base64

def aiwlSource = new String(Base64.decoder.decode('@@SOURCE@@'), StandardCharsets.UTF_8)
def aiwlMarker = '@@MARKER@@'
def aiwlConfiguration = new CompilerConfiguration()
aiwlConfiguration.addCompilationCustomizers(new ImportCustomizer().addStarImports(@@IMPORTS@@))
try {
    new GroovyShell(
        jenkins.model.Jenkins.get().pluginManager.uberClassLoader,
        binding,
        aiwlConfiguration
    ).evaluate(aiwlSource, 'Script1.groovy')
    out.print('\\n' + aiwlMarker + ' status=ok\\n')
} catch (Throwable aiwlError) {
    aiwlError.printStackTrace(out)
    out.print('\\n' + aiwlMarker + ' status=error class=' + aiwlError.class.name + '\\n')
}
out.flush()
'''

    static String newNonce() {
        UUID.randomUUID().toString().replace('-', '')
    }

    static String wrap(String script, String nonce, List<String> starImports) {
        validateNonce(nonce)
        List<String> imports = (starImports ?: []).collect { it.toString() }
        imports.each { name ->
            if (!(name ==~ IMPORT_NAME)) {
                throw new IllegalArgumentException("Invalid script import: ${name}")
            }
        }
        TEMPLATE
            .replace('@@SOURCE@@', Base64.encoder.encodeToString(script.getBytes(StandardCharsets.UTF_8)))
            .replace('@@MARKER@@', marker(nonce))
            .replace('@@IMPORTS@@', imports.collect { "'${it}'" }.join(', '))
    }

    static Map parse(String body, String nonce) {
        String text = body ?: ''
        String trailer = "\n${marker(nonce)} status="
        int index = text.lastIndexOf(trailer)
        if (index < 0) {
            return [output: text, script_status: 'unknown']
        }
        String rest = text.substring(index + trailer.length())
        int lineEnd = rest.indexOf('\n')
        List<String> tokens = (lineEnd >= 0 ? rest.substring(0, lineEnd) : rest).trim().tokenize(' ')
        String status = tokens ? tokens[0] : ''
        String output = text.substring(0, index)
        if (status == 'ok') {
            return [output: output, script_status: 'ok']
        }
        if (status == 'error') {
            String className = tokens.find { it.startsWith('class=') }?.substring(6)
            return [output: output, script_status: 'error', exception_class: className ?: 'unknown']
        }
        [output: text, script_status: 'unknown']
    }

    private static String marker(String nonce) {
        "${MARKER_PREFIX}${nonce}__"
    }

    private static void validateNonce(String nonce) {
        if (!(nonce ==~ NONCE)) {
            throw new IllegalArgumentException('Invalid script nonce')
        }
    }
}
