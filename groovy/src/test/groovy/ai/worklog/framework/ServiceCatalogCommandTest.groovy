package ai.worklog.framework

import ai.worklog.framework.catalog.CatalogLoader
import ai.worklog.framework.commands.CatalogCommands
import ai.worklog.framework.commands.ServiceCommands
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.FrameworkPaths
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

class ServiceCatalogCommandTest extends GroovyTestCase {
    private File repository
    private File workspace
    private FrameworkPaths paths

    void setUp() {
        repository = new File('..').canonicalFile
        workspace = File.createTempDir('ai-worklog-', '-service-test')
        new File(workspace, '.ai-worklog/catalog').mkdirs()
        paths = new FrameworkPaths(workspace)
    }

    void tearDown() {
        workspace.deleteDir()
    }

    void testServiceListHumanOutput() {
        Map captured = captureStreams {
            ServiceCommands.run('list', [], repository, paths, ConfigLoader.load(workspace))
        }
        assertEquals(0, captured.code)
        assertTrue(captured.out.contains('Service operators (3):'))
        assertTrue(captured.out.contains('automox: Automox operator'))
        assertTrue(captured.out.contains('jira: Jira and Tempo operator'))
        assertTrue(captured.out.contains('jenkins: Jenkins operator'))
        assertEquals('', captured.err)
    }

    void testServiceListJsonOutput() {
        Map captured = captureStreams {
            ServiceCommands.run('list', ['--json'], repository, paths, ConfigLoader.load(workspace))
        }
        Map report = (Map) new JsonSlurper().parseText(captured.out)
        assertEquals(0, captured.code)
        assertEquals('ready', report.status)
        assertEquals(['automox', 'jenkins', 'jira'], report.items*.id)
    }

    void testServiceHelpUsesCanonicalJenkinsPath() {
        Map captured = captureStreams {
            Main.execute(['service', 'jenkins', 'artifacts', '--help'])
        }
        assertEquals(0, captured.code)
        assertTrue(captured.out.contains(
            'Usage: ai-worklog service jenkins artifacts <controller> <job> <build_selector>'
        ))
        assertEquals('', captured.err)
    }

    void testServiceHelpUsesCanonicalAutomoxPath() {
        Map captured = captureStreams {
            Main.execute(['service', 'automox', 'device-packages', '--help'])
        }
        assertEquals(0, captured.code)
        assertTrue(captured.out.contains(
            'Usage: ai-worklog service automox device-packages <device>'
        ))
        assertEquals('', captured.err)
    }

    void testRemovedJenkinsPathFailsWithMigrationGuidance() {
        Map result = runDispatcher(['--runtime', 'groovy', 'jenkins', 'controllers'])
        assertEquals(1, result.code)
        assertEquals('', result.out)
        assertEquals(
            "Command moved: use 'ai-worklog service jenkins ...'\n",
            result.err
        )
    }

    void testRemovedJenkinsPathIsRejectedBeforePythonDispatch() {
        Map result = runDispatcher(['--runtime', 'python', 'jenkins', 'health', 'primary'])
        assertEquals(1, result.code)
        assertEquals('', result.out)
        assertEquals(
            "Command moved: use 'ai-worklog service jenkins ...'\n",
            result.err
        )
    }

    void testCatalogListHumanOutput() {
        CatalogLoader loader = new CatalogLoader(repository, paths)
        Map captured = captureStreams {
            CatalogCommands.run('list', [], loader)
        }
        assertEquals(0, captured.code)
        assertTrue(captured.out.contains('Catalog systems (3):'))
        assertTrue(captured.out.contains('example-worker: Example Worker Service (application)'))
    }

    void testCatalogListJsonOutput() {
        CatalogLoader loader = new CatalogLoader(repository, paths)
        Map captured = captureStreams {
            CatalogCommands.run('list', ['--json'], loader)
        }
        Map report = (Map) new JsonSlurper().parseText(captured.out)
        assertEquals(0, captured.code)
        assertEquals('ok', report.status)
        assertEquals(3, report.count)
        assertEquals(
            ['example-eks-platform', 'example-metrics-exporter', 'example-worker'],
            report.items*.id
        )
    }

    void testCatalogMissingShowArgumentUsesActionHelp() {
        Map captured = captureStreams {
            CatalogCommands.run('show', [], new CatalogLoader(repository, paths))
        }
        assertEquals(1, captured.code)
        assertEquals('', captured.out)
        assertTrue(captured.err.contains('Missing system'))
        assertTrue(captured.err.contains('Usage: ai-worklog catalog show <system>'))
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

    private Map runDispatcher(List<String> arguments) {
        Process process = new ProcessBuilder(
            [new File(repository, 'bin/ai-worklog').absolutePath] + arguments
        ).directory(repository).start()
        int code = process.waitFor()
        [
            code: code,
            out: process.inputStream.getText('UTF-8'),
            err: process.errorStream.getText('UTF-8')
        ]
    }
}
