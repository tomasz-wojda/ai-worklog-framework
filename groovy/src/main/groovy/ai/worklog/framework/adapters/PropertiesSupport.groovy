package ai.worklog.framework.adapters

class PropertiesSupport {
    static Map load(File file) {
        Map values = [:]
        file.eachLine('UTF-8') { line ->
            String trimmed = line.trim()
            if (!trimmed || trimmed.startsWith('#')) {
                return
            }
            if (trimmed.contains('=')) {
                List<String> parts = trimmed.split('=', 2).toList()
                values[parts[0].trim()] = parts[1].trim()
            }
        }
        values
    }

    static Map controllers(File file) {
        Map values = load(file)
        Map controllers = [:]
        values.each { key, value ->
            List<String> parts = key.toString().split('\\.').toList()
            if (parts.size() >= 2) {
                String controller = parts[0]
                String field = parts[1..-1].join('.')
                if (!controllers[controller]) {
                    controllers[controller] = [:]
                }
                controllers[controller][field] = value
            }
        }
        controllers
    }

    static Map publicController(String id, Map info) {
        [
            id: id,
            url: info.url?.toString() ?: '',
            has_user: !!info.user?.toString(),
            has_token: !!info.token?.toString()
        ]
    }

    static Map publicControllers(Map controllers) {
        Map publicControllers = [:]
        controllers.keySet().sort().each { id ->
            publicControllers[id] = publicController(id.toString(), (Map) controllers[id])
        }
        publicControllers
    }
}
