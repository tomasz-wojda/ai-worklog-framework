package ai.worklog.framework

import ai.worklog.framework.adapters.ArtifactoryAdapter
import ai.worklog.framework.adapters.ArtifactoryCredentials
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.artifactory.ArtifactoryOperatorReport
import ai.worklog.framework.commands.ArtifactoryCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

class ArtifactoryOperatorTest extends GroovyTestCase {
    private File repository
    private File workspace
    private File serviceDirectory
    private FrameworkPaths paths
    private Map rules

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-artifactory-test')
        serviceDirectory = new File(workspace, 'integrations/artifactory')
        serviceDirectory.mkdirs()
        paths = new FrameworkPaths(workspace)
        rules = ArtifactoryAdapter.loadOperatorRules(repository)
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testShellAssignmentParserAcceptsExportsAndQuotesWithoutEvaluation() {
        File file = new File(serviceDirectory, 'credentials')
        file.setText(
            """# ignored
export ARTIFACTORY_URL='https://art.example.test'
ARTIFACTORY_TOKEN="literal-\${NOT_EXPANDED}"
INVALID LINE
BROKEN='unterminated
""",
            'UTF-8'
        )
        Map values = ArtifactoryCredentials.parseAssignments(file, true)
        assertEquals('https://art.example.test', values.ARTIFACTORY_URL)
        assertEquals('literal-${NOT_EXPANDED}', values.ARTIFACTORY_TOKEN)
        assertFalse(values.containsKey('BROKEN'))
    }

    void testCredentialPrecedenceAndPublicProfileNeverExposeToken() {
        String legacyToken = 'legacy-secret-token'
        String propertyToken = 'property-secret-token'
        String environmentToken = 'environment-secret-token'
        new File(serviceDirectory, 'creds').setText(
            "ARTIFACTORY_URL=https://legacy.example.test\nARTIFACTORY_TOKEN=${legacyToken}\n",
            'UTF-8'
        )
        writeProperties(
            "primary.url=https://properties.example.test/root\nprimary.token=${propertyToken}\n" +
                "primary.auth_scheme=api-key\n"
        )
        ArtifactoryCredentials.Resolved resolved = ArtifactoryCredentials.resolve(
            paths,
            [:],
            rules,
            'primary',
            [
                ARTIFACTORY_URL: 'https://environment.example.test',
                ARTIFACTORY_TOKEN: environmentToken,
                ARTIFACTORY_AUTH_SCHEME: 'bearer'
            ]
        )
        assertEquals('https://environment.example.test/artifactory', resolved.baseUrl)
        assertEquals(environmentToken, resolved.token)
        assertEquals('bearer', resolved.selectedScheme)
        assertEquals([url: 'env', token: 'env', auth_scheme: 'env'], resolved.sources)
        String publicJson = JsonOutput.toJson(ArtifactoryCredentials.publicProfile(resolved, true))
        [legacyToken, propertyToken, environmentToken].each {
            assertFalse(publicJson.contains(it))
        }
        assertTrue(publicJson.contains('"has_token":true'))
    }

    void testCanonicalPropertyAndLegacyFilenamesResolveInOrder() {
        new File(serviceDirectory, 'creds').setText(
            "ARTIFACTORY_URL=https://creds.example.test\nARTIFACTORY_TOKEN=creds-token\n",
            'UTF-8'
        )
        new File(serviceDirectory, 'credentials').setText(
            "ARTIFACTORY_URL=https://credentials.example.test\nARTIFACTORY_TOKEN=credentials-token\n",
            'UTF-8'
        )
        ArtifactoryCredentials.Resolved legacy = ArtifactoryCredentials.resolve(
            paths, [:], rules, 'default', [:]
        )
        assertEquals('https://credentials.example.test/artifactory', legacy.baseUrl)
        assertEquals('credentials-token', legacy.token)

        writeProperties(
            "url=https://default.example.test\n" +
                "token=default-token\n" +
                "primary.url=https://primary.example.test/artifactory\n" +
                "primary.token=primary-token\n"
        )
        ArtifactoryCredentials.Resolved profile = ArtifactoryCredentials.resolve(
            paths, [:], rules, 'primary', [:]
        )
        assertEquals('https://primary.example.test/artifactory', profile.baseUrl)
        assertEquals('primary-token', profile.token)
    }

