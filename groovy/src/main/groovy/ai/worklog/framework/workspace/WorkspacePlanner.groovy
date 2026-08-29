package ai.worklog.framework.workspace

import ai.worklog.framework.core.JsonFiles

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class WorkspacePlanner {
    final File frameworkRoot
    final Map rules

    WorkspacePlanner(File frameworkRoot) {
        this.frameworkRoot = frameworkRoot
        rules = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/workspace-init.json'), [:])
    }

    Map planInit(File workspace) {
        String integrationsRel = rules.integrations_path?.toString() ?: 'integrations'
        File integrations = new File(workspace, integrationsRel)
        List<Map> actions = []
        List<Map> conflicts = []
        List<String> services = ((List) rules.services)*.toString()

        ((List) rules.directories).each { relative ->
            File target = new File(workspace, relative.toString())
            actions << [
                kind: 'mkdir',
                target: target,
                skip: target.isDirectory(),
                reason: target.isDirectory() ? 'already exists' : ''
            ]
        }

        ((List) rules.files).each { managedFile ->
            File target = new File(workspace, managedFile.target.toString())
            actions << [
                kind: 'copy',
                source: new File(frameworkRoot, managedFile.source.toString()),
                target: target,
                skip: target.exists(),
                reason: target.exists() ? 'already exists' : ''
            ]
        }

        services.each { String service ->
            File canonical = new File(integrations, service)

            if (Files.isSymbolicLink(canonical.toPath())) {
                actions << [
                    kind: 'mkdir',
                    target: canonical,
                    skip: true,
                    reason: 'already linked'
                ]
            } else if (canonical.isDirectory()) {
                actions << [
                    kind: 'mkdir',
                    target: canonical,
                    skip: true,
                    reason: 'already exists'
                ]
            } else if (canonical.exists()) {
                String reason = foreignIntegrationReason(canonical)
                conflicts << [path: canonical.path, reason: reason]
                actions << [
                    kind: 'mkdir',
                    target: canonical,
                    skip: true,
                    reason: reason
                ]
            } else {
                actions << [
                    kind: 'mkdir',
                    target: canonical,
                    skip: false,
                    reason: ''
                ]
            }
        }

        [actions: actions, conflicts: conflicts]
    }

    Map planRevert(File workspace) {
        String integrationsRel = rules.integrations_path?.toString() ?: 'integrations'
        File integrations = new File(workspace, integrationsRel)
        List<String> services = ((List) rules.services)*.toString()
        List<Map> actions = []
        Set<String> created = WorkspaceProvenance.loadCreated(workspace)

        services.each { String service ->
            File canonical = new File(integrations, service)
            String reason = retainReason(canonical, "${integrationsRel}/${service}", created)
            actions << [
                kind: 'rmdir',
                target: canonical,
                skip: reason as boolean,
                reason: reason
            ]
        }

        [actions: actions, conflicts: []]
    }

    private static String retainReason(File canonical, String relative, Set<String> created) {
        if (Files.isSymbolicLink(canonical.toPath())) {
            return 'not created by the framework'
        }
        if (!canonical.isDirectory()) {
            return 'not present'
        }
        if (!created.contains(relative)) {
            return 'not created by the framework'
        }
        if (canonical.listFiles()) {
            return 'not empty'
        }
        ''
    }

    static String legacyIntegrationStatus(File workspace, Map rules) {
        null
    }

    static void apply(List<Map> actions, File workspace = null) {
        actions.findAll { !it.skip }.each { action ->
            File target = (File) action.target
            switch (action.kind) {
                case 'mkdir':
                    Files.createDirectories(target.toPath())
                    break
                case 'copy':
                    Files.createDirectories(target.parentFile.toPath())
                    Files.copy(
                        ((File) action.source).toPath(),
                        target.toPath(),
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                    break
                case 'symlink':
                    Files.createDirectories(target.parentFile.toPath())
                    try {
                        Files.createSymbolicLink(target.toPath(), (Path) action.source)
                    } catch (IOException | UnsupportedOperationException exception) {
                        if (System.getProperty("os.name")?.toLowerCase()?.contains("win")) {
                            File sourceDir = new File(target.parentFile, action.source.toString()).canonicalFile
                            Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", target.absolutePath, sourceDir.absolutePath).start()
                            if (p.waitFor() != 0) {
                                throw exception
                            }
                        } else {
                            throw exception
                        }
                    }
                    break
                case 'unlink':
                case 'delete':
                    if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                        Files.delete(target.toPath())
                    }
                    break
                case 'rmdir':
                    if (target.isDirectory() && !target.listFiles()) {
                        Files.delete(target.toPath())
                    }
                    break
            }
        }

        if (workspace != null) {
            WorkspaceProvenance.recordCreated(workspace, actions)
        }
    }

    static String format(Map action, boolean applying) {
        if (action.skip) {
            return "skipped: ${action.target} (${action.reason})"
        }
        String prefix = applying ? 'run:' : 'would:'
        String detail
        switch (action.kind) {
            case 'mkdir':
                detail = "mkdir ${action.target}"
                break
            case 'copy':
                detail = "copy ${action.source} -> ${action.target}"
                break
            case 'symlink':
                detail = "link ${action.target} -> ${action.source}"
                break
            case 'rmdir':
                detail = "rmdir ${action.target}"
                break
            case 'delete':
                detail = "delete ${action.target}"
                break
            default:
                detail = "unlink ${action.target}"
        }
        "${prefix} ${detail}"
    }

    private static String foreignIntegrationReason(File target) {
        if (Files.isSymbolicLink(target.toPath())) {
            return 'foreign symlink'
        }
        if (target.isDirectory()) {
            return 'foreign directory'
        }
        'foreign file'
    }
}
