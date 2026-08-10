package ai.worklog.framework

import ai.worklog.framework.core.GlobalConfig
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import groovy.test.GroovyTestCase

class GlobalConfigTest extends GroovyTestCase {
    File repository
    File home
    File work
    File testWs
    String originalTestHome

    void setUp() {
        repository = new File('..').canonicalFile
        home = File.createTempDir('ai-worklog-home-', '-test')
        work = File.createTempDir('ai-worklog-work-', '-test')
        testWs = File.createTempDir('ai-worklog-test-', '-test')
        originalTestHome = System.getProperty('ai.worklog.test.home')
        System.setProperty('ai.worklog.test.home', home.path)
    }

    void tearDown() {
        if (originalTestHome) {
            System.setProperty('ai.worklog.test.home', originalTestHome)
        } else {
            System.clearProperty('ai.worklog.test.home')
        }
        work.deleteDir()
        testWs.deleteDir()
        home.deleteDir()
    }

    void testDefaultsWhenMissingConfig() {
        Map config = GlobalConfig.load()
        assertEquals(1, config.version)
        assertEquals('groovy', config.runtime)
        assertNull(config.default_workspace)
        assertEquals([:], config.workspaces)
    }

    void testAddListShowDefaultAndRemove() {
        Map added = GlobalConfig.addWorkspace('work', work.path, true)
        assertEquals('ok', added.status)
        assertEquals('work', added.name)
        assertEquals(work.canonicalFile.path, added.path)
        assertTrue(added.default)

        Map listed = GlobalConfig.listWorkspaces()
        assertEquals('work', listed.default_workspace)
        assertEquals(1, listed.workspaces.size())
        assertTrue(listed.workspaces[0].available)

        GlobalConfig.addWorkspace('test', testWs.path, false)
        Map shown = GlobalConfig.showWorkspace('test')
        assertEquals('test', shown.name)
        assertFalse(shown.default)
        assertTrue(shown.available)

        Map defaulted = GlobalConfig.setDefaultWorkspace('test')
        assertEquals('test', defaulted.name)
        assertEquals('test', GlobalConfig.showDefaultWorkspace().name)

        File originalDir = new File(System.getProperty('user.dir')).canonicalFile
        File outside = File.createTempDir('ai-worklog-outside-', '-test')
        try {
            System.setProperty('user.dir', outside.path)
            Map current = GlobalConfig.currentWorkspace(null, null, repository, [:])
            assertEquals('default_workspace', current.source)
            assertEquals('test', current.name)
            assertEquals(testWs.canonicalFile.path, current.path)
        } finally {
            System.setProperty('user.dir', originalDir.path)
            outside.deleteDir()
        }

        Map removed = GlobalConfig.removeWorkspace('work')
        assertEquals('work', removed.name)
        Map config = GlobalConfig.load()
        assertFalse(config.workspaces.containsKey('work'))
        assertEquals('test', config.default_workspace)
        assertTrue(work.isDirectory())
    }

    void testRemoveClearsDefaultWhenRemovingDefaultWorkspace() {
        GlobalConfig.addWorkspace('work', work.path, true)
        GlobalConfig.removeWorkspace('work')
        assertNull(GlobalConfig.load().default_workspace)
    }

    void testDuplicateAddIsIdempotentAndConflictIsRejected() {
        GlobalConfig.addWorkspace('work', work.path, true)
        Map unchanged = GlobalConfig.addWorkspace('work', work.path, false)
        assertTrue(unchanged.unchanged)

        File other = File.createTempDir('ai-worklog-other-', '-test')
        try {
            shouldFail(IllegalArgumentException) {
                GlobalConfig.addWorkspace('work', other.path, false)
            }
        } finally {
            other.deleteDir()
        }
    }

    void testInvalidWorkspaceNameAndMissingPathAreRejected() {
        shouldFail(IllegalArgumentException) {
            GlobalConfig.addWorkspace('../bad', work.path, false)
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.addWorkspace('work', new File(home, 'missing').path, false)
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.removeWorkspace('missing')
        }
    }

    void testMalformedConfigIsRejectedAndNotOverwritten() {
        GlobalConfig.ensureHome()
        GlobalConfig.configFile().setText('{ invalid json', 'UTF-8')
        shouldFail(IllegalArgumentException) {
            GlobalConfig.addWorkspace('work', work.path, false)
        }
        assertEquals('{ invalid json', GlobalConfig.configFile().getText('UTF-8').trim())
    }

    void testUnknownKeysAndUnsupportedVersionAreRejected() {
        GlobalConfig.save(GlobalConfig.defaults())
        shouldFail(IllegalArgumentException) {
            GlobalConfig.save(GlobalConfig.defaults() + [extra: true])
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.save(GlobalConfig.defaults() + [version: 2])
        }
    }

    void testPermissionsAreAppliedBestEffort() {
        GlobalConfig.addWorkspace('work', work.path, true)
        Set<PosixFilePermission> homePerms = Files.getPosixFilePermissions(home.toPath())
        Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(GlobalConfig.configFile().toPath())
        assertEquals(
            PosixFilePermissions.fromString('rwx------'),
            homePerms
        )
        assertEquals(
            PosixFilePermissions.fromString('rw-------'),
            filePerms
        )
    }

