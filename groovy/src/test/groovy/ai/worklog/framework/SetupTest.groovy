package ai.worklog.framework

import ai.worklog.framework.adapters.JenkinsAdapter
import ai.worklog.framework.adapters.ReadOnlyHttp
import ai.worklog.framework.adapters.ReadOnlyProcess
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.core.JsonFiles
import ai.worklog.framework.setup.SetupManifest
import ai.worklog.framework.setup.SetupMaterialize
import ai.worklog.framework.setup.SetupPlanner
import ai.worklog.framework.setup.SetupResolver
import ai.worklog.framework.setup.SetupVault
import groovy.json.JsonOutput
import groovy.test.GroovyTestCase

class SetupTest extends GroovyTestCase {
    private File repository
    private File home
    private File tempRoot
    private String originalTestHome

    private static final Map VAULT_MANIFEST = [
        version: 1,
        skills: [
            [
                name: 'developer-protocol',
                dir: 'developer-protocol',
                required: true,
                ides: ['cursor', 'claude', 'antigravity']
            ],
            [
                name: 'devops-daily-protocol',
                dir: 'devops-daily-protocol',
                required: true,
                ides: ['cursor']
            ]
        ]
    ]

    void setUp() {
        repository = new File('..').canonicalFile
        home = File.createTempDir('ai-worklog-setup-home-', '-test')
        tempRoot = File.createTempDir('ai-worklog-setup-root-', '-test')
        originalTestHome = System.getProperty('ai.worklog.test.home')
        System.setProperty('ai.worklog.test.home', home.path)
    }

    void tearDown() {
        if (originalTestHome) {
            System.setProperty('ai.worklog.test.home', originalTestHome)
        } else {
            System.clearProperty('ai.worklog.test.home')
        }
        tempRoot.deleteDir()
        home.deleteDir()
    }

    void testAiVaultResolverPrecedence() {
        File workspace = makeWorkspace()
        File cliVault = makeVault(new File(tempRoot, 'cli'))
        File envVault = makeVault(new File(tempRoot, 'env'))
        File globalVault = makeVault(new File(tempRoot, 'global'))
        File fallbackVault = makeVaultAt(new File(workspace, 'repos/ai-vault'))

        List cliResolution = SetupResolver.resolveAiVaultRoot(workspace, cliVault.path)
        assertEquals(cliVault.canonicalFile, cliResolution[0])
        assertEquals('cli', cliResolution[1])

        List envResolution = SetupResolver.resolveAiVaultRoot(
            workspace,
            null,
            [(SetupResolver.rules().ai_vault_environment.toString()): envVault.path]
        )
        assertEquals(envVault.canonicalFile, envResolution[0])
        assertEquals('env', envResolution[1])

        GlobalConfig.setAiVaultRoot(globalVault.path)
        List globalResolution = SetupResolver.resolveAiVaultRoot(
            workspace,
            null,
            [(SetupResolver.rules().ai_vault_environment.toString()): '']
        )
        assertEquals(globalVault.canonicalFile, globalResolution[0])
        assertEquals('global', globalResolution[1])

        GlobalConfig.setAiVaultRoot(null)
        List fallbackResolution = SetupResolver.resolveAiVaultRoot(
            workspace,
            null,
            [(SetupResolver.rules().ai_vault_environment.toString()): '']
        )
        assertEquals(fallbackVault.canonicalFile, fallbackResolution[0])
        assertEquals('workspace_fallback', fallbackResolution[1])
    }

    void testValidateVault() {
        File vault = makeVault(tempRoot)
        List validation = SetupVault.validateVaultRoot(vault)
        assertTrue(validation[0])
        assertEquals('valid', validation[1])
        assertEquals(2, ((Map) validation[2]).skills.size())
    }

    void testDetectIdesFromWorkspaceMarker() {
        File workspace = makeWorkspace()
        new File(workspace, '.cursor').mkdir()
        assertTrue('cursor' in SetupResolver.detectIdes(workspace, [PATH: '/usr/bin:/bin']))
    }