    void testUrlNormalizationAndSchemeSelection() {
        assertEquals(
            'https://example.test/root/artifactory',
            ArtifactoryCredentials.normalizeUrl('https://example.test/root/')
        )
        shouldFail(IllegalArgumentException) {
            ArtifactoryCredentials.normalizeUrl('file:///tmp/artifactory')
        }
        shouldFail(IllegalArgumentException) {
            ArtifactoryCredentials.normalizeUrl('https://user@example.test/artifactory')
        }
        writeProperties("url=https://example.test\ntoken=AKCp-legacy\n")
        assertEquals(
            'api-key',
            ArtifactoryCredentials.resolve(paths, [:], rules, 'default', [:]).selectedScheme
        )
        writeProperties("url=https://example.test\ntoken=reference-token\n")
        assertEquals(
            'bearer',
            ArtifactoryCredentials.resolve(paths, [:], rules, 'default', [:]).selectedScheme
        )
    }

    void testProfilesReportSafeConfigurationOnly() {
        String token = 'profile-secret-value'
        writeProperties(
            "primary.url=https://example.test\nprimary.token=${token}\nprimary.auth_scheme=bearer\n"
        )
        Map report = adapter { method, url, headers, timeout, max -> jsonResponse([:]) }
            .operatorProfiles()
        assertEquals(Status.READY, report.status)
        Map primary = report.items.find { it.id == 'primary' }
        assertNotNull(primary)
        assertTrue(primary.has_token as boolean)
        assertTrue(report.items.find { it.id == 'default' }.default as boolean)
        assertFalse(JsonOutput.toJson(report).contains(token))
    }

    void testStatusUsesOnlyPublicEndpointsAndReturnsVersion() {
        writeProperties("url=https://example.test\ntoken=unused-token\n")
        List<Map> requests = []
        ArtifactoryAdapter adapter = adapter { method, url, headers, timeout, max ->
            requests << [method: method, url: url, headers: headers]
            if (url.endsWith('/api/system/ping')) {
                return [code: 200, body: 'OK', error: '']
            }
            jsonResponse([version: '7.98.1', revision: '70980100'])
        }
        Map report = adapter.operatorStatus('default', 15)
        assertEquals(Status.READY, report.status)
        assertEquals('7.98.1', report.items[0].version)
        assertEquals(['GET', 'GET'], requests*.method)
        assertTrue(requests.every { !it.headers.Authorization && !it.headers['X-JFrog-Art-Api'] })
    }

    void testAutoAuthenticationRetriesAlternateHeaderOn401Only() {
        writeProperties("url=https://example.test\ntoken=reference-token\nauth_scheme=auto\n")
        List<Map> requests = []
        ArtifactoryAdapter retryAdapter = adapter { method, url, headers, timeout, max ->
            requests << new LinkedHashMap(headers)
            if (requests.size() == 1) {
                return [code: 401, body: '', error: '']
            }
            return jsonResponse([[key: 'releases', type: 'LOCAL', packageType: 'Maven', url: url]])
        }
        Map report = retryAdapter.operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals(Status.READY, report.status)
        assertEquals(2, requests.size())
        assertTrue(requests[0].Authorization?.startsWith('Bearer '))
        assertTrue(!!requests[1]['X-JFrog-Art-Api'])

        requests.clear()
        Map blocked = adapter { method, url, headers, timeout, max ->
            requests << new LinkedHashMap(headers)
            [code: 403, body: '', error: '']
        }.operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals(Status.BLOCKED, blocked.status)
        assertEquals(1, requests.size())
    }

    void testExplicitAuthenticationDoesNotRetry() {
        writeProperties("url=https://example.test\ntoken=reference-token\nauth_scheme=bearer\n")
        int calls = 0
        Map report = adapter { method, url, headers, timeout, max ->
            calls++
            [code: 401, body: '', error: '']
        }.operatorAuthTest('default', 'releases', '', 15)
        assertEquals(Status.BLOCKED, report.status)
        assertEquals(1, calls)
    }

