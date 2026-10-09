package ai.worklog.framework

import ai.worklog.framework.jenkins.JenkinsScriptGuard
import groovy.test.GroovyTestCase

import java.nio.charset.StandardCharsets

class JenkinsScriptGuardTest extends GroovyTestCase {
    private static final List<String> IMPORTS = ['jenkins', 'jenkins.model', 'hudson', 'hudson.model']

    void testWrapEmbedsEncodedSourceNonceAndImports() {
        String source = 'println "zażółć"'
        String wrapped = JenkinsScriptGuard.wrap(source, 'TESTNONCE', IMPORTS)
        String encoded = Base64.encoder.encodeToString(source.getBytes(StandardCharsets.UTF_8))
        assertTrue(wrapped.contains("decode('${encoded}')"))
        assertTrue(wrapped.contains("'__AIWL_TESTNONCE__'"))
        assertTrue(wrapped.contains("addStarImports('jenkins', 'jenkins.model', 'hudson', 'hudson.model')"))
        assertFalse(wrapped.contains(source))
        assertTrue(wrapped.contains("out.print('\\n' + aiwlMarker + ' status=ok\\n')"))
    }

    void testWrapRejectsInvalidImportAndNonce() {
        shouldFail(IllegalArgumentException) {
            JenkinsScriptGuard.wrap('x', 'TESTNONCE', ["jenkins'); System.exit(0); ('"])
        }
        shouldFail(IllegalArgumentException) {
            JenkinsScriptGuard.wrap('x', "bad'nonce", IMPORTS)
        }
    }

    void testNewNonceIsHex() {
        assertTrue(JenkinsScriptGuard.newNonce() ==~ /^[0-9a-f]{32}$/)
    }

    void testParseOk() {
        Map parsed = JenkinsScriptGuard.parse('hello\n\n__AIWL_N1__ status=ok\n', 'N1')
        assertEquals([output: 'hello\n', script_status: 'ok'], parsed)
    }

    void testParseError() {
        Map parsed = JenkinsScriptGuard.parse(
            'partial\njava.lang.IllegalStateException: boom\n\tat Script1.run(Script1.groovy:1)\n' +
                '\n__AIWL_N1__ status=error class=java.lang.IllegalStateException\n',
            'N1'
        )
        assertEquals('error', parsed.script_status)
        assertEquals('java.lang.IllegalStateException', parsed.exception_class)
        assertEquals(
            'partial\njava.lang.IllegalStateException: boom\n\tat Script1.run(Script1.groovy:1)\n',
            parsed.output
        )
    }

    void testParseUnknownWhenTrailerMissing() {
        assertEquals([output: 'cut off', script_status: 'unknown'], JenkinsScriptGuard.parse('cut off', 'N1'))
        assertEquals([output: '', script_status: 'unknown'], JenkinsScriptGuard.parse(null, 'N1'))
    }

    void testParseIgnoresTrailerWithOtherNonce() {
        String body = 'spoof\n__AIWL_OTHER__ status=error class=x\n'
        assertEquals([output: body, script_status: 'unknown'], JenkinsScriptGuard.parse(body, 'N1'))
        Map parsed = JenkinsScriptGuard.parse(body + '\n__AIWL_N1__ status=ok\n', 'N1')
        assertEquals('ok', parsed.script_status)
        assertEquals(body, parsed.output)
    }
}