    void testNormalizeAutoMergesExisting() {
        File workspace = makeWorkspace()
        new File(workspace, '.claude').mkdir()
        List<String> ides = SetupResolver.normalizeIdeSelection(['auto'], ['cursor'], workspace)
        assertEquals(['claude', 'cursor'], ides)
    }

    void testAutoCannotMixWithExplicit() {
        File workspace = makeWorkspace()
        shouldFail(IllegalArgumentException) {
            SetupResolver.normalizeIdeSelection(['auto', 'cursor'], [], workspace)
        }
    }

    void testTreeChecksumChangesWhenContentChanges() {
        File target = new File(tempRoot, 'skill')
        target.mkdir()
        new File(target, 'SKILL.md').setText('a', 'UTF-8')
        String first = SetupManifest.treeChecksum(target)
        new File(target, 'SKILL.md').setText('b', 'UTF-8')
        assertFalse(first == SetupManifest.treeChecksum(target))
    }

    void testSymlinkConflictAndAdopt() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        File source = new File(vault, 'skills/developer-protocol')
        File destination = new File(workspace, '.cursor/skills/developer-protocol')
        destination.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(destination.toPath(), source.toPath())
        List inspection = SetupMaterialize.inspectDestination(destination, source, 'symlink', null, true)
        assertEquals('adopt', inspection[0])

