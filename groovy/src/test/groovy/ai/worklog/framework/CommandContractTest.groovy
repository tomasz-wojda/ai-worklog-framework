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

    void testRawContractAndSharedBlocksValidateAgainstSchema() {
        Map schema = (Map) JsonFiles.read(new File(repository, 'schemas/command-contract.schema.json'), [:])
        Map raw = rawContract()
        assertEquals([], validate(raw, schema, schema, '$'))
        Map definitions = (Map) schema.definitions
        ['options': 'option', 'positionals': 'positional'].each { String kind, String definition ->
            ((Map) raw.shared[kind]).each { name, block ->
                assertEquals(
                    [],
                    validate(block, (Map) definitions[definition], schema, "\$.shared.${kind}.${name}")
                )
            }
        }
    }

    void testExpandedContractHasNoSharedReferences() {
        assertFalse(contract.data.containsKey('shared'))
        assertFalse(groovy.json.JsonOutput.toJson(contract.data).contains('"use"'))
        Map json = contract.node(['service', 'jira', 'get-cis']).options.find { it.name == '--json' }
        assertEquals('Emit the machine-readable report', json.description)
        assertEquals('controller', contract.node(['service', 'jenkins', 'health']).positionals[0].name)
    }

    void testUnknownSharedReferenceFails() {
        String message = shouldFail(IllegalStateException) {
            CommandContract.expand([
                shared: [options: [:]],
                commands: [[name: 'x', positionals: [], options: [[use: 'missing']]]]
            ])
        }
        assertTrue(message.contains('missing'))
    }

    void testSharedBlocksAreUsedAndInlineBlocksDoNotRepeat() {
        Map raw = rawContract()
        Map<String, Integer> inline = [:]
        Set<String> used = [] as Set
        Closure walk
        walk = { List nodes ->
            nodes.each { Map node ->
                ['options', 'positionals'].each { String kind ->
                    ((List) (node[kind] ?: [])).each { Map entry ->
                        if (entry.use) {
                            used << "${kind}.${entry.use}".toString()
                        } else {
                            String key = "${kind}:${groovy.json.JsonOutput.toJson(new TreeMap(entry))}"
                            inline[key] = (inline[key] ?: 0) + 1
                        }
                    }
                }
                walk((List) (node.subcommands ?: []))
            }
        }
        walk((List) raw.commands)
        Set<String> defined = raw.shared.collectMany { kind, blocks ->
            ((Map) blocks).keySet().collect { "${kind}.${it}".toString() }
        } as Set
        assertEquals(defined, used)
        assertEquals([], inline.findAll { it.value >= 3 }.keySet().toList())
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
        assertTrue(rendered.contains('artifactory'))
    }

    void testArtifactoryRenderingListsSevenReadOnlyActions() {
        List<Map> actions = contract.children(['service', 'artifactory'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'artifactory'])
        assertEquals(7, actions.size())
        assertEquals(
            ['profiles', 'status', 'auth-test', 'repositories', 'artifacts', 'artifact', 'manifest'],
            actions*.name
        )
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'artifactory', 'artifacts'])
                .contains('<repository> [path]')
        )
        assertEquals(
            ['--json', '--profile', '--max-bytes', '--timeout'],
            contract.node(['service', 'artifactory', 'manifest']).options*.name
        )
    }

    void testJenkinsRenderingListsEighteenActions() {
        List<Map> actions = contract.children(['service', 'jenkins'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jenkins'])
        assertEquals(18, actions.size())
        assertTrue(actions*.name.contains('safe-restart'))
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
    }

    void testJenkinsPluginsNestedContract() {
        List<Map> actions = contract.children(['service', 'jenkins', 'plugins'])
        assertEquals(['list', 'vulnerabilities', 'install'], actions*.name)
        String groupHelp = new UsageRenderer(contract).renderPath(
            ['service', 'jenkins', 'plugins']
        )
        assertTrue(groupHelp.contains('{list|vulnerabilities|install}'))
        Map install = contract.node(['service', 'jenkins', 'plugins', 'install'])
        ParsedArguments installParsed = parser().parse(
            'service jenkins plugins',
            install,
            ['primary', 'git', 'bouncycastle-api', '--apply'],
            rules
        )
        assertEquals(['git', 'bouncycastle-api'], installParsed.variadic('plugins'))
        assertTrue(installParsed.flag('--apply'))
        shouldFail(UsageError) {
            parser().parse('service jenkins plugins', install, ['primary', '../x'], rules)
        }
        String scanHelp = new UsageRenderer(contract).renderPath(
            ['service', 'jenkins', 'plugins', 'vulnerabilities']
        )
        assertTrue(scanHelp.contains('<controller>'))
        assertTrue(scanHelp.contains('--plugin <PLUGIN>'))
        assertTrue(scanHelp.contains('--enrich <SOURCE>'))
        ParsedArguments parsed = parser().parse(
            'service jenkins plugins',
            contract.node(['service', 'jenkins', 'plugins', 'vulnerabilities']),
            ['primary', '--enrich', 'NVD'],
            rules
        )
        assertEquals(['NVD'], parsed.values('--enrich'))
    }

    void testHiddenCredentialRevealOptionsParseButNeverRender() {
        Map action = jenkinsAction('credentials')
        ParsedArguments parsed = parser().parse(
            'service jenkins',
            action,
            [
                'primary',
                '--id',
                'credential-1',
                '--show-secretValue'
            ],
            rules
        )
        assertEquals('credential-1', parsed.value('--id'))
        assertTrue(parsed.flag('--show-secretValue'))
        UsageRenderer usage = new UsageRenderer(contract)
        String human = usage.renderPath(
            ['service', 'jenkins', 'credentials']
        )
        String pathJson = groovy.json.JsonOutput.toJson(
            usage.describePath(['service', 'jenkins', 'credentials'])
        )
        String rootJson = groovy.json.JsonOutput.toJson(usage.describe())
        [human, pathJson, rootJson].each { rendered ->
            assertFalse(rendered.contains('--id'))
            assertFalse(rendered.contains('--show-secretValue'))
            assertFalse(rendered.contains('terminal reveal'))
        }
    }

    void testJiraRenderingListsFifteenActions() {
        List<Map> actions = contract.children(['service', 'jira'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jira'])
        assertEquals(15, actions.size())
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'jira', 'ticket'])
                .contains('<ticket_key>')
        )
    }

    void testJiraAssetsArgumentsValidateAndDefault() {
        Map jiraRules = (Map) JsonFiles.read(new File(repository, 'shared/jira-operator-rules.json'), [:])
        Closure<ParsedArguments> parse = { String name, List<String> args ->
            parser().parse('service jira', contract.node(['service', 'jira', name]), args, jiraRules)
        }
        assertEquals('CI-174110', parse('get-ci', ['CI-174110']).positional('object_key'))
        assertEquals('3', parse('assets-types', ['3']).positional('schema_id'))
        assertEquals(['Name', '=', '"A.A.PROD.SOF"'], parse('assets-search', ['Name', '=', '"A.A.PROD.SOF"']).variadic('query'))
        assertEquals(50, parse('assets-search', ['x']).value('--limit'))
        ParsedArguments cis = parse('get-cis', ['--env', 'PROD'])
        assertEquals('PROD', cis.value('--env'))
        assertEquals(1000, cis.value('--limit'))
        assertEquals('5000', parse('get-cis', ['--limit', '5000']).value('--limit'))
        [
            ['get-ci', ['ci-1']],
            ['assets-object', ['CI-0']],
            ['assets-attributes', ['0']],
            ['assets-types', ['abc']],
            ['assets-search', []],
            ['assets-search', ['x', '--limit', '501']],
            ['get-cis', ['--env', 'prod']],
            ['get-cis', ['--limit', '5001']]
        ].each { List example ->
            shouldFail(UsageError) {
                parse(example[0].toString(), (List<String>) example[1])
            }
        }
    }

    void testAutomoxRenderingListsNineteenActions() {
        List<Map> actions = contract.children(['service', 'automox'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'automox'])
        assertEquals(19, actions.size())
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'automox', 'policy-run'])
                .contains('<policy_id>')
        )
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'automox', 'device-packages'])
                .contains('<device>')
        )
    }

    void testAutomoxDevicePackagesPageOption() {
        Map action = contract.node(['service', 'automox', 'device-packages'])
        Map automoxRules = (Map) JsonFiles.read(new File(repository, 'shared/automox-operator-rules.json'), [:])
        ParsedArguments parsed = parser().parse('service automox', action, ['host', '--page', '2'], automoxRules)
        assertEquals('2', parsed.value('--page').toString())
        shouldFail(UsageError) {
            parser().parse('service automox', action, ['host', '--page', '0'], automoxRules)
        }
    }

    void testNewRelicRenderingListsTwentySevenActions() {
        List<Map> actions = contract.children(['service', 'newrelic'])
        String rendered = new UsageRenderer(contract).renderPath(['service', 'newrelic'])
        assertEquals(27, actions.size())
        actions*.name.each { assertTrue(rendered.contains(it.toString())) }
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'newrelic', 'alert-condition-create'])
                .contains('<policy_id> <definition_file>')
        )
        assertTrue(
            new UsageRenderer(contract)
                .renderPath(['service', 'newrelic', 'dashboard-export'])
                .contains('dry-run unless --apply')
        )
    }

    void testNewRelicDefaultsResolveFromOperatorRules() {
        Map newrelicRules = (Map) JsonFiles.read(
            new File(repository, 'shared/newrelic-operator-rules.json'),
            [:]
        )
        assertEquals(
            newrelicRules.limits.applications_default.toString(),
            parser().parse('newrelic', newRelicAction('applications'), [], newrelicRules)
                .value('--limit').toString()
        )
        assertEquals(
            newrelicRules.limits.errors_hours_default.toString(),
            parser().parse('newrelic', newRelicAction('errors'), ['12345'], newrelicRules)
                .value('--hours').toString()
        )
        assertEquals(
            newrelicRules.limits.nrql_rows_default.toString(),
            parser().parse('newrelic', newRelicAction('nrql'), ['SELECT 1'], newrelicRules)
                .value('--limit').toString()
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

    void testJobExportContractAndHelp() {
        Map action = jenkinsAction('job-export')
        assertEquals(['controller', 'job'], action.positionals*.name)
        assertEquals(['--json', '--apply', '--force', '--cwd'], action.options*.name)
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jenkins', 'job-export'])
        assertTrue(rendered.contains('<controller> <job>'))
        assertTrue(rendered.contains('dry-run unless --apply'))
        Map schema = (Map) JsonFiles.read(
            new File(repository, 'schemas/command-contract.schema.json'),
            [:]
        )
        assertEquals(
            [],
            validate(
                new UsageRenderer(contract).describePath(['service', 'jenkins', 'job-export']),
                schema,
                schema,
                '$'
            )
        )
    }

    void testRunScriptContractAndHelp() {
        Map action = jenkinsAction('run-script')
        assertEquals(['controller', 'script_file'], action.positionals*.name)
        assertEquals([true, false], action.positionals*.required)
        assertEquals(['--json', '--apply', '--script'], action.options*.name)
        String rendered = new UsageRenderer(contract).renderPath(['service', 'jenkins', 'run-script'])
        assertTrue(rendered.contains('dry-run unless --apply'))
        assertTrue(rendered.contains('SCRIPT_FILE | -'))
        Map schema = (Map) JsonFiles.read(
            new File(repository, 'schemas/command-contract.schema.json'),
            [:]
        )
        assertEquals(
            [],
            validate(
                new UsageRenderer(contract).describePath(['service', 'jenkins', 'run-script']),
                schema,
                schema,
                '$'
            )
        )
    }

    void testBareDashIsPositional() {
        Map action = jenkinsAction('run-script')
        assertEquals('-', parser().parse('jenkins', action, ['primary', '-'], rules).positional('script_file'))
        String message = shouldFail(UsageError) {
            parser().parse('jenkins', action, ['primary', '--unknown'], rules)
        }
        assertTrue(message.contains('Unknown option'))
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

    private Map rawContract() {
        (Map) JsonFiles.read(new File(repository, 'shared/command-contract.json'), [:])
    }

    private ArgumentParser parser() {
        new ArgumentParser(contract)
    }

    private Map jenkinsAction(String name) {
        contract.node(['service', 'jenkins', name])
    }

    private Map newRelicAction(String name) {
        contract.node(['service', 'newrelic', name])
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
