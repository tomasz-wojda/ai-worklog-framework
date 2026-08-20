package ai.worklog.framework.core

class ConfigLoader {
    static Map defaults() {
        [
            catalog_path : 'catalog',
            interface_path: null,
            services     : [:],
            adapters     : [:],
            preflight    : [
                required_binaries: ['python3', 'git', 'gh', 'kubectl', 'aws', 'jq'],
                optional_binaries: ['groovy', 'java', 'argocd', 'helm']
            ],
            toolchain    : [groovy: [:]]
        ]
    }

    static Map load(File workspace) {
        File configDir = new File(workspace, '.ai-worklog')
        Map value = JsonFiles.deepMerge(defaults(), asMap(JsonFiles.read(new File(configDir, 'config.json'), [:])))
        Map merged = JsonFiles.deepMerge(value, asMap(JsonFiles.read(new File(configDir, 'local.json'), [:])))
        merged.interface_path = null
        merged
    }

    static Map asMap(Object value) {
        value instanceof Map ? (Map) value : [:]
    }
}
