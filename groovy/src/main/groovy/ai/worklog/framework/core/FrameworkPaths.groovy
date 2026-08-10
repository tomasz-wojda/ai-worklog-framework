package ai.worklog.framework.core

class FrameworkPaths {
    final File root
    final File worklog
    final File worklogDone
    final File stateDir
    final File configDir
    final File catalogDir
    final File interfaceDir
    final File promptLog

    FrameworkPaths(File root) {
        this.root = root.canonicalFile
        this.worklog = new File(this.root, 'worklog')
        this.worklogDone = new File(worklog, 'done')
        this.stateDir = new File(this.root, '.ai-worklog/state')
        this.configDir = new File(this.root, '.ai-worklog')
        this.catalogDir = new File(this.root, '.ai-worklog/catalog')
        this.interfaceDir = new File(worklog, 'interface')
        this.promptLog = new File(this.root, 'prompt.log')
    }

    File serviceDir(String service) {
        validateComponent(service, 'service')
        File interfacePath = new File(interfaceDir, service)
        if (interfacePath.exists()) {
            return interfacePath
        }
        File rootPath = new File(root, service)
        rootPath.exists() ? rootPath : interfacePath
    }

    File ticketStateFile(String key) {
        validateComponent(key, 'ticket key')
        new File(stateDir, "${key}.json")
    }

    static String validateComponent(String value, String label) {
        if (!(value ==~ /[A-Za-z0-9][A-Za-z0-9._-]{0,127}/) ||
            value in ['.', '..']) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }
        value
    }

    static File resolveWorkspace(String explicit, File frameworkRoot) {
        if (explicit) {
            File selected = new File(explicit).canonicalFile
            if (!selected.isDirectory()) {
                throw new IllegalArgumentException("Workspace not found: ${explicit}")
            }
            return selected
        }
        String configured = System.getenv('AI_WORKLOG_WORKSPACE')
        if (configured) {
            File selected = new File(configured).canonicalFile
            if (selected.isDirectory()) {
                return selected
            }
        }
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
        throw new IllegalArgumentException(
            'Cannot locate workspace. Use --workspace or AI_WORKLOG_WORKSPACE.'
        )
    }

    static File resolveFrameworkRoot() {
        String configured = System.getenv('AI_WORKLOG_FRAMEWORK_ROOT')
        if (configured) {
            return new File(configured).canonicalFile
        }
        File current = new File(System.getProperty('user.dir')).canonicalFile
        for (int i = 0; i < 20; i++) {
            if (new File(current, 'shared').isDirectory() &&
                new File(current, 'groovy').isDirectory() &&
                new File(current, 'bin/ai-worklog').isFile()) {
                return current
            }
            if (current.parentFile == null || current.parentFile == current) {
                break
            }
            current = current.parentFile
        }
        throw new IllegalArgumentException('Cannot locate ai-worklog-framework repository.')
    }
}