    void testRepositoryFilteringSortingAndTruncation() {
        writeProperties(defaultProperties())
        List repositories = [
            [key: 'z-snapshots', type: 'LOCAL', packageType: 'Maven', url: 'https://example/z'],
            [key: 'releases-b', type: 'REMOTE', packageType: 'Maven', url: 'https://example/b'],
            [key: 'releases-a', type: 'LOCAL', packageType: 'Maven', url: 'https://example/a'],
            [key: 'docker-releases', type: 'LOCAL', packageType: 'Docker', url: 'https://example/d']
        ]
        Map report = adapter { method, url, headers, timeout, max -> jsonResponse(repositories) }
            .operatorRepositories('default', 'releases', 'local', 'maven', 1, 15)
        assertEquals(Status.READY, report.status)
        assertEquals(['releases-a'], report.items*.key)
        assertEquals([matched: 1, returned: 1], report.totals)

        Map truncated = adapter { method, url, headers, timeout, max -> jsonResponse(repositories) }
            .operatorRepositories('default', null, 'all', null, 2, 15)
        assertTrue(truncated.truncated)
        assertEquals(['docker-releases', 'releases-a'], truncated.items*.key)
    }

    void testArtifactListingFiltersDatesSortsAndEncodesPath() {
        writeProperties(defaultProperties())
        String requestedUrl
        List files = [
            [uri: '/old.xml', folder: false, size: 10, lastModified: '2026-09-15T10:00:00Z'],
            [uri: '/new file.xml', folder: false, size: '20', lastModified: '2026-09-17T10:00:00Z'],
            [uri: '/folder', folder: true, size: 0, lastModified: '2026-09-16T10:00:00Z']
        ]
        Map report = adapter { method, url, headers, timeout, max ->
            requestedUrl = url
            jsonResponse([files: files])
        }.operatorArtifacts(
            'default',
            'releases',
            'team/build output',
            '.xml',
            '2026-09-16',
            '2026-09-18',
            true,
            10,
            15
        )
        assertEquals(Status.READY, report.status)
        assertEquals(['new file.xml'], report.items*.uri.collect { it.replaceFirst('^/', '') })
        assertTrue(requestedUrl.contains('/team/build%20output?list&deep=1'))
        assertEquals(20L, report.items[0].size)
    }

    void testArtifactMetadataAndFolderMismatch() {
        writeProperties(defaultProperties())
        Map file = [
            repo: 'releases',
            path: '/a/file.json',
            size: '42',
            created: '2026-09-01T00:00:00Z',
            lastModified: '2026-09-02T00:00:00Z',
            lastUpdated: '2026-09-02T00:00:01Z',
            downloadUri: 'https://example.test/artifactory/releases/a/file.json',
            mimeType: 'application/json',
            checksums: [md5: 'abc', sha1: 'def', sha256: 'ghi']
        ]
        Map report = adapter { method, url, headers, timeout, max -> jsonResponse(file) }
            .operatorArtifact('default', 'releases', 'a/file.json', 15)
        assertEquals(Status.READY, report.status)
        assertEquals(42L, report.items[0].size)
        assertEquals('ghi', report.items[0].checksums.sha256)

        Map folder = adapter { method, url, headers, timeout, max ->
            jsonResponse([repo: 'releases', path: '/a', children: []])
        }.operatorArtifact('default', 'releases', 'a', 15)
        assertEquals(Status.ERROR, folder.status)
        assertEquals('user', folder.error_kind)
        assertTrue(folder.message.contains('artifacts'))
    }

    void testManifestIsBoundedRejectsBinaryAndNeverWrites() {
        writeProperties(defaultProperties())
        int requestedMaximum
        Map report = adapter { method, url, headers, timeout, max ->
            requestedMaximum = max
            [code: 200, body: 'abcdef', error: '']
        }.operatorManifest('default', 'releases', 'path/file.txt', 5, 15)
        assertEquals(Status.READY, report.status)
        assertEquals(6, requestedMaximum)
        assertTrue(report.truncated)
        assertEquals('abcde', report.items[0].content)
        assertFalse(new File(workspace, 'path/file.txt').exists())

        Map unicode = adapter { method, url, headers, timeout, max ->
            [code: 200, body: 'a€b', error: '']
        }.operatorManifest('default', 'releases', 'path/unicode.txt', 2, 15)
        assertEquals('a', unicode.items[0].content)
        assertEquals(1, unicode.items[0].bytes)
        assertTrue(unicode.items[0].content.getBytes('UTF-8').length <= 2)

        Map binary = adapter { method, url, headers, timeout, max ->
            [code: 200, body: "abc\u0000def", error: '']
        }.operatorManifest('default', 'releases', 'path/file.bin', 10, 15)
        assertEquals(Status.ERROR, binary.status)
        assertEquals('user', binary.error_kind)
    }

