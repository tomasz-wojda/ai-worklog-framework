package ai.worklog.framework.catalog

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.ConfigLoader
import ai.worklog.framework.core.JsonFiles
import groovy.json.JsonOutput

class CatalogLoader {
    final File frameworkRoot
    final FrameworkPaths paths
    final Map rules

    CatalogLoader(File frameworkRoot, FrameworkPaths paths) {
        this.frameworkRoot = frameworkRoot
        this.paths = paths
        this.rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/catalog-rules.json'),
            [
                required_fields: ['id', 'name', 'type'],
                valid_types: ['application', 'infrastructure', 'library', 'pipeline', 'platform'],
                forbidden_secret_fields: ['value', 'password']
            ]
        )
    }

    Map<String, Map> load() {
        Map<String, Map> catalog = new LinkedHashMap<>()
        Map config = ConfigLoader.load(paths.root)
        File configuredCatalog = new File(config.catalog_path.toString())
        if (!configuredCatalog.isAbsolute()) {
            configuredCatalog = new File(paths.root, config.catalog_path.toString())
        }
        List<File> directories = [
            new File(frameworkRoot, 'catalog'),
            configuredCatalog,
            paths.catalogDir
        ].unique { it.canonicalPath }
        directories.each { directory ->
            if (directory.isDirectory()) {
                directory.listFiles()
                    ?.findAll { it.isFile() && it.name.endsWith('.json') }
                    ?.sort { it.name }
                    ?.each { file -> addDocument(catalog, JsonFiles.read(file, [:])) }
            }
        }
        catalog
    }

    private static void addDocument(Map<String, Map> catalog, Object document) {
        if (document instanceof Map && document.id) {
            catalog[document.id.toString()] = (Map) document
        } else if (document instanceof List) {
            document.findAll { it instanceof Map && it.id }.each {
                catalog[it.id.toString()] = (Map) it
            }
        }
    }

    List<String> validate(Map entry) {
        List<String> errors = []
        ((List) rules.required_fields).each { field ->
            if (!entry.containsKey(field)) {
                errors << "Missing required field: ${field}"
            }
        }
        if (entry.type && !((List) rules.valid_types).contains(entry.type)) {
            errors << "Invalid type '${entry.type}', must be one of: ${rules.valid_types}"
        }
        if (entry.repositories != null && !(entry.repositories instanceof List)) {
            errors << "'repositories' must be an array"
        } else {
            ((List) (entry.repositories ?: [])).eachWithIndex { repo, index ->
                if (!(repo instanceof Map)) {
                    errors << "repositories[${index}] must be an object"
                } else if (!repo.url && !repo.local_dir) {
                    errors << "repositories[${index}] must have 'url' or 'local_dir'"
                }
            }
        }
        if (entry.jenkins != null && !(entry.jenkins instanceof Map)) {
            errors << "'jenkins' must be an object"
        }
        if (entry.argocd != null && !(entry.argocd instanceof Map)) {
            errors << "'argocd' must be an object"
        }
        if (entry.secrets != null && !(entry.secrets instanceof List)) {
            errors << "'secrets' must be an array"
        } else {
            ((List) (entry.secrets ?: [])).eachWithIndex { secret, index ->
                if (secret instanceof Map &&
                    ((List) rules.forbidden_secret_fields).any { secret.containsKey(it) }) {
                    errors << "secrets[${index}] must NOT contain actual secret values"
                }
            }
        }
        errors
    }

    List<String> findServices(String project, List<String> components = [], String summary = '') {
        Map<String, Map> catalog = load()
        List<List> matches = []
        List<String> componentValues = components.collect { it.toLowerCase() }
        catalog.each { id, entry ->
            int score = 0
            Map jira = entry.jira instanceof Map ? (Map) entry.jira : [:]
            if (project && jira.project == project) {
                score += 10
            }
            List<String> entryComponents = ((List) (jira.components ?: [])).collect {
                it.toString().toLowerCase()
            }
            componentValues.each { if (entryComponents.contains(it)) score += 5 }
            String lowerSummary = summary.toLowerCase()
            if (lowerSummary) {
                if (entry.name && lowerSummary.contains(entry.name.toString().toLowerCase())) score += 3
                if (lowerSummary.contains(id.toLowerCase())) score += 3
            }
            if (score > 0) {
                matches << [score, id]
            }
        }
        matches.sort { a, b -> b[0] <=> a[0] }.collect { it[1].toString() }
    }

    static String pretty(Object value) {
        JsonOutput.prettyPrint(JsonOutput.toJson(value))
    }
}
