package ai.worklog.framework

import ai.worklog.framework.reconciliation.ReconciliationReport
import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.cli.ArgumentParser
import ai.worklog.framework.cli.CommandContract
import ai.worklog.framework.cli.ParsedArguments
import ai.worklog.framework.cli.UsageError
import ai.worklog.framework.cli.UsageRenderer
import ai.worklog.framework.core.JsonFiles
import groovy.test.GroovyTestCase

class CommandContractTest extends GroovyTestCase {
    private File repository
    private CommandContract contract
    private Map rules

    void setUp() {
        repository = new File('..').canonicalFile
        contract = CommandContract.load(repository)
        rules = (Map) JsonFiles.read(new File(repository, 'shared/jenkins-operator-rules.json'), [:])
    }

    void testContractValidatesAgainstSchema() {
        Map schema = (Map) JsonFiles.read(new File(repository, 'schemas/command-contract.schema.json'), [:])
        assertEquals([], validate(contract.data, schema, schema, '$'))
    }

    void testMainCommandsAndContractCommandsAgree() {
        Set expected = [
            'workspace', 'config', 'catalog', 'ticket', 'state', 'preflight', 'reconcile',
            'service', 'day', 'delivery', 'closeout', 'diag', 'toolchain', 'help'
        ] as Set
        assertEquals(expected, contract.commands()*.name as Set)
    }

    void testRootRenderingMatchesPreviousMainHelp() {
        String expected = '''usage: ai-worklog [--runtime groovy|python] [--workspace PATH] [-w NAME] [--workspace-name NAME] [--version]
                  {config,workspace,catalog,ticket,state,preflight,reconcile,service,day,delivery,closeout,diag,toolchain} ...

DevOps daily workflow automation framework

commands:
  config       Machine-wide runtime and AI vault settings
  workspace    Workspace lifecycle, IDE profiles, and registry
  catalog      Logical systems and delivery relationships
  ticket       Ticket preparation
  state        Structured ticket state
  preflight    Environment preflight checks
  reconcile    Cross-system read-only reconciliation
  service      External service operators
  day          Daily routines
  delivery     Delivery state tracking
  closeout     Close-out and handover
  diag         Diagnostic packs
  toolchain    Python/Java/Groovy detection and routing
'''
        assertEquals(expected, new UsageRenderer(contract).renderRoot())
    }

    void testServiceRenderingListsJenkinsOperator() {
        String rendered = new UsageRenderer(contract).renderPath(['service'])
        assertTrue(rendered.contains('list'))
        assertTrue(rendered.contains('jenkins'))
    }

