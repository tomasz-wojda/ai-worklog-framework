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
            File rootSource = new File(workspace, service)
            File canonical = new File(integrations, service)
            boolean canonicalManaged = isManagedLink(canonical, service)
            boolean canonicalDirectory = canonical.isDirectory() &&
                !Files.isSymbolicLink(canonical.toPath())
            boolean canonicalPresent = pathPresent(canonical)
            boolean rootReady = rootSource.isDirectory() && !Files.isSymbolicLink(rootSource.toPath())

            if (canonicalPresent && !canonicalManaged && !canonicalDirectory) {
                String reason = foreignIntegrationReason(canonical)
                conflicts << [path: canonical.path, reason: reason]
                actions << [
                    kind: 'symlink',
                    source: managedTarget(service),
                    target: canonical,
                    skip: true,
                    reason: reason
                ]
            } else if (canonicalManaged || canonicalDirectory) {
                actions << [
                    kind: 'symlink',
                    source: managedTarget(service),
                    target: canonical,
                    skip: true,
                    reason: canonicalManaged ? 'already linked' : 'integration present'
                ]
            } else if (rootReady) {
                actions << [
                    kind: 'symlink',
                    source: managedTarget(service),
                    target: canonical,
                    skip: false,
                    reason: ''
                ]
            } else {
                actions << [
                    kind: 'symlink',
                    source: managedTarget(service),
                    target: canonical,
                    skip: true,
                    reason: 'source absent'
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

        services.each { String service ->
            File canonical = new File(integrations, service)
            boolean canonicalManaged = isManagedLink(canonical, service)
            actions << [
                kind: 'unlink',
                target: canonical,
                skip: !canonicalManaged,
                reason: canonicalManaged ? '' : 'not a managed link'
            ]
        }

        appendDirectoryCleanup(actions, integrations)

        ((List) rules.files ?: []).reverse().each { managedFile ->
            File target = new File(workspace, managedFile.target.toString())
            actions << [
                kind: 'delete',
                target: target,
                skip: !target.exists(),
                reason: target.exists() ? '' : 'not present'
            ]
        }

        ((List) rules.directories ?: []).reverse().each { relative ->
            File target = new File(workspace, relative.toString())
            if (target.path != integrations.path) {
                appendDirectoryCleanup(actions, target)
            }
        }

        [actions: actions, conflicts: []]
    }

    static String legacyIntegrationStatus(File workspace, Map rules) {
        null
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

    private static Path managedTarget(String service, boolean legacy = false) {
        legacy ? Paths.get('../..', service) : Paths.get('..', service)
    }

    private static boolean isManagedLink(File target, String service, boolean legacy = false) {
        if (!Files.isSymbolicLink(target.toPath())) {
            if (System.getProperty("os.name")?.toLowerCase()?.contains("win") && target.isDirectory()) {
                File expectedSource = new File(target.parentFile, managedTarget(service, legacy).toString()).canonicalFile
                return target.canonicalFile == expectedSource
            }
            return false
        }
        Path read = Files.readSymbolicLink(target.toPath())
        read == managedTarget(service, legacy) || read.toAbsolutePath().normalize() == new File(target.parentFile, managedTarget(service, legacy).toString()).toPath().toAbsolutePath().normalize()
    }

    private static boolean pathPresent(File target) {
        target.exists() || Files.isSymbolicLink(target.toPath())
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

    private static void appendDirectoryCleanup(List<Map> actions, File directory) {
        if (!directory.isDirectory()) {
            actions << [
                kind: 'rmdir',
                target: directory,
                skip: true,
                reason: 'not present'
            ]
            return
        }
        Set<File> plannedRemovals = actions.findAll {
            (it.kind in ['unlink', 'delete', 'rmdir']) && !it.skip
        }.collect { ((File) it.target).canonicalFile } as Set
        List<File> remaining = directory.listFiles()?.findAll { File entry ->
            !(entry.canonicalFile in plannedRemovals)
        } ?: []
        actions << [
            kind: 'rmdir',
            target: directory,
            skip: !remaining.isEmpty(),
            reason: remaining ? 'not empty' : ''
        ]
    }
}
