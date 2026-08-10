package ai.worklog.framework.workspace

import ai.worklog.framework.core.JsonFiles

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class WorkspacePlanner {
    final File frameworkRoot
    final Map rules

    WorkspacePlanner(File frameworkRoot) {
        this.frameworkRoot = frameworkRoot
        rules = (Map) JsonFiles.read(new File(frameworkRoot, 'shared/workspace-init.json'), [:])
    }

    List<Map> planInit(File workspace) {
        List<Map> actions = []
        ((List) rules.directories).each { relative ->
            File target = new File(workspace, relative.toString())
            actions << [
                kind: 'mkdir',
                target: target,
                skip: target.isDirectory(),
                reason: target.isDirectory() ? 'already exists' : ''
            ]
        }

        File configTarget = new File(workspace, rules.config_target.toString())
        actions << [
            kind: 'copy',
            source: new File(frameworkRoot, rules.config_template.toString()),
            target: configTarget,
            skip: configTarget.exists(),
            reason: configTarget.exists() ? 'already exists' : ''
        ]

        File interfaceDir = new File(workspace, rules.interface_path.toString())
        ((List) rules.services).each { service ->
            File source = new File(workspace, service.toString())
            File target = new File(interfaceDir, service.toString())
            boolean skip = !source.isDirectory() || Files.isSymbolicLink(source.toPath()) ||
                target.exists() || Files.isSymbolicLink(target.toPath())
            String reason = !source.isDirectory() ? 'source absent' :
                Files.isSymbolicLink(source.toPath()) ? 'source is symlink' :
                target.exists() || Files.isSymbolicLink(target.toPath()) ? 'target exists' : ''
            actions << [
                kind: 'symlink',
                source: Paths.get('../..', service.toString()),
                target: target,
                skip: skip,
                reason: reason
            ]
        }
        actions
    }

    List<Map> planRevert(File workspace) {
        File interfaceDir = new File(workspace, rules.interface_path.toString())
        ((List) rules.services).collect { service ->
            File target = new File(interfaceDir, service.toString())
            Path expected = Paths.get('../..', service.toString())
            boolean managed = Files.isSymbolicLink(target.toPath()) &&
                Files.readSymbolicLink(target.toPath()) == expected
            [
                kind: 'unlink',
                target: target,
                skip: !managed,
                reason: managed ? '' : 'not a managed link'
            ]
        }
    }

    static void apply(List<Map> actions) {
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
                    Files.createSymbolicLink(target.toPath(), (Path) action.source)
                    break
                case 'unlink':
                    Files.delete(target.toPath())
                    break
            }
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
            default:
                detail = "unlink ${action.target}"
        }
        "${prefix} ${detail}"
    }
}
