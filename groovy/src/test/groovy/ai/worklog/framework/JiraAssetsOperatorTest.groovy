package ai.worklog.framework

import ai.worklog.framework.adapters.JiraAssetsAdapter
import ai.worklog.framework.adapters.JiraOperatorAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.commands.JiraCommands
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Redaction
import ai.worklog.framework.core.Status
import ai.worklog.framework.jira.JiraOperatorReport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

class JiraAssetsOperatorTest extends GroovyTestCase {
    static final String PIPELINE_URL =
        'https://jenkins.example/view/prod-main/job/prod-blue-Deploy/'

    private File repository
    private File workspace
    private FrameworkPaths paths
    private Map rules
    private List<Map> requests

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-jira-assets-test')
        new File(workspace, 'integrations/jira').mkdirs()
        new File(workspace, 'integrations/jira/jira.properties').text =
            'jira.url=https://jira.example/\njira.token=top-secret-token\n'
        paths = new FrameworkPaths(workspace)
        rules = JiraCommands.loadRules(repository, paths)
        requests = []
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testAssetsObjectRequestsObjectWithBearerGet() {
        Map report = assets { url -> response(ciObject()) }.object('CI-174110')
        assertEquals(Status.READY, report.status)
        assertEquals(1, requests.size())
        assertEquals('GET', requests[0].method)
        assertEquals('https://jira.example/rest/insight/1.0/object/CI-174110', requests[0].url)
        assertEquals('Bearer top-secret-token', requests[0].headers.Authorization)
        assertEquals('CI-174110', report.object_key)
    }

    void testObjectNormalizationKeepsAttributeNamesAndPolishCharacters() {
        Map item = assets { url -> response(ciObject()) }.object('CI-174110').items[0]
        assertEquals('CI-174110', item.key)
        assertEquals('A.A.PROD.SOF', item.label)
        assertEquals('Aplikacja', item.object_type)
        assertTrue(item.attributes.keySet().containsAll([
            'Status', 'Ścieżka do Artifactory', 'URL do pipeline',
            'Zautomatyzowane', 'Niedostępny w zgłoszeniach Jira'
        ]))
        assertEquals(['releases/example/jira-versions/SOF/'], item.attributes['Ścieżka do Artifactory'])
        assertEquals(['DEV', 'OPS'], item.attributes['Zespoły'])
        assertEquals(['A.A.PROD.Parent'], item.attributes['Rodzic'])
        assertEquals(['CI-1'], item.referenced_keys)

        JiraOperatorReport report = JiraOperatorReport.fromPayload(
            assets { url -> response(ciObject()) }.object('CI-174110')
        )
        Map json = (Map) new JsonSlurper().parseText(report.renderJson(redaction()))
        assertEquals(
            ['releases/example/jira-versions/SOF/'],
            json.items[0].attributes['Ścieżka do Artifactory']
        )
        assertTrue(report.renderHuman(redaction()).contains('Niedostępny w zgłoszeniach Jira'))
    }

    void testAttributeValuesAreCapped() {
        rules.limits.assets_values_max = 2
        Map raw = ciObject()
        raw.attributes << attribute('Wiele', ['a', 'b', 'c'])
        Map item = assets { url -> response(raw) }.object('CI-174110').items[0]
        assertEquals(['a', 'b'], item.attributes['Wiele'])
    }

    void testGetCiReturnsLiveFieldValuesAsRead() {
        Map report = assets { url -> response(ciObject()) }.ci('CI-174110')
        assertEquals(Status.READY, report.status)
        Map item = report.items[0]
        assertEquals('PROD', item.env)
        assertEquals('SOF', item.app)
        assertEquals('releases/example/jira-versions/SOF/', item.fields.artifactory_path)
        assertEquals('true', item.fields.automated)
        assertEquals(PIPELINE_URL, item.fields.pipeline_url)
        assertEquals('URL do pipeline', item.field_attributes.pipeline_url)
    }

    void testGetCiHumanOutputPrintsCiBlock() {
        JiraOperatorReport report = JiraOperatorReport.fromPayload(
            assets { url -> response(ciObject()) }.ci('CI-174110')
        )
        String output = report.renderHuman(redaction())
        assertTrue(output.contains('  Object: CI-174110 / A.A.PROD.SOF'))
        assertTrue(output.contains('    Ścieżka do Artifactory: releases/example/jira-versions/SOF/'))
        assertTrue(output.contains('    Zautomatyzowane: true'))
        assertTrue(output.contains("    URL do pipeline: ${PIPELINE_URL}"))
    }

