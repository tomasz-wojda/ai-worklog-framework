package ai.worklog.framework

import ai.worklog.framework.adapters.BinaryDownloadClient
import groovy.test.GroovyTestCase

import java.nio.file.Files

class BinaryDownloadClientTest extends GroovyTestCase {
    private File root
    private File target

    void setUp() {
        root = File.createTempDir('ai-worklog-download-', '-test')
        File parent = new File(root, 'nested')
        parent.mkdirs()
        target = new File(parent, 'artifact.bin')
    }

    void tearDown() {
        root.deleteDir()
    }

    void testPreservesBinaryBytesExactly() {
        byte[] content = [0, 1, 2, 127, 128, 255] as byte[]
        BinaryDownloadClient client = clientFor(200, content, content.length)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [Authorization: 'secret'],
            target,
            300,
            1024,
            2,
            false
        )
        assertEquals(200, result.code)
        assertEquals(content.length as long, result.bytes)
        assertEquals(content.toList(), target.bytes.toList())
        assertNoParts()
    }

    void testRejectsContentLengthBeforeWriting() {
        BinaryDownloadClient client = clientFor(200, [1] as byte[], 6)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            false
        )
        assertTrue(result.error.contains('exceeds download limit'))
        assertFalse(target.exists())
        assertNoParts()
    }

    void testRejectsStreamThatExceedsLimitAndRemovesPart() {
        BinaryDownloadClient client = clientFor(200, [1, 2, 3, 4, 5, 6] as byte[], -1)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            false
        )
        assertTrue(result.error.contains('exceeds download limit'))
        assertFalse(target.exists())
        assertNoParts()
    }

    void testRefusesExistingFileWithoutForce() {
        target.bytes = [9] as byte[]
        boolean called = false
        BinaryDownloadClient client = new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
            called = true
            [code: 200, stream: new ByteArrayInputStream([1] as byte[]), content_length: 1]
        })
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            false
        )
        assertFalse(called)
        assertTrue(result.error.contains('already exists'))
        assertEquals([9], target.bytes.toList())
    }

    void testForceAtomicallyReplacesExistingFile() {
        target.bytes = [9] as byte[]
        BinaryDownloadClient client = clientFor(200, [1, 2] as byte[], 2)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            true
        )
        assertTrue(result.replaced)
        assertEquals([1, 2], target.bytes.toList())
        assertNoParts()
    }

    void testRejectsExistingSymlinkEvenWithForce() {
        File outside = new File(root, 'outside.bin')
        outside.bytes = [9] as byte[]
        target.delete()
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        BinaryDownloadClient client = clientFor(200, [1] as byte[], 1)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            true
        )
        assertTrue(result.error.contains('Invalid destination'))
        assertEquals([9], outside.bytes.toList())
    }

    void testHttpFailuresCreateNoDestination() {
        [401, 403, 404, 500].each { int code ->
            BinaryDownloadClient client = new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
                [code: code, error: "HTTP ${code}", content_length: -1]
            })
            Map result = client.download(
                'https://jenkins.example/artifact',
                [:],
                target,
                300,
                5,
                2,
                false
            )
            assertEquals(code, result.code)
            assertFalse(target.exists())
        }
        BinaryDownloadClient timeout = new BinaryDownloadClient(requestHandler: { url, headers, seconds ->
            throw new SocketTimeoutException('timed out')
        })
        assertEquals(
            0,
            timeout.download(
                'https://jenkins.example/artifact',
                [:],
                target,
                300,
                5,
                2,
                false
            ).code
        )
        assertFalse(target.exists())
        assertNoParts()
    }

    void testReportsBytesAndContentLength() {
        BinaryDownloadClient client = clientFor(200, [1, 2, 3] as byte[], 3)
        Map result = client.download(
            'https://jenkins.example/artifact',
            [:],
            target,
            300,
            5,
            2,
            false
        )
        assertEquals(3L, result.bytes)
        assertEquals(3L, result.content_length)
        assertNoParts()
    }

    private static BinaryDownloadClient clientFor(int code, byte[] content, long contentLength) {
        new BinaryDownloadClient(requestHandler: { url, headers, timeout ->
            [
                code: code,
                stream: new ByteArrayInputStream(content),
                content_length: contentLength,
                error: code == 200 ? '' : "HTTP ${code}"
            ]
        })
    }

    private void assertNoParts() {
        assertEquals([], target.parentFile.listFiles().findAll { it.name.endsWith('.part') })
    }
}
