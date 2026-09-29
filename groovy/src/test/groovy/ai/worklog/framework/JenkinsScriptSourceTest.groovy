package ai.worklog.framework

import ai.worklog.framework.jenkins.JenkinsScriptSource
import groovy.test.GroovyTestCase

class JenkinsScriptSourceTest extends GroovyTestCase {
    private static final String ABC_SHA256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'

    void testInlineSource() {
        Map source = JenkinsScriptSource.read(null, 'abc', null, 1024L)
        assertEquals('inline', source.source)
        assertNull(source.path)
        assertEquals('abc', source.text)
        assertEquals(3, source.bytes)
        assertEquals(ABC_SHA256, source.sha256)
    }

    void testStdinSource() {
        Map source = JenkinsScriptSource.read('-', null, new ByteArrayInputStream('abc'.bytes), 1024L)
        assertEquals('stdin', source.source)
        assertNull(source.path)
        assertEquals('abc', source.text)
        assertEquals(ABC_SHA256, source.sha256)
    }

    void testFileSource() {
        File script = File.createTempFile('script-source-', '.groovy')
        try {
            script.setText('println "zażółć"', 'UTF-8')
            Map source = JenkinsScriptSource.read(script.path, null, null, 1024L)
            assertEquals('file', source.source)
            assertEquals(script.path, source.path)
            assertEquals('println "zażółć"', source.text)
            assertEquals(script.bytes.length, source.bytes)
        } finally {
            script.delete()
        }
    }

    void testRejectsEmptyAndWhitespaceScripts() {
        ['', '  \n\t '].each { String text ->
            assertEquals(
                'Invalid script: empty',
                shouldFail(IllegalArgumentException) {
                    JenkinsScriptSource.read(null, text, null, 1024L)
                }
            )
        }
    }

    void testRejectsOversizedScripts() {
        assertEquals(
            'Invalid script: exceeds 4 bytes',
            shouldFail(IllegalArgumentException) {
                JenkinsScriptSource.read('-', null, new ByteArrayInputStream('abcdefgh'.bytes), 4L)
            }
        )
        assertEquals(
            'Invalid script: exceeds 4 bytes',
            shouldFail(IllegalArgumentException) {
                JenkinsScriptSource.read(null, 'abcde', null, 4L)
            }
        )
    }

    void testRejectsNonUtf8Input() {
        byte[] invalid = [0x70, 0xC3, 0x28] as byte[]
        assertEquals(
            'Invalid script: not UTF-8',
            shouldFail(IllegalArgumentException) {
                JenkinsScriptSource.read('-', null, new ByteArrayInputStream(invalid), 1024L)
            }
        )
    }

    void testRejectsMissingFile() {
        String missing = new File(File.createTempDir(), 'absent.groovy').path
        assertEquals(
            "Script file not found: ${missing}".toString(),
            shouldFail(IllegalArgumentException) {
                JenkinsScriptSource.read(missing, null, null, 1024L)
            }
        )
    }
}