    void testGetCiMissingAttributeYieldsEmptyValue() {
        Map raw = ciObject()
        raw.attributes = raw.attributes.findAll { it.objectTypeAttribute.name != 'URL do pipeline' }
        Map item = assets { url -> response(raw) }.ci('CI-174110').items[0]
        assertEquals('', item.fields.pipeline_url)
    }

    void testGetCiRejectsNonApplicationLabel() {
        Map raw = ciObject()
        raw.label = 'Serwer-01'
        Map payload = assets { url -> response(raw) }.ci('CI-174110')
        assertEquals(Status.ERROR, payload.status)
        assertTrue(payload.message.contains('not an application CI'))
        assertEquals(
            new ExitCodes(repository).userError,
            JiraOperatorReport.exitCodeFor(JiraOperatorReport.fromPayload(payload), new ExitCodes(repository))
        )
    }

    void testGetCisListsKeyIdAndLabelSortedAndFiltered() {
        Map report = assets { url ->
            response(searchPage([
                header('CI-3', 'A.A.TEST.Zeta'),
                header('CI-1', 'A.A.PROD.SOF'),
                header('CI-2', 'Serwer A.A.PROD.X'),
                header('CI-4', 'A.A.DEV.Alpha')
            ], 4))
        }.cis(null, 100)
        assertEquals(Status.READY, report.status)
        assertEquals(['A.A.DEV.Alpha', 'A.A.PROD.SOF', 'A.A.TEST.Zeta'], report.items*.label)
        assertEquals([key: 'CI-1', id: '1', label: 'A.A.PROD.SOF'], report.items[1])
        assertEquals(3, report.totals.cis)
        assertFalse(report.truncated)
        Map query = queryOf(requests[0].url)
        assertEquals('Name LIKE "A.A."', query.iql)
        assertEquals('false', query.includeAttributes)
        assertEquals('200', query.resultPerPage)
    }

    void testGetCisEnvUsesTemplateAndDropsSubstringMatches() {
        Map report = assets { url ->
            response(searchPage([header('CI-1', 'A.A.PROD.SOF'), header('CI-2', 'A.A.PRODX.SOF')], 2))
        }.cis('PROD', 100)
        assertEquals(['CI-1'], report.items*.key)
        assertEquals('PROD', report.env)
        assertEquals('Name LIKE "A.A.PROD."', queryOf(requests[0].url).iql)
    }

    void testGetCisPagesPastFilteredResultsAndTruncates() {
        rules.limits.ci_list_page_size = 2
        Map report = assets { url ->
            String page = queryOf(url).page
            switch (page) {
                case '1':
                    return response(searchPage([header('CI-1', 'other'), header('CI-2', 'A.A.PROD.A')], 5))
                case '2':
                    return response(searchPage([header('CI-3', 'other'), header('CI-4', 'A.A.PROD.B')], 5))
                default:
                    return response(searchPage([header('CI-5', 'A.A.PROD.C')], 5))
            }
        }.cis(null, 2)
        assertEquals(['CI-2', 'CI-4'], report.items*.key)
        assertTrue(report.truncated)
        assertEquals(3, requests.size())
    }

    void testSearchResolvesAttributeNamesAndPages() {
        rules.limits.assets_page_size = 1
        Map entry = [
            id: 7,
            objectKey: 'CI-7',
            label: 'A.A.PROD.Portal',
            objectType: [name: 'Aplikacja'],
            attributes: [[
                objectTypeAttributeId: 11,
                objectAttributeValues: [[displayValue: 'true']]
            ]]
        ]
        Map report = assets { url ->
            Map page = searchPage([queryOf(url).page == '1' ? entry : entry + [objectKey: 'CI-8']], 3)
            page.objectTypeAttributes = [[id: 11, name: 'Zautomatyzowane']]
            response(page)
        }.search('Name = "A.A.PROD.Portal"', 2)
        assertEquals(['CI-7', 'CI-8'], report.items*.key)
        assertEquals(['true'], report.items[0].attributes['Zautomatyzowane'])
        assertTrue(report.truncated)
        assertEquals('true', queryOf(requests[0].url).includeTypeAttributes)
    }

