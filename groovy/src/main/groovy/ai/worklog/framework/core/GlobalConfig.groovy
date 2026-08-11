package ai.worklog.framework.core

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class GlobalConfig {
    static final Map RULES = loadRules()
    static final int CANONICAL_VERSION = RULES.version as int
    static final Set<Integer> SUPPORTED_READ_VERSIONS = [1, CANONICAL_VERSION] as Set
    static final String CONFIG_FILENAME = RULES.config_filename.toString()
    static final String DEFAULT_RUNTIME = RULES.default_runtime.toString()
    static final Set<String> ALLOWED_KEYS = [
        'version', 'runtime', 'ai_vault_root', 'default_workspace', 'workspaces'
    ] as Set
    static final Set<String> ALLOWED_WORKSPACE_KEYS = ['path', 'ides'] as Set
    static final Set<String> ALLOWED_RUNTIMES = ((List) RULES.supported_runtimes)*.toString() as Set
    static final Set<String> SUPPORTED_IDES = ((List) RULES.supported_ides)*.toString() as Set

    static File configHome() {
        String configured = System.getenv(RULES.home_environment.toString())
        if (!configured) {
            configured = System.getProperty('ai.worklog.test.home')
        }
        configured ? new File(expandPath(configured)).canonicalFile :
            new File(System.getProperty('user.home'), '.ai-worklog').canonicalFile
    }

    static File configFile() {
        new File(configHome(), CONFIG_FILENAME)
    }

    static Map defaults() {
        [
            version: CANONICAL_VERSION,
            runtime: DEFAULT_RUNTIME,
            ai_vault_root: null,
            default_workspace: null,
            workspaces: [:]
        ]
    }

    static Map load() {
        File file = configFile()
        if (!file.isFile()) {
            return cloneConfig(defaults())
        }
        Object parsed
        try {
            parsed = new JsonSlurper().parse(file, 'UTF-8')
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Malformed global configuration: ${file}")
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Malformed global configuration: ${file}")
        }
        cloneConfig(validate((Map) parsed))
    }

    static Map save(Map config) {
        Map validated = validate(config)
        ensureHome()
        File file = configFile()
        if (file.isFile()) {
            try {
                Object existing = new JsonSlurper().parse(file, 'UTF-8')
                if (!(existing instanceof Map)) {
                    throw new IllegalArgumentException("Malformed global configuration: ${file}")
                }
                validate((Map) existing)
            } catch (IllegalArgumentException exception) {
                throw exception
            } catch (Exception ignored) {
                throw new IllegalArgumentException("Malformed global configuration: ${file}")
            }
        }
        JsonFiles.write(file, validated)
        setFilePermissions(file)
        cloneConfig(validated)
    }

    static Map validate(Map raw) {
        def unknown = raw.keySet().findAll { !(it.toString() in ALLOWED_KEYS) }
        if (unknown) {
            throw new IllegalArgumentException("Unknown global configuration keys: ${unknown.sort().join(', ')}")
        }
        if (!raw.containsKey('version')) {
            throw new IllegalArgumentException('Missing global configuration field: version')
        }
        int version = raw.version as int
        if (!(version in SUPPORTED_READ_VERSIONS)) {
            throw new IllegalArgumentException("Unsupported global configuration version: ${raw.version}")
        }
        String runtime = raw.containsKey('runtime') ? raw.runtime?.toString() : DEFAULT_RUNTIME
        if (!(runtime in ALLOWED_RUNTIMES)) {
            throw new IllegalArgumentException("Invalid runtime: ${runtime}")
        }
        if (version == 1 && raw.containsKey('ai_vault_root')) {
            throw new IllegalArgumentException('Unknown global configuration keys: ai_vault_root')
        }
        if (version == CANONICAL_VERSION && !raw.containsKey('ai_vault_root')) {
            throw new IllegalArgumentException('Missing global configuration field: ai_vault_root')
        }
        Object aiVaultRootValue = raw.containsKey('ai_vault_root') ? raw.ai_vault_root : null
        String aiVaultRoot = null
        if (aiVaultRootValue != null) {
            if (!(aiVaultRootValue instanceof String) || !aiVaultRootValue.toString().trim()) {
                throw new IllegalArgumentException('Invalid ai_vault_root')
            }
            aiVaultRoot = canonicalWorkspacePath(aiVaultRootValue.toString()).path
        }
        if (raw.containsKey('default_workspace') && raw.default_workspace != null &&
            !(raw.default_workspace instanceof String)) {
            throw new IllegalArgumentException('Invalid default_workspace')
        }
        if (raw.containsKey('default_workspace') && raw.default_workspace != null) {
            validateWorkspaceName(raw.default_workspace.toString())
        }
        if (raw.containsKey('workspaces') && !(raw.workspaces instanceof Map)) {
            throw new IllegalArgumentException('Invalid workspaces')
        }
        Map workspaces = [:]
        ((Map) (raw.workspaces ?: [:])).each { name, entryValue ->
            validateWorkspaceName(name.toString())
            try {
                workspaces[name.toString()] = normalizeWorkspaceEntry(entryValue)
            } catch (IllegalArgumentException exception) {
                if (exception.message == 'Invalid workspace path') {
                    throw new IllegalArgumentException("Invalid workspace path for ${name}")
                }
                throw exception
            }
        }
        String defaultWorkspace = raw.containsKey('default_workspace') ?
            (raw.default_workspace?.toString()) : null
        if (defaultWorkspace && !(defaultWorkspace in workspaces)) {
            throw new IllegalArgumentException("Unknown default workspace: ${defaultWorkspace}")
        }
        [
            version: CANONICAL_VERSION,
            runtime: runtime,
            ai_vault_root: aiVaultRoot,
            default_workspace: defaultWorkspace,
            workspaces: new LinkedHashMap(workspaces.sort { it.key })
        ]
    }

    static List<String> normalizeIdes(Object ides) {
        if (!(ides instanceof List)) {
            throw new IllegalArgumentException('Invalid ides')
        }
        Set<String> seen = [] as Set
        List<String> normalized = []
        ides.each { value ->
            if (!(value instanceof String)) {
                throw new IllegalArgumentException('Invalid ides')
            }
            String ide = value.toString()
            if (!(ide in SUPPORTED_IDES)) {
                throw new IllegalArgumentException("Invalid ide: ${ide}")
            }
            if (!(ide in seen)) {
                seen << ide
                normalized << ide
            }
        }
        normalized.sort()
    }

    static Map normalizeWorkspaceEntry(Object value) {
        if (value instanceof String) {
            if (!value.toString().trim()) {
                throw new IllegalArgumentException('Invalid workspace path')
            }
            return [
                path: canonicalWorkspacePath(value.toString()).path,
                ides: []
            ]
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException('Invalid workspace entry')
        }
        Map entry = (Map) value
        def unknown = entry.keySet().findAll { !(it.toString() in ALLOWED_WORKSPACE_KEYS) }
        if (unknown) {
            throw new IllegalArgumentException("Unknown workspace keys: ${unknown.sort().join(', ')}")
        }
        if (!entry.containsKey('path')) {
            throw new IllegalArgumentException('Invalid workspace path')
        }
        Object pathValue = entry.path
        if (!(pathValue instanceof String) || !pathValue.toString().trim()) {
            throw new IllegalArgumentException('Invalid workspace path')
        }
        [
            path: canonicalWorkspacePath(pathValue.toString()).path,
            ides: normalizeIdes(entry.containsKey('ides') ? entry.ides : [])
        ]
    }

    static String workspaceEntryPath(Object entry) {
        if (entry instanceof Map) {
            Object pathValue = entry.path
            if (pathValue instanceof String && pathValue.toString().trim()) {
                return pathValue.toString()
            }
        }
        if (entry instanceof String && entry.toString().trim()) {
            return canonicalWorkspacePath(entry.toString()).path
        }
        throw new IllegalArgumentException('Invalid workspace path')
    }

    static String validateWorkspaceName(String name) {
        if (!(name ==~ RULES.workspace_name_pattern.toString()) || name in ['.', '..']) {
            throw new IllegalArgumentException("Invalid workspace name: ${name}")
        }
        name
    }

    static String expandPath(String path) {
        if (!path) {
            return path
        }
        if (path == '~') {
            return System.getProperty('user.home')
        }
        if (path.startsWith('~/')) {
            return "${System.getProperty('user.home')}${path.substring(1)}"
        }
        path
    }

    static File canonicalWorkspacePath(String path) {
        new File(expandPath(path)).canonicalFile
    }

    static boolean workspaceAvailable(String path) {
        new File(path).isDirectory()
    }

    static Map workspaceEntry(String name, Map entry, Map config) {
        [
            name: name,
            path: entry.path.toString(),
            ides: ((List) entry.ides)*.toString(),
            available: workspaceAvailable(entry.path.toString()),
            default: config.default_workspace == name
        ]
    }

    static Map addWorkspace(String name, String path, boolean makeDefault) {
        validateWorkspaceName(name)
        File resolved = canonicalWorkspacePath(path)
        if (!resolved.isDirectory()) {
            throw new IllegalArgumentException("Workspace not found: ${path}")
        }
        String canonical = resolved.path
        withConfigLock {
            Map config = load()
            Map workspaces = cloneConfig(config).workspaces
            Map existing = workspaces[name]
            String existingPath = existing?.path?.toString()
            List existingIdes = existing ? ((List) existing.ides)*.toString() : []
            boolean unchanged = existingPath == canonical
            if (existingPath && existingPath != canonical) {
                throw new IllegalArgumentException(
                    "Workspace ${name} is already registered with a different path: ${existingPath}"
                )
            }
            workspaces[name] = [path: canonical, ides: existingIdes]
            config.workspaces = workspaces
            if (makeDefault) {
                config.default_workspace = name
            }
            save(config)
            Map result = [
                operation: 'add',
                status: 'ok',
                name: name,
                path: canonical,
                default: config.default_workspace == name
            ]
            if (unchanged) {
                result.unchanged = true
            }
            result
        }
    }

    static Map removeWorkspace(String name) {
        validateWorkspaceName(name)
        withConfigLock {
            Map config = load()
            Map workspaces = cloneConfig(config).workspaces
            if (!(name in workspaces)) {
                throw new IllegalArgumentException("Workspace not registered: ${name}")
            }
            workspaces.remove(name)
            config.workspaces = workspaces
            if (config.default_workspace == name) {
                config.default_workspace = null
            }
            save(config)
            [
                operation: 'remove',
                status: 'ok',
                name: name
            ]
        }
    }

    static Map setDefaultWorkspace(String name) {
        validateWorkspaceName(name)
        withConfigLock {
            Map config = load()
            if (!(name in config.workspaces)) {
                throw new IllegalArgumentException("Workspace not registered: ${name}")
            }
            config.default_workspace = name
            save(config)
            [
                operation: 'default',
                status: 'ok',
                name: name
            ]
        }
    }

    static Map setAiVaultRoot(String path) {
        withConfigLock {
            Map config = load()
            String canonical = null
            if (path == null) {
                config.ai_vault_root = null
            } else {
                File resolved = canonicalWorkspacePath(path)
                if (!resolved.isDirectory()) {
                    throw new IllegalArgumentException("AI vault root not found: ${path}")
                }
                canonical = resolved.path
                config.ai_vault_root = canonical
            }
            save(config)
            [
                operation: 'ai_vault_root',
                status: 'ok',
                ai_vault_root: canonical
            ]
        }
    }

    static Map setWorkspaceIdes(String name, List<String> ides) {
        validateWorkspaceName(name)
        List<String> normalizedIdes = normalizeIdes(ides)
        withConfigLock {
            Map config = load()
            Map workspaces = cloneConfig(config).workspaces
            if (!(name in workspaces)) {
                throw new IllegalArgumentException("Workspace not registered: ${name}")
            }
            Map entry = new LinkedHashMap((Map) workspaces[name])
            entry.ides = normalizedIdes
            workspaces[name] = entry
            config.workspaces = workspaces
            save(config)
            [
                operation: 'ides',
                status: 'ok',
                name: name,
                ides: normalizedIdes
            ]
        }
    }

    static Map showDefaultWorkspace() {
        Map config = load()
        if (!config.default_workspace) {
            throw new IllegalArgumentException('No default workspace configured')
        }
        [
            operation: 'default',
            status: 'ok',
            name: config.default_workspace
        ]
    }

    static Map listWorkspaces() {
        Map config = load()
        [
            operation: 'list',
            status: 'ok',
            default_workspace: config.default_workspace,
            workspaces: ((Map) config.workspaces).collect { name, entry ->
                workspaceEntry(name.toString(), (Map) entry, config)
            }.sort { it.name }
        ]
    }

    static Map showWorkspace(String name) {
        validateWorkspaceName(name)
        Map config = load()
        if (!(name in config.workspaces)) {
            throw new IllegalArgumentException("Workspace not registered: ${name}")
        }
        Map entry = workspaceEntry(name, (Map) config.workspaces[name], config)
        entry.operation = 'show'
        entry.status = 'ok'
        entry
    }

    static Map showConfiguration() {
        Map config = load()
        [
            operation: 'show',
            status: 'ok',
            version: config.version,
            runtime: config.runtime,
            ai_vault_root: config.ai_vault_root,
            default_workspace: config.default_workspace,
            workspaces: ((Map) config.workspaces).collect { name, entry ->
                workspaceEntry(name.toString(), (Map) entry, config)
            }.sort { it.name }
        ]
    }

    static Map setRuntime(String runtime) {
        if (!(runtime in ALLOWED_RUNTIMES)) {
            throw new IllegalArgumentException("Invalid runtime: ${runtime}")
        }
        withConfigLock {
            Map config = load()
            config.runtime = runtime
            save(config)
            [
                operation: 'runtime',
                status: 'ok',
                runtime: runtime
            ]
        }
    }

    static Map showRuntime() {
        Map config = load()
        [
            operation: 'runtime',
            status: 'ok',
            runtime: config.runtime
        ]
    }

    static Map resolveWorkspaceSelection(
        String explicitPath,
        String explicitName,
        File frameworkRoot,
        Map<String, String> environment = null
    ) {
        Map<String, String> env = environment ?: System.getenv()
        if (explicitPath) {
            File selected = canonicalWorkspacePath(explicitPath)
            if (!selected.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${explicitPath}")
            }
            return [
                path: selected,
                source: 'explicit_path',
                name: null
            ]
        }
        if (explicitName) {
            return resolveRegisteredWorkspace(explicitName, 'workspace_name')
        }
        String envPath = env.get('AI_WORKLOG_WORKSPACE')
        if (envPath) {
            File selected = canonicalWorkspacePath(envPath)
            if (!selected.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${envPath}")
            }
            return [
                path: selected,
                source: 'env_path',
                name: null
            ]
        }
        String envName = env.get('AI_WORKLOG_WORKSPACE_NAME')
        if (envName) {
            return resolveRegisteredWorkspace(envName, 'env_name')
        }
        File discovered = discoverWorkspaceFromCwd(frameworkRoot)
        if (discovered) {
            return [
                path: discovered,
                source: 'cwd_marker',
                name: null
            ]
        }
        Map config = load()
        if (config.default_workspace) {
            Map resolved = resolveRegisteredWorkspace(config.default_workspace.toString(), 'default_workspace')
            return resolved
        }
        throw new IllegalArgumentException(
            'Cannot locate workspace. Use --workspace, -w/--workspace-name, AI_WORKLOG_WORKSPACE, ' +
            'AI_WORKLOG_WORKSPACE_NAME, run from within a workspace directory, or set a default workspace.'
        )
    }

    static Map currentWorkspace(
        String explicitPath,
        String explicitName,
        File frameworkRoot,
        Map<String, String> environment = null
    ) {
        Map resolved = resolveWorkspaceSelection(explicitPath, explicitName, frameworkRoot, environment)
        [
            operation: 'current',
            status: 'ok',
            path: resolved.path.path,
            source: resolved.source,
            name: resolved.name,
            available: true
        ]
    }

    static File resolveWorkspace(
        String explicitPath,
        String explicitName,
        File frameworkRoot,
        Map<String, String> environment = null
    ) {
        resolveWorkspaceSelection(explicitPath, explicitName, frameworkRoot, environment).path as File
    }

    static void printJson(Map value) {
        println JsonOutput.prettyPrint(JsonOutput.toJson(value))
    }

    static void ensureHome() {
        File home = configHome()
        if (!home.exists()) {
            home.mkdirs()
        }
        try {
            Files.setPosixFilePermissions(
                home.toPath(),
                PosixFilePermissions.fromString('rwx------')
            )
        } catch (Exception ignored) {
        }
    }

    private static Map resolveRegisteredWorkspace(String name, String source) {
        validateWorkspaceName(name)
        Map config = load()
        if (!(name in config.workspaces)) {
            throw new IllegalArgumentException("Workspace not registered: ${name}")
        }
        String path = workspaceEntryPath(config.workspaces[name])
        if (!workspaceAvailable(path)) {
            throw new IllegalArgumentException("Registered workspace path is unavailable: ${name} -> ${path}")
        }
        [
            path: new File(path).canonicalFile,
            source: source,
            name: name
        ]
    }

    private static File discoverWorkspaceFromCwd(File frameworkRoot) {
        Map rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/workspace-markers.json'),
            [markers: ['.ai-worklog', 'worklog', 'prompt.log', 'jira'], max_parent_depth: 20]
        )
        File current = new File(System.getProperty('user.dir')).canonicalFile
        int depth = (rules.max_parent_depth ?: 20) as int
        for (int i = 0; i < depth; i++) {
            if (((List) rules.markers).any { new File(current, it.toString()).exists() }) {
                return current
            }
            if (current.parentFile == null || current.parentFile == current) {
                break
            }
            current = current.parentFile
        }
        null
    }

    private static Map cloneConfig(Map config) {
        Map cloned = new LinkedHashMap(config)
        cloned.workspaces = ((Map) config.workspaces).collectEntries { name, entry ->
            Map workspace = (Map) entry
            [
                (name.toString()): [
                    path: workspace.path.toString(),
                    ides: ((List) workspace.ides)*.toString()
                ]
            ]
        }
        cloned
    }

    private static void setFilePermissions(File file) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString('rw-------')
            )
        } catch (Exception ignored) {
        }
    }

    private static Object withConfigLock(Closure operation) {
        ensureHome()
        File lockFile = new File(configHome(), '.config.lock')
        RandomAccessFile handle = new RandomAccessFile(lockFile, 'rw')
        setFilePermissions(lockFile)
        def lock = handle.channel.lock()
        try {
            operation.call()
        } finally {
            lock.release()
            handle.close()
        }
    }

    private static Map loadRules() {
        JsonFiles.read(
            new File(FrameworkPaths.resolveFrameworkRoot(), 'shared/global-config-rules.json'),
            [
                version: 2,
                home_environment: 'AI_WORKLOG_HOME',
                config_filename: 'config.json',
                default_runtime: 'groovy',
                supported_runtimes: ['groovy', 'python'],
                workspace_name_pattern: '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$',
                supported_ides: ['cursor', 'claude', 'antigravity']
            ]
        )
    }
}