    void testJenkinsRenderingListsFifteenActions() {
        List<Map> actions = contract.children(['service', 'jenkins'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jenkins'])
        assertEquals(15, actions.size())
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
    }

    void testJiraRenderingListsEightActions() {
        List<Map> actions = contract.children(['service', 'jira'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jira'])
        assertEquals(8, actions.size())
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'jira', 'ticket'])
                .contains('<ticket_key>')
        )
    }

    void testArtifactsRenderingDocumentsPositionalsAndSelectors() {
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jenkins', 'artifacts'])
        assertTrue(rendered.contains('<controller> <job> <build_selector>'))
        assertTrue(rendered.contains('last-successful | last-completed | BUILD_NUMBER'))
    }

    void testDownloadArtifactContractAndHelp() {
        Map action = jenkinsAction('download-artifact')
        assertEquals(
            ['controller', 'job', 'build_selector', 'artifact'],
            action.positionals*.name
        )
        assertEquals(['--json', '--apply', '--force'], action.options*.name)
        String rendered = new UsageRenderer(contract).renderPath(
            ['service', 'jenkins', 'download-artifact']
        )
        assertTrue(rendered.contains('<controller> <job> <build_selector> <artifact>'))
        assertTrue(rendered.contains('dry-run unless --apply'))
        Map schema = (Map) JsonFiles.read(
            new File(repository, 'schemas/command-contract.schema.json'),
            [:]
        )
        assertEquals(
            [],
            validate(
                new UsageRenderer(contract).describePath(
                    ['service', 'jenkins', 'download-artifact']
                ),
                schema,
                schema,
                '$'
            )
        )
    }

    void testPositionalOrderIsAuthoritative() {
        Map action = jenkinsAction('artifacts')
        String message = shouldFail(UsageError) {
            parser().parse('jenkins', action, ['last-successful', 'job', 'primary'], rules)
        }
        assertTrue(message.contains("Invalid build_selector: 'primary'"))
    }

    void testArgumentFailuresHaveSpecifiedMessages() {
        assertEquals(
            'Unknown option for jenkins health: --bad',
            failure('health', ['primary', '--bad'])
        )
        assertEquals(
            'Repeated option: --limit',
            failure('queue', ['primary', '--limit', '1', '--limit', '2'])
        )
        assertEquals(
            'Missing value for --limit',
            failure('queue', ['primary', '--limit'])
        )
        assertEquals(
            'Unexpected argument for jenkins health: extra',
            failure('health', ['primary', 'extra'])
        )
        assertEquals('Missing controller', failure('health', []))
    }

    void testDoubleDashTerminatesOptionParsing() {
        ParsedArguments parsed = parser().parse(
            'jenkins',
            jenkinsAction('syntax-check'),
            ['--', '--named-file'],
            rules
        )
        assertEquals(['--named-file'], parsed.variadic('files'))
    }

    void testVariadicPositionalConsumesRemainingTokens() {
        ParsedArguments parsed = parser().parse(
            'jenkins',
            jenkinsAction('syntax-check'),
            ['one', 'two', 'three'],
            rules
        )
        assertEquals(['one', 'two', 'three'], parsed.variadic('files'))
    }

    void testDefaultsResolveFromOperatorRules() {
        Map configured = JsonFiles.deepMerge(rules, [max_builds: 8, credential_domain: 'custom'])
        assertEquals(
            '8',
            parser().parse('jenkins', jenkinsAction('job'), ['c', 'j'], configured)
                .value('--builds').toString()
        )
        assertEquals(
            'custom',
            parser().parse('jenkins', jenkinsAction('credentials'), ['c'], configured)
                .value('--domain')
        )
        assertEquals(
            rules.limits.queue_default.toString(),
            parser().parse('jenkins', jenkinsAction('queue'), ['c'], rules)
                .value('--limit').toString()
        )
        assertEquals(
            rules.limits.jobs_default.toString(),
            parser().parse('jenkins', jenkinsAction('jobs'), ['c'], rules)
                .value('--limit').toString()
        )
    }

    void testValueValidationUsesAnyDeclaredConstraint() {
        Map action = [
            name: 'sample',
            positionals: [[
                name: 'value',
                description: 'Value',
                required: true,
                value: [
                    choices: ['named'],
                    pattern: '^[1-9][0-9]*$',
                    integer_min: 100,
                    help_values: 'named | POSITIVE | INTEGER_100_OR_GREATER'
                ]
            ]],
            options: []
        ]
        assertEquals('named', parser().parse('test', action, ['named']).positional('value'))
        assertEquals('5', parser().parse('test', action, ['5']).positional('value'))
        assertTrue(shouldFail(UsageError) {
            parser().parse('test', action, ['invalid'])
        }.contains('expected named | POSITIVE | INTEGER_100_OR_GREATER'))
    }

    void testBuildSelectorContractAgreesWithAdapter() {
        Map action = jenkinsAction('artifacts')
        ['last-successful', 'last-completed', '42'].each { String selector ->
            assertNotNull parser().parse('jenkins', action, ['c', 'j', selector], rules)
            assertNotNull resolveBuildSelector(selector)
        }
        ['0', '-1', 'invalid'].each { String selector ->
            shouldFail(UsageError) {
                parser().parse('jenkins', action, ['c', 'j', selector], rules)
            }
            shouldFail(IllegalArgumentException) {
                resolveBuildSelector(selector)
            }
        }
    }

    void testDescribeOutputValidatesAgainstSchema() {
        Map schema = (Map) JsonFiles.read(new File(repository, 'schemas/command-contract.schema.json'), [:])
        UsageRenderer renderer = new UsageRenderer(contract)
        assertEquals([], validate(renderer.describePath(['service']), schema, schema, '$'))
        assertEquals([], validate(renderer.describePath(['service', 'jenkins']), schema, schema, '$'))
        assertEquals(
            [],
            validate(renderer.describePath(['service', 'jenkins', 'artifacts']), schema, schema, '$')
        )
    }

    private ArgumentParser parser() {
        new ArgumentParser(contract)
    }

    private Map jenkinsAction(String name) {
        contract.node(['service', 'jenkins', name])
    }

    private String failure(String action, List<String> args) {
        shouldFail(UsageError) {
            parser().parse('jenkins', jenkinsAction(action), args, rules)
        }
    }

    private static Object resolveBuildSelector(String selector) {
        ReconciliationReport.name
        JenkinsAdapter.resolveBuildSelector(selector)
    }

    private static List<String> validate(Object value, Map schema, Map root, String path) {
        if (schema.'$ref') {
            Map resolved = root
            schema.'$ref'.toString().substring(2).split('/').each { resolved = (Map) resolved[it] }
            return validate(value, resolved, root, path)
        }
        if (schema['oneOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['oneOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.count { !it } == 1 ? [] : ["${path}: expected exactly one schema match"]
        }
        if (schema['anyOf'] instanceof List) {
            List<List<String>> outcomes = ((List<Map>) schema['anyOf']).collect {
                validate(value, it, root, path)
            }
            return outcomes.any { !it } ? [] : ["${path}: expected at least one schema match"]
        }
        List<String> errors = []
        if (schema['const'] != null && value != schema['const']) {
            errors << "${path}: expected ${schema['const']}"
        }
        if (schema['enum'] instanceof List && !((List) schema['enum']).contains(value)) {
            errors << "${path}: unexpected value ${value}"
        }
        if (schema['type'] == 'object' || schema['required'] || schema['properties'] || schema['not']) {
            if (!(value instanceof Map)) {
                return ["${path}: expected object"]
            }
            Map map = (Map) value
            ((List) (schema['required'] ?: [])).each {
                if (!map.containsKey(it)) errors << "${path}: missing ${it}"
            }
            if (schema['not'] instanceof Map && !validate(value, (Map) schema['not'], root, path)) {
                errors << "${path}: matched forbidden schema"
            }
            Map properties = (Map) (schema['properties'] ?: [:])
            map.each { key, item ->
                if (properties[key] instanceof Map) {
                    errors.addAll(validate(item, (Map) properties[key], root, "${path}.${key}"))
                } else if (schema['additionalProperties'] == false) {
                    errors << "${path}: unexpected ${key}"
                }
            }
        } else if (schema['type'] == 'array') {
            if (!(value instanceof List)) {
                return ["${path}: expected array"]
            }
            List list = (List) value
            if (schema['minItems'] != null && list.size() < (schema['minItems'] as int)) {
                errors << "${path}: too few items"
            }
            if (schema['uniqueItems'] == true && list.unique().size() != list.size()) {
                errors << "${path}: duplicate items"
            }
            if (schema['items'] instanceof Map) {
                list.eachWithIndex { item, index ->
                    errors.addAll(validate(item, (Map) schema['items'], root, "${path}[${index}]"))
                }
            }
        } else if (schema['type'] == 'string') {
            if (!(value instanceof String)) return ["${path}: expected string"]
            if (schema['minLength'] != null && value.size() < (schema['minLength'] as int)) {
                errors << "${path}: too short"
            }
            if (schema['pattern'] && !(value ==~ schema['pattern'].toString())) {
                errors << "${path}: pattern mismatch"
            }
        } else if (schema['type'] == 'integer' && !(value instanceof Integer || value instanceof Long)) {
            errors << "${path}: expected integer"
        } else if (schema['type'] == 'boolean' && !(value instanceof Boolean)) {
            errors << "${path}: expected boolean"
        }
        errors
    }
}