    void testValidationRejectsTraversalInvalidRepositoryLimitsAndDates() {
        writeProperties(defaultProperties())
        shouldFail(IllegalArgumentException) {
            adapter().operatorArtifact('default', '../bad', 'file.txt', 15)
        }
        ['/absolute', 'a/../b', 'a//b', 'a\\b', 'a/./b'].each { path ->
            shouldFail(IllegalArgumentException) {
                adapter().operatorArtifact('default', 'releases', path, 15)
            }
        }
        shouldFail(IllegalArgumentException) {
            adapter().operatorRepositories('default', null, 'all', null, 1001, 15)
        }
        shouldFail(IllegalArgumentException) {
            adapter().operatorArtifacts(
                'default', 'releases', '', null, '2026-09-18', '2026-09-17', false, 10, 15
            )
        }
        shouldFail(IllegalArgumentException) {
            adapter().operatorManifest('default', 'releases', 'file.txt', 1048577, 15)
        }
    }

    void testMalformedTransportNotFoundAndServerResponsesMapToRequiredErrors() {
        writeProperties(defaultProperties())
        Map malformed = adapter { method, url, headers, timeout, max ->
            [code: 200, body: '{bad', error: '']
        }.operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals([Status.ERROR, 'system'], [malformed.status, malformed.error_kind])

        Map transport = adapter { method, url, headers, timeout, max ->
            [code: 0, body: '', error: 'secret-token-must-not-appear']
        }.operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals([Status.ERROR, 'system'], [transport.status, transport.error_kind])
        assertFalse(JsonOutput.toJson(transport).contains('secret-token-must-not-appear'))

        Map missing = adapter { method, url, headers, timeout, max ->
            [code: 404, body: '', error: '']
        }.operatorArtifact('default', 'releases', 'missing.txt', 15)
        assertEquals([Status.ERROR, 'user'], [missing.status, missing.error_kind])

        [429, 500].each { code ->
            Map failed = adapter { method, url, headers, timeout, max ->
                [code: code, body: '', error: '']
            }.operatorRepositories('default', null, 'all', null, 10, 15)
            assertEquals([Status.ERROR, 'system'], [failed.status, failed.error_kind])
        }
    }

    void testMissingConfigurationAndAccessFailuresAreBlocked() {
        Map missing = adapter().operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals(Status.BLOCKED, missing.status)
        assertEquals('Artifactory URL unavailable', missing.message)

        writeProperties("url=https://example.test\n")
        Map tokenMissing = adapter().operatorRepositories('default', null, 'all', null, 10, 15)
        assertEquals(Status.BLOCKED, tokenMissing.status)
        assertEquals('Artifactory token unavailable', tokenMissing.message)
    }

    void testReportRenderingRedactsNestedSensitiveValuesAndPreservesSafeMetadata() {
        String token = 'very-secret-token-value'
        Redaction redaction = new Redaction(repository)
        ArtifactoryOperatorReport report = ArtifactoryOperatorReport.fromPayload([
            operation: 'profiles',
            fetched_at: ArtifactoryAdapter.utcNow(),
            status: Status.READY,
            message: 'safe',
            items: [[
                id: 'default',
                default: true,
                has_url: true,
                has_token: true,
                auth_scheme: 'bearer',
                source: [url: 'properties', token: 'legacy', auth_scheme: 'default'],
                token: token
            ]]
        ])
        String json = report.renderJson(redaction)
        String human = report.renderHuman(redaction)
        assertFalse(json.contains(token))
        assertFalse(human.contains(token))
        Map parsed = (Map) new JsonSlurper().parseText(json)
        assertTrue(parsed.items[0].has_token)
        assertEquals('bearer', parsed.items[0].auth_scheme)
        assertEquals('legacy', parsed.items[0].source.token)
    }

    void testReportExitCodesMatchStatusMapping() {
        ExitCodes codes = new ExitCodes(repository)
        assertEquals(
            codes.success,
            ArtifactoryOperatorReport.exitCodeFor(report(Status.READY, null), codes)
        )
        assertEquals(
            codes.blocked,
            ArtifactoryOperatorReport.exitCodeFor(report(Status.BLOCKED, 'blocked'), codes)
        )
        assertEquals(
            codes.userError,
            ArtifactoryOperatorReport.exitCodeFor(report(Status.ERROR, 'user'), codes)
        )
        assertEquals(
            codes.systemError,
            ArtifactoryOperatorReport.exitCodeFor(report(Status.ERROR, 'system'), codes)
        )
    }