    void testTildeExpansion() {
        File tildeDir = new File(System.getProperty('user.home'), "ai-worklog-tilde-${UUID.randomUUID()}")
        tildeDir.mkdirs()
        try {
            GlobalConfig.addWorkspace('work', "~/${tildeDir.name}", false)
            Map config = GlobalConfig.load()
            assertFalse(config.workspaces.work.contains('~'))
            assertEquals(tildeDir.canonicalFile.path, config.workspaces.work)
        } finally {
            tildeDir.deleteDir()
        }
    }

    void testRuntimePersistence() {
        Map runtime = GlobalConfig.setRuntime('python')
        assertEquals('python', runtime.runtime)
        assertEquals('python', GlobalConfig.showRuntime().runtime)
        assertEquals('python', GlobalConfig.showConfiguration().runtime)
    }

    void testResolutionPrecedence() {
        GlobalConfig.addWorkspace('work', work.path, true)
        GlobalConfig.addWorkspace('test', testWs.path, false)
        new File(work, 'jira').mkdir()

        assertEquals(
            work.canonicalFile,
            GlobalConfig.resolveWorkspace(work.path, null, repository)
        )
        assertEquals(
            testWs.canonicalFile,
            GlobalConfig.resolveWorkspace(null, 'test', repository)
        )

        assertEquals(
            testWs.canonicalFile,
            GlobalConfig.resolveWorkspace(null, null, repository, [
                AI_WORKLOG_WORKSPACE: testWs.path
            ])
        )

        assertEquals(
            testWs.canonicalFile,
            GlobalConfig.resolveWorkspace(null, null, repository, [
                AI_WORKLOG_WORKSPACE_NAME: 'test'
            ])
        )

        File originalDir = new File(System.getProperty('user.dir')).canonicalFile
        try {
            System.setProperty('user.dir', work.path)
            assertEquals(
                work.canonicalFile,
                GlobalConfig.resolveWorkspace(null, null, repository, [:])
            )
        } finally {
            System.setProperty('user.dir', originalDir.path)
        }

        File outside = File.createTempDir('ai-worklog-outside-', '-test')
        try {
            System.setProperty('user.dir', outside.path)
            assertEquals(
                work.canonicalFile,
                GlobalConfig.resolveWorkspace(null, null, repository, [:])
            )
        } finally {
            System.setProperty('user.dir', originalDir.path)
            outside.deleteDir()
        }
    }

    void testExplicitInvalidSelectorsNeverFallThrough() {
        GlobalConfig.addWorkspace('work', work.path, true)
        shouldFail(IllegalArgumentException) {
            GlobalConfig.resolveWorkspace(new File(home, 'missing').path, null, repository)
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.resolveWorkspace(null, 'missing', repository)
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.resolveWorkspace(null, null, repository, [
                AI_WORKLOG_WORKSPACE: new File(home, 'missing').path
            ])
        }
        shouldFail(IllegalArgumentException) {
            GlobalConfig.resolveWorkspace(null, null, repository, [
                AI_WORKLOG_WORKSPACE_NAME: 'missing'
            ])
        }
    }

    void testStaleRegisteredPathIsRejected() {
        GlobalConfig.addWorkspace('work', work.path, true)
        work.deleteDir()
        shouldFail(IllegalArgumentException) {
            GlobalConfig.resolveWorkspace(null, 'work', repository)
        }
        Map shown = GlobalConfig.showWorkspace('work')
        assertFalse(shown.available)
    }

    void testConfigAndWorkspaceCommandsViaMain() {
        Map added = runJson(['workspace', 'add', 'work', work.path, '--default', '--json'])
        assertEquals('ok', added.status)
        assertEquals('work', added.name)

        Map listed = runJson(['workspace', 'list', '--json'])
        assertEquals(1, listed.workspaces.size())

        Map config = runJson(['config', 'show', '--json'])
        assertEquals('groovy', config.runtime)
        assertEquals('work', config.default_workspace)

        Map runtime = runJson(['config', 'runtime', 'python', '--json'])
        assertEquals('python', runtime.runtime)

        Map current = runJson(['-w', 'work', 'workspace', 'current', '--json'])
        assertEquals('workspace_name', current.source)
        assertEquals('work', current.name)

        Map removed = runJson(['workspace', 'remove', 'work', '--json'])
        assertEquals('work', removed.name)
        assertTrue(GlobalConfig.load().workspaces.isEmpty())
    }

    void testWorkspaceInitCompatibility() {
        new File(work, 'jira').mkdir()
        String output = captureOutput {
            assertEquals(0, Main.execute(['workspace', 'init', work.path]))
        }
        assertTrue(output.contains('Dry run only'))
    }

    void testGlobalOptionsAcceptedAnywhere() {
        GlobalConfig.addWorkspace('work', work.path, true)
        Map current = runJson(['workspace', 'current', '-w', 'work', '--json'])
        assertEquals('workspace_name', current.source)
        assertEquals('work', current.name)
    }

    private Map runJson(List<String> args) {
        String output = captureOutput {
            assertEquals(0, Main.execute(args))
        }
        new JsonSlurper().parseText(output)
    }

    private String captureOutput(Closure runnable) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PrintStream original = System.out
        System.out = new PrintStream(buffer)
        try {
            runnable.call()
        } finally {
            System.out = original
        }
        buffer.toString('UTF-8').trim()
    }
}