    void testSearchStopsWhenResultsAreExhausted() {
        Map report = assets { url -> response(searchPage([header('CI-1', 'A.A.PROD.A')], 1)) }
            .search('objectType = Aplikacja', 50)
        assertEquals(1, report.items.size())
        assertFalse(report.truncated)
        assertEquals(1, requests.size())
    }

    void testSearchEncodesNonAsciiQueryAndQuotes() {
        assets { url -> response(searchPage([], 0)) }.search('Name = "A.A.PROD.PortalZleceń"', 10)
        String url = requests[0].url
        assertTrue(url.startsWith('https://jira.example/rest/insight/1.0/iql/objects?'))
        assertTrue(url.contains('PortalZlece%C5%84'))
        assertTrue(url.contains('%22'))
        assertEquals('Name = "A.A.PROD.PortalZleceń"', queryOf(url).iql)
    }

    void testSearchUsesConfiguredAqlParameter() {
        rules.api_paths.assets_search = '/aql/objects'
        rules.api_params.assets_search_query = 'qlQuery'
        assets { url -> response(searchPage([], 0)) }.search('Key = CI-1', 10)
        assertTrue(requests[0].url.contains('/rest/insight/1.0/aql/objects?'))
        assertEquals('Key = CI-1', queryOf(requests[0].url).qlQuery)
    }

    void testListEndpointsAcceptPlainAndWrappedResponses() {
        Map wrapped = assets { url ->
            response([objectschemas: [[id: 3, objectSchemaKey: 'CMDB', name: 'CMDB', objectCount: 9]]])
        }.schemas()
        assertEquals([[id: '3', key: 'CMDB', name: 'CMDB', object_count: 9L]], wrapped.items)
        assertTrue(requests[0].url.endsWith('/rest/insight/1.0/objectschema/list'))

        Map types = assets { url ->
            response([[id: 5, name: 'Aplikacja', parentObjectTypeId: 2, objectCount: 4]])
        }.types(3)
        assertEquals('2', types.items[0].parent_id)
        assertEquals(3, types.schema_id)
        assertTrue(requests[1].url.endsWith('/rest/insight/1.0/objectschema/3/objecttypes/flat'))

        Map attributes = assets { url ->
            response([[
                id: 11,
                name: 'Rodzic',
                type: 1,
                referenceObjectType: [name: 'Aplikacja'],
                minimumCardinality: 0,
                maximumCardinality: 1
            ]])
        }.attributes(5)
        assertEquals([
            id: '11', name: 'Rodzic', type: 'Object', referenced_type: 'Aplikacja', minimum: 0, maximum: 1
        ], attributes.items[0])
        assertTrue(requests[2].url.endsWith('/rest/insight/1.0/objecttype/5/attributes'))
    }

    void testMalformedResponsesAreErrors() {
        assertEquals(JiraAssetsAdapter.MALFORMED, assets { url -> response([unexpected: true]) }.schemas().message)
        assertEquals(JiraAssetsAdapter.MALFORMED, assets { url -> response([total: 1]) }.search('x', 5).message)
        assertEquals(JiraAssetsAdapter.MALFORMED, assets { url -> response([id: 1]) }.object('CI-1').message)
        Map invalid = assets { url -> [code: 200, body: 'not json', error: ''] }.ci('CI-1')
        assertEquals(Status.ERROR, invalid.status)
    }

    void testHttpStatusMapping() {
        Map missing = assets { url -> [code: 404, body: '', error: 'missing'] }.object('CI-999')
        assertEquals(Status.ERROR, missing.status)
        assertEquals('Jira item not found', missing.message)
        [401, 403].each { code ->
            assertEquals(Status.BLOCKED, assets { url -> [code: code, body: '', error: ''] }.cis(null, 5).status)
        }
        assertEquals(Status.ERROR, assets { url -> [code: 500, body: '', error: ''] }.schemas().status)
    }