        File foreign = new File(workspace, '.cursor/skills/devops-daily-protocol')
        foreign.mkdir()
        List conflict = SetupMaterialize.inspectDestination(
            foreign,
            new File(vault, 'skills/devops-daily-protocol'),
            'symlink',
            null,
            false
        )
        assertEquals('conflict', conflict[0])
    }

    void testCopyModifiedConflict() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        File source = new File(vault, 'skills/developer-protocol')
        File destination = new File(workspace, '.agents/skills/developer-protocol')
        destination.parentFile.mkdirs()
        copyTree(source, destination)
        Map entry = [
            materialization: 'copy',
            applied_checksum: SetupManifest.treeChecksum(destination),
            source: source.path
        ]
        new File(destination, 'SKILL.md').setText('changed', 'UTF-8')
        List inspection = SetupMaterialize.inspectDestination(destination, source, 'copy', entry, false)
        assertEquals('conflict', inspection[0])
        assertEquals('modified copy', inspection[1])
    }

    void testManifestAtomicWrite() {
        File workspace = makeWorkspace()
        SetupManifest.saveManifest(workspace, [
            version: 1,
            workspace_name: 'work',
            ai_vault_root: tempRoot.path,
            ides: ['cursor'],
            skills: [],
            synced_at: '2026-01-01T00:00:00+00:00'
        ])
        Map loaded = SetupManifest.loadManifest(workspace)
        assertEquals('work', loaded.workspace_name)
        assertTrue(SetupManifest.manifestPath(workspace).isFile())
    }

    void testValidateManifestRejectsUnknownTopLevelFields() {
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 1,
                workspace_name: 'work',
                ai_vault_root: tempRoot.path,
                ides: ['cursor'],
                skills: [],
                synced_at: '2026-01-01T00:00:00+00:00',
                extra: true
            ])
        }
    }

    void testValidateManifestRejectsWrongVersion() {
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 2,
                workspace_name: 'work',
                ai_vault_root: tempRoot.path,
                ides: ['cursor'],
                skills: [],
                synced_at: '2026-01-01T00:00:00+00:00'
            ])
        }
    }

    void testValidateManifestRejectsInvalidWorkspaceName() {
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 1,
                workspace_name: '-bad',
                ai_vault_root: tempRoot.path,
                ides: ['cursor'],
                skills: [],
                synced_at: '2026-01-01T00:00:00+00:00'
            ])
        }
    }

    void testValidateManifestRejectsDuplicateIdes() {
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 1,
                workspace_name: 'work',
                ai_vault_root: tempRoot.path,
                ides: ['cursor', 'cursor'],
                skills: [],
                synced_at: '2026-01-01T00:00:00+00:00'
            ])
        }
    }

    void testValidateManifestRejectsDuplicateSkillKeys() {
        Map skill = [
            name: 'developer-protocol',
            ide: 'cursor',
            source: '/vault/skills/developer-protocol',
            destination: '/ws/.cursor/skills/developer-protocol',
            materialization: 'symlink',
            source_checksum: 'abc123',
            created_at: '2026-01-01T00:00:00+00:00',
            synced_at: '2026-01-01T00:00:00+00:00'
        ]
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 1,
                workspace_name: 'work',
                ai_vault_root: tempRoot.path,
                ides: ['cursor'],
                skills: [skill, new LinkedHashMap(skill)],
                synced_at: '2026-01-01T00:00:00+00:00'
            ])
        }
    }

    void testValidateManifestRejectsEmptyAppliedChecksum() {
        shouldFail(IllegalArgumentException) {
            SetupManifest.validateManifest([
                version: 1,
                workspace_name: 'work',
                ai_vault_root: tempRoot.path,
                ides: ['cursor'],
                skills: [[
                    name: 'developer-protocol',
                    ide: 'cursor',
                    source: '/vault/skills/developer-protocol',
                    destination: '/ws/.cursor/skills/developer-protocol',
                    materialization: 'symlink',
                    source_checksum: 'abc123',
                    applied_checksum: '',
                    created_at: '2026-01-01T00:00:00+00:00',
                    synced_at: '2026-01-01T00:00:00+00:00'
                ]],
                synced_at: '2026-01-01T00:00:00+00:00'
            ])
        }
    }

    void testLoadManifestRejectsMalformedJson() {
        File workspace = makeWorkspace()
        File path = SetupManifest.manifestPath(workspace)
        path.parentFile.mkdirs()
        path.setText('{bad', 'UTF-8')
        shouldFail(IllegalArgumentException) {
            SetupManifest.loadManifest(workspace)
        }
    }

    void testPlanInitCreatesSkillActions() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        Map plan = SetupPlanner.planSetupInit(
            workspace,
            vault,
            VAULT_MANIFEST,
            ['cursor'],
            false,
            repository
        )
        assertFalse(((List) plan.skill_actions).isEmpty())
        assertFalse(((List) plan.workspace_actions).isEmpty())
    }

    void testApplyInitWritesManifestAndSymlinks() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        Map plan = SetupPlanner.planSetupInit(
            workspace,
            vault,
            VAULT_MANIFEST,
            ['cursor'],
            true,
            repository
        )
        SetupPlanner.applyInitOrRepairPlan(workspace, 'work', vault, ['cursor'], plan)
        Map manifest = SetupManifest.loadManifest(workspace)
        assertNotNull(manifest)
        assertEquals('work', manifest.workspace_name)
        File link = new File(workspace, '.cursor/skills/developer-protocol')
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertEquals(
            new File(vault, 'skills/developer-protocol').canonicalFile,
            link.canonicalFile
        )
    }

    void testInitDryRunThenApply() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        String dryRun = captureOutput {
            assertEquals(0, Main.execute([
                'setup', 'init', 'work', workspace.path,
                '--ide', 'cursor',
                '--ai-vault', vault.path
            ]))
        }
        assertTrue(dryRun.contains('Dry run only'))

        captureOutput {
            assertEquals(0, Main.execute([
                'setup', 'init', 'work', workspace.path,
                '--ide', 'cursor',
                '--ai-vault', vault.path,
                '--default',
                '--apply'
            ]))
        }
        Map config = GlobalConfig.load()
        assertEquals(['cursor'], config.workspaces.work.ides)
        assertEquals(vault.canonicalFile.path, config.ai_vault_root)
        assertNotNull(SetupManifest.loadManifest(workspace))
    }

    void testInitMergesIdes() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        GlobalConfig.addWorkspace('work', workspace.path, false)
        GlobalConfig.setWorkspaceIdes('work', ['claude'])
        captureOutput {
            assertEquals(0, Main.execute([
                'setup', 'init', 'work', workspace.path,
                '--ide', 'cursor',
                '--ai-vault', vault.path,
                '--json',
                '--apply'
            ]))
        }
        assertEquals(['claude', 'cursor'], GlobalConfig.load().workspaces.work.ides)
    }

    void testRevertRemovesCursorOnly() {
        File vault = makeVault(tempRoot)
        File workspace = makeWorkspace()
        captureOutput {
            Main.execute([
                'setup', 'init', 'work', workspace.path,
                '--ide', 'cursor',
                '--ide', 'claude',
                '--ai-vault', vault.path,
                '--apply'
            ])
        }
        GlobalConfig.addWorkspace('work', workspace.path, true)
        GlobalConfig.setWorkspaceIdes('work', ['cursor', 'claude'])

        captureOutput {
            Main.execute([
                '--workspace', workspace.path,
                'setup', 'revert',
                '--ide', 'cursor',
                '--apply'
            ])
        }
        assertFalse(new File(workspace, '.cursor/skills/developer-protocol').exists())
        assertEquals(['claude'], GlobalConfig.load().workspaces.work.ides)
    }

    void testGlobalAiVaultUsedForSyntaxCheckFallback() {
        File workspace = makeWorkspace()
        File vault = makeVault(tempRoot)
        GlobalConfig.setAiVaultRoot(vault.path)
        File script = new File(vault, 'skills/jenkins-pipeline-architect/scripts/syntax_check.sh')
        script.parentFile.mkdirs()
        script.setText('#!/bin/sh\nexit 0\n', 'UTF-8')
        script.setExecutable(true)

        JenkinsAdapter adapter = new JenkinsAdapter(
            new FrameworkPaths(workspace),
            new ReadOnlyHttp(),
            [:],
            repository,
            [:],
            new ReadOnlyProcess()
        )
        File resolved = resolveSyntaxCheckScript(adapter)
        assertEquals(script.canonicalFile, resolved.canonicalFile)
    }

    void testWorkspaceSyntaxCheckOverridePreserved() {
        File workspace = makeWorkspace()
        File explicit = new File(workspace, 'explicit.sh')
        explicit.setText('#!/bin/sh\nexit 0\n', 'UTF-8')
        explicit.setExecutable(true)
        JsonFiles.write(new File(workspace, '.ai-worklog/config.json'), [
            adapters: [jenkins: [syntax_check_script: explicit.absolutePath]]
        ])
        JenkinsAdapter adapter = new JenkinsAdapter(
            new FrameworkPaths(workspace),
            new ReadOnlyHttp(),
            [:],
            repository,
            ConfigLoader.load(workspace),
            new ReadOnlyProcess()
        )
        File resolved = resolveSyntaxCheckScript(adapter)
        assertEquals(explicit.canonicalFile, resolved.canonicalFile)
    }

    private static File resolveSyntaxCheckScript(JenkinsAdapter adapter) {
        def method = JenkinsAdapter.class.getDeclaredMethod('resolveSyntaxCheckScript')
        method.accessible = true
        method.invoke(adapter) as File
    }

    private File makeVault(File root) {
        makeVaultAt(new File(root, 'ai-vault'))
    }

    private File makeVaultAt(File vault) {
        vault.mkdirs()
        File scripts = new File(vault, 'scripts')
        scripts.mkdir()
        File script = new File(scripts, 'validate-skills.sh')
        script.setText('#!/bin/sh\n', 'UTF-8')
        script.setExecutable(true)
        File skills = new File(vault, 'skills')
        skills.mkdir()
        new File(skills, 'manifest.json').setText(JsonOutput.toJson(VAULT_MANIFEST), 'UTF-8')
        VAULT_MANIFEST.skills.each { Map entry ->
            File skillDir = new File(skills, entry.dir.toString())
            skillDir.mkdir()
            new File(skillDir, 'SKILL.md').setText('# Skill\n', 'UTF-8')
        }
        vault
    }

    private File makeWorkspace() {
        File workspace = new File(tempRoot, "workspace-${UUID.randomUUID()}")
        workspace.mkdir()
        new File(workspace, 'worklog').mkdir()
        new File(workspace, 'prompt.log').createNewFile()
        workspace
    }

    private static void copyTree(File source, File target) {
        target.mkdirs()
        source.eachFileRecurse { File file ->
            File dest = new File(target, source.toPath().relativize(file.toPath()).toString())
            if (file.isDirectory()) {
                dest.mkdirs()
            } else {
                dest.parentFile.mkdirs()
                dest.bytes = file.bytes
            }
        }
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