    void testCliProfilesAndErrorsAreStructuredAndSecretSafe() {
        String token = 'cli-secret-token-value'
        writeProperties("url=https://example.test\ntoken=${token}\n")
        Map profiles = captureStreams {
            ArtifactoryCommands.run(
                'profiles', ['--json'], repository, paths, ConfigLoader.load(workspace)
            )
        }
        assertEquals(0, profiles.code)
        assertFalse(profiles.out.contains(token))
        assertTrue(profiles.out.contains('"has_token": true'))

        Map unknown = captureStreams {
            ArtifactoryCommands.run(
                'repositories', ['--unknown', '--json'], repository, paths, ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).userError, unknown.code)
        assertEquals('user', new JsonSlurper().parseText(unknown.out).error_kind)

        Map repeated = captureStreams {
            ArtifactoryCommands.run(
                'repositories',
                ['--limit', '1', '--limit', '2', '--json'],
                repository,
                paths,
                ConfigLoader.load(workspace)
            )
        }
        assertEquals(new ExitCodes(repository).userError, repeated.code)
        assertTrue(new JsonSlurper().parseText(repeated.out).message.contains('Repeated option'))
    }

    void testEveryRemoteOperationUsesGet() {
        writeProperties(defaultProperties())
        List<String> methods = []
        Closure handler = { method, url, headers, timeout, max ->
            methods << method
            if (url.endsWith('/api/system/ping')) return [code: 200, body: 'OK', error: '']
            if (url.endsWith('/api/system/version')) return jsonResponse([version: '7', revision: '1'])
            if (url.endsWith('/api/repositories')) return jsonResponse([])
            if (url.contains('?list')) return jsonResponse([files: []])
            if (url.contains('/api/storage/')) {
                return jsonResponse([
                    repo: 'releases',
                    path: '/file.txt',
                    size: '1',
                    downloadUri: 'https://example/file.txt'
                ])
            }
            [code: 200, body: 'text', error: '']
        }
        ArtifactoryAdapter adapter = adapter(handler)
        adapter.operatorStatus('default', 15)
        adapter.operatorAuthTest('default', 'releases', '', 15)
        adapter.operatorRepositories('default', null, 'all', null, 10, 15)
        adapter.operatorArtifacts('default', 'releases', '', null, null, null, false, 10, 15)
        adapter.operatorArtifact('default', 'releases', 'file.txt', 15)
        adapter.operatorManifest('default', 'releases', 'file.txt', 10, 15)
        assertFalse(methods.isEmpty())
        assertTrue(methods.every { it == 'GET' })
    }

    private ArtifactoryAdapter adapter(Closure handler = null) {
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: handler ?: {
            method, url, headers, timeout, max -> jsonResponse([])
        })
        new ArtifactoryAdapter(paths, http, rules, [:], [:])
    }

    private void writeProperties(String content) {
        new File(serviceDirectory, 'artifactory.properties').setText(content, 'UTF-8')
    }

    private static String defaultProperties() {
        'url=https://example.test\ntoken=test-reference-token\nauth_scheme=bearer\n'
    }

    private static Map jsonResponse(Object value) {
        [code: 200, body: JsonOutput.toJson(value), error: '']
    }

    private ArtifactoryOperatorReport report(Status status, String errorKind) {
        ArtifactoryOperatorReport.fromPayload([
            operation: 'test',
            fetched_at: ArtifactoryAdapter.utcNow(),
            status: status,
            error_kind: errorKind,
            items: []
        ])
    }

    private static Map captureStreams(Closure<Integer> work) {
        PrintStream originalOut = System.out
        PrintStream originalErr = System.err
        ByteArrayOutputStream stdout = new ByteArrayOutputStream()
        ByteArrayOutputStream stderr = new ByteArrayOutputStream()
        try {
            System.setOut(new PrintStream(stdout))
            System.setErr(new PrintStream(stderr))
            int code = work()
            [
                code: code,
                out: stdout.toString('UTF-8'),
                err: stderr.toString('UTF-8')
            ]
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