    void testMissingCredentialsAreBlockedWithoutRequests() {
        new File(workspace, 'integrations/jira/jira.properties').delete()
        JiraAssetsAdapter adapter = assets { url -> response([:]) }
        [
            adapter.schemas(), adapter.types(1), adapter.attributes(1), adapter.object('CI-1'),
            adapter.search('x', 1), adapter.ci('CI-1'), adapter.cis(null, 1)
        ].each {
            assertEquals(Status.BLOCKED, it.status)
            assertEquals('Jira credentials unavailable', it.message)
        }
        assertEquals([], requests)
    }

    void testEveryRequestUsesGet() {
        JiraAssetsAdapter adapter = assets { url ->
            url.contains('/object/') ? response(ciObject()) : response(searchPage([], 0))
        }
        adapter.object('CI-174110')
        adapter.ci('CI-174110')
        adapter.search('x', 5)
        adapter.cis('PROD', 5)
        assertEquals(['GET'] as Set, requests*.method as Set)
    }

    void testTokenInAttributeValueIsRedacted() {
        Map raw = ciObject()
        raw.attributes << attribute('Notatka', ['Bearer top-secret-token'])
        JiraOperatorReport report = JiraOperatorReport.fromPayload(
            assets { url -> response(raw) }.object('CI-174110')
        )
        [report.renderJson(redaction()), report.renderHuman(redaction())].each {
            assertFalse(it.contains('top-secret-token'))
            assertTrue(it.contains('REDACTED'))
        }
    }

    void testReportJsonCarriesContextFields() {
        Map json = (Map) new JsonSlurper().parseText(
            JiraOperatorReport.fromPayload(
                assets { url -> response(searchPage([header('CI-1', 'A.A.PROD.SOF')], 1)) }.cis('PROD', 5)
            ).renderJson(redaction())
        )
        assertEquals('get-cis', json.operation)
        assertEquals('PROD', json.env)
        assertEquals('Name LIKE "A.A.PROD."', json.query)
        assertEquals([cis: 1], json.totals)
    }

    private JiraAssetsAdapter assets(Closure<Map> handler) {
        ReadOnlyHttp http = new ReadOnlyHttp(requestHandler: { method, url, headers, timeout ->
            requests << [method: method, url: url, headers: headers]
            handler(url)
        })
        new JiraAssetsAdapter(new JiraOperatorAdapter(paths, http, rules), rules)
    }

    private Redaction redaction() {
        new Redaction(repository)
    }

    private static Map queryOf(String url) {
        url.substring(url.indexOf('?') + 1).split('&').collectEntries {
            List<String> parts = it.split('=', 2) as List
            [(URLDecoder.decode(parts[0], 'UTF-8')): URLDecoder.decode(parts[1], 'UTF-8')]
        }
    }

    private static Map response(Object payload) {
        [code: 200, body: JsonOutput.toJson(payload), error: '']
    }

    private static Map searchPage(List<Map> entries, int total) {
        [objectEntries: entries, totalFilterCount: total]
    }

    private static Map header(String key, String label) {
        [id: key.substring(3) as int, objectKey: key, label: label, objectType: [name: 'Aplikacja']]
    }

    private static Map attribute(String name, List<String> values) {
        [
            objectTypeAttribute: [name: name],
            objectAttributeValues: values.collect { [displayValue: it] }
        ]
    }

    private static Map ciObject() {
        [
            id: 174110,
            objectKey: 'CI-174110',
            label: 'A.A.PROD.SOF',
            objectType: [name: 'Aplikacja'],
            created: '2024-01-01T00:00:00.000Z',
            updated: '2026-10-01T00:00:00.000Z',
            attributes: [
                attribute('Status', ['Aktywny']),
                attribute('Ścieżka do Artifactory', ['releases/example/jira-versions/SOF/']),
                attribute('URL do pipeline', [PIPELINE_URL]),
                attribute('Zautomatyzowane', ['true']),
                attribute('Niedostępny w zgłoszeniach Jira', ['false']),
                attribute('Zespoły', ['DEV', 'OPS']),
                [
                    objectTypeAttribute: [name: 'Rodzic'],
                    objectAttributeValues: [[
                        displayValue: 'A.A.PROD.Parent',
                        referencedObject: [objectKey: 'CI-1', label: 'A.A.PROD.Parent']
                    ]]
                ]
            ]
        ]
    }
}
