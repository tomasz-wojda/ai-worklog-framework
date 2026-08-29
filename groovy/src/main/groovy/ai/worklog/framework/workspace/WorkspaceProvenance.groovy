package ai.worklog.framework.workspace

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class WorkspaceProvenance {

    static final int PROVENANCE_VERSION = 1
    static final String PROVENANCE_RELATIVE = '.ai-worklog/created.json'

    static File provenanceFile(File workspace) {
        new File(workspace, PROVENANCE_RELATIVE)
    }

    static Set<String> loadCreated(File workspace) {
        File path = provenanceFile(workspace)
        if (!path.isFile()) {
            return [] as Set
        }
        Object data
        try {
            data = new JsonSlurper().parse(path)
        } catch (Exception ignored) {
            return [] as Set
        }
        if (!(data instanceof Map)) {
            return [] as Set
        }
        Object entries = ((Map) data).directories
        if (!(entries instanceof List)) {
            return [] as Set
        }
        ((List) entries).findAll { it instanceof String && it }*.toString() as Set
    }

    static Set<String> createdFromActions(File workspace, List<Map> actions) {
        Path root = workspace.toPath().toAbsolutePath().normalize()
        Set<String> created = [] as Set
        actions.each { Map action ->
            if (action.skip || action.kind != 'mkdir') {
                return
            }
            Path target = ((File) action.target).toPath().toAbsolutePath().normalize()
            if (!target.startsWith(root) || target == root) {
                return
            }
            created << root.relativize(target).toString().replace(File.separator, '/')
        }
        created
    }

    static void recordCreated(File workspace, List<Map> actions) {
        Set<String> created = createdFromActions(workspace, actions)
        if (!created) {
            return
        }
        List<String> merged = ((loadCreated(workspace) + created) as List).sort()
        Map payload = [version: PROVENANCE_VERSION, directories: merged]
        File path = provenanceFile(workspace)
        path.parentFile.mkdirs()
        File tmp = File.createTempFile('.created.', '.tmp', path.parentFile)
        tmp.setText(JsonOutput.prettyPrint(JsonOutput.toJson(payload)) + System.lineSeparator(), 'UTF-8')
        Files.move(
            tmp.toPath(),
            path.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
