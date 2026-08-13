package ai.worklog.framework.core

import ai.worklog.framework.core.JsonFiles

class FrameworkPaths {
    final File root
    final File worklog
    final File worklogDone
    final File stateDir
    final File configDir
    final File catalogDir
    final File integrationsDir
    final File interfaceDir
    final File promptLog

    private static final Map WORKSPACE_LAYOUT = (Map) JsonFiles.read(
        new File(resolveFrameworkRoot(), 'shared/workspace-init.json'),
        [integrations_path: 'integrations']
    )
    private static final String INTEGRATIONS_PATH =
        WORKSPACE_LAYOUT.integrations_path.toString()

    FrameworkPaths(File root) {
        this.root = root.canonicalFile
        this.worklog = new File(this.root, 'worklog')
        this.worklogDone = new File(worklog, 'done')
        this.stateDir = new File(this.root, '.ai-worklog/state')
        this.configDir = new File(this.root, '.ai-worklog')
        this.catalogDir = new File(this.root, '.ai-worklog/catalog')
        this.integrationsDir = new File(this.root, INTEGRATIONS_PATH)
        this.interfaceDir = integrationsDir
        this.promptLog = new File(this.root, 'prompt.log')
    }

    File serviceDir(String service) {
        validateComponent(service, 'service')
        File canonical = new File(integrationsDir, service)
        if (canonical.exists()) {
            return canonical
        }
        File rootPath = new File(root, service)
        rootPath.exists() ? rootPath : canonical
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

    static File resolveWorkspace(String explicitPath, String explicitName, File frameworkRoot) {
        GlobalConfig.resolveWorkspace(explicitPath, explicitName, frameworkRoot)
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
