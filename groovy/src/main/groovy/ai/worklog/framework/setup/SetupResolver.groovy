package ai.worklog.framework.setup

import ai.worklog.framework.commands.PreflightCommands
import ai.worklog.framework.commands.ToolchainCommands
import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.GlobalConfig
import ai.worklog.framework.core.JsonFiles

class SetupResolver {
    static Map rules() {
        JsonFiles.read(
            new File(FrameworkPaths.resolveFrameworkRoot(), 'shared/setup-rules.json'),
            [
                manifest_version: 1,
                report_version: 1,
                ai_vault_environment: 'AI_WORKLOG_AI_VAULT_ROOT',
                setup_manifest_path: '.ai-worklog/setup.json',
                vault_manifest: 'skills/manifest.json',
                vault_skills_dir: 'skills',
                vault_validate_script: 'scripts/validate-skills.sh',
                vault_skill_file: 'SKILL.md',
                workspace_fallback_subpath: 'repos/ai-vault',
                supported_ides: ['cursor', 'claude', 'antigravity'],
                ides: [:],
                auto_detection: [:]
            ]
        )
    }

    static Map ideMaterialization(String ide) {
        Map profile = ((Map) rules().ides)[ide]
        if (!profile) {
            throw new IllegalArgumentException("Invalid ide: ${ide}")
        }
        [
            destination: profile.destination.toString(),
            materialization: profile.materialization.toString()
        ]
    }

    static List resolveAiVaultRoot(
        File workspace,
        String cliOverride = null,
        Map<String, String> environment = null
    ) {
        Map<String, String> env = environment ?: System.getenv()
        Map configRules = rules()
        String envKey = configRules.ai_vault_environment?.toString() ?: 'AI_WORKLOG_AI_VAULT_ROOT'
        File fallback = new File(workspace, configRules.workspace_fallback_subpath?.toString() ?: 'repos/ai-vault')

        List candidates = [
            ['cli', cliOverride],
            ['env', env.get(envKey)],
            ['global', null],
            ['workspace_fallback', fallback.path]
        ]

        for (List entry : candidates) {
            String source = entry[0]
            String raw = entry[1]
            if (source == 'global') {
                Map config = GlobalConfig.load()
                raw = config.ai_vault_root
                if (!raw) {
                    continue
                }
            } else if (!raw) {
                continue
            }
            try {
                File resolved = GlobalConfig.canonicalWorkspacePath(raw.toString())
                if (resolved.isDirectory()) {
                    return [resolved, source]
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        [null, null]
    }

    static boolean validateRuntime(String runtime) {
        if (!(runtime in GlobalConfig.ALLOWED_RUNTIMES)) {
            return false
        }
        if (runtime == 'python') {
            return PreflightCommands.available('python3') || PreflightCommands.available('python')
        }
        PreflightCommands.available('groovy')
    }

    static List resolveRuntimeSelection(String explicit = null) {
        Map config = GlobalConfig.load()
        if (explicit) {
            if (!(explicit in GlobalConfig.ALLOWED_RUNTIMES)) {
                throw new IllegalArgumentException("Invalid runtime: ${explicit}")
            }
            return [explicit, 'explicit', validateRuntime(explicit)]
        }
        String current = config.runtime?.toString() ?: GlobalConfig.DEFAULT_RUNTIME
        [current, 'global', validateRuntime(current)]
    }

    static File userHome(Map<String, String> environment = null) {
        Map<String, String> env = environment ?: System.getenv()
        String home = env.get('HOME') ?: System.getProperty('user.home')
        new File(home)
    }

    static List<String> detectIdes(File workspace, Map<String, String> environment = null) {
        Map<String, String> env = environment ?: System.getenv()
        Map configRules = rules()
        List<String> detected = []
        File homeDir = userHome(env)
        ((List) configRules.supported_ides).sort().each { ideValue ->
            String ide = ideValue.toString()
            if (!(ide in GlobalConfig.SUPPORTED_IDES)) {
                return
            }
            Map hints = ((Map) configRules.auto_detection)[ide] instanceof Map ?
                (Map) ((Map) configRules.auto_detection)[ide] : [:]
            boolean found = false
            ((List) (hints.commands ?: [])).each { command ->
                if (commandAvailable(command.toString(), env.get('PATH'))) {
                    found = true
                }
            }
            if (!found) {
                ((List) (hints.config_homes ?: [])).each { home ->
                    if (new File(workspace, home.toString()).exists() ||
                        new File(homeDir, home.toString()).exists()) {
                        found = true
                    }
                }
            }
            if (found) {
                detected << ide
            }
        }
        detected
    }

    static List<String> normalizeIdeSelection(
        List<String> requested,
        List<String> existing = [],
        File workspace = null
    ) {
        List<String> mergedExisting = existing ? new ArrayList<>(existing) : []
        if (!requested) {
            if (!workspace) {
                throw new IllegalArgumentException('Workspace path required for auto IDE detection')
            }
            List<String> resolved = detectIdes(workspace)
            if (!resolved) {
                throw new IllegalArgumentException('No IDE detected')
            }
            return (mergedExisting + resolved).unique().sort()
        }
        if (requested.contains('auto')) {
            if (requested.size() > 1) {
                throw new IllegalArgumentException('--ide auto cannot be combined with explicit IDE values')
            }
            if (!workspace) {
                throw new IllegalArgumentException('Workspace path required for auto IDE detection')
            }
            List<String> resolved = detectIdes(workspace)
            if (!resolved) {
                throw new IllegalArgumentException('No IDE detected')
            }
            return (mergedExisting + resolved).unique().sort()
        }
        List<String> normalized = []
        requested.each { ide ->
            if (!(ide in GlobalConfig.SUPPORTED_IDES)) {
                throw new IllegalArgumentException("Invalid ide: ${ide}")
            }
            if (!(ide in normalized)) {
                normalized << ide
            }
        }
        (mergedExisting + normalized).unique().sort()
    }

    static List<String> parseIdeArgs(List<String> ideValues) {
        ideValues ? new ArrayList<>(ideValues) : null
    }

    private static boolean commandAvailable(String command, String pathValue) {
        if (!command) {
            return false
        }
        if (command.contains('/')) {
            return new File(command).canExecute()
        }
        String path = pathValue ?: System.getenv('PATH') ?: ''
        path.split(':').any { dir ->
            new File(dir, command).canExecute()
        }
    }
}
