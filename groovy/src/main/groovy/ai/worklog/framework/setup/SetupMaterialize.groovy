package ai.worklog.framework.setup

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

class SetupMaterialize {
    static List inspectDestination(
        File destination,
        File source,
        String materialization,
        Map manifestEntry,
        boolean adopt
    ) {
        if (!destination.exists() && !Files.isSymbolicLink(destination.toPath())) {
            return ['create', '']
        }
        if (materialization == 'symlink') {
            if (Files.isSymbolicLink(destination.toPath())) {
                File target = symlinkTarget(destination)
                if (target?.canonicalFile == source.canonicalFile) {
                    if (manifestEntry) {
                        return ['skip', 'already linked']
                    }
                    return ['adopt', 'matching unmanaged link']
                }
                if (manifestEntry && target != null &&
                    target.canonicalFile.path == manifestEntry.source?.toString()) {
                    return ['update', 'stale symlink']
                }
                if (adopt) {
                    return ['update', 'adopting foreign symlink']
                }
                return ['conflict', 'foreign symlink']
            }
            if (destination.isDirectory() || destination.isFile()) {
                if (adopt) {
                    return ['update', 'adopting foreign file or directory']
                }
                return ['conflict', 'foreign file or directory']
            }
            return ['create', '']
        }
        if (Files.isSymbolicLink(destination.toPath())) {
            if (adopt) {
                return ['update', 'adopting foreign symlink']
            }
            return ['conflict', 'foreign symlink']
        }
        if (!destination.isDirectory()) {
            if (adopt) {
                return ['update', 'adopting foreign file']
            }
            return ['conflict', 'foreign file']
        }
        String currentChecksum = SetupManifest.treeChecksum(destination)
        if (manifestEntry) {
            String applied = manifestEntry.applied_checksum?.toString()
            if (applied && currentChecksum != applied) {
                if (adopt) {
                    return ['update', 'adopting modified copy']
                }
                return ['conflict', 'modified copy']
            }
            if (applied && currentChecksum == applied) {
                String sourceChecksum = SetupManifest.treeChecksum(source)
                if (sourceChecksum != manifestEntry.source_checksum?.toString()) {
                    return ['update', 'source changed']
                }
                return ['skip', 'copy current']
            }
        }
        ['create', '']
    }

    static List planSkillMaterialization(
        File workspace,
        File vaultRoot,
        Map vaultManifest,
        List<String> ides,
        Map existingManifest,
        boolean adopt
    ) {
        Map rules = SetupResolver.rules()
        File skillsDir = new File(vaultRoot, rules.vault_skills_dir?.toString() ?: 'skills')
        Map<String, Map> skillIndex = SetupManifest.manifestSkillIndex(existingManifest)
        List<Map> actions = []
        List<Map> conflicts = []
        List<Map> skillRecords = []

        ides.sort().each { ide ->
            Map profile = SetupResolver.ideMaterialization(ide)
            File destinationRoot = new File(workspace, profile.destination)
            String materialization = profile.materialization
            SetupVault.skillsForIde(vaultManifest, ide).each { Map skill ->
                File source = new File(skillsDir, skill.dir.toString())
                File destination = new File(destinationRoot, skill.name.toString())
                String key = "${ide}:${skill.name}"
                Map manifestEntry = skillIndex[key]
                List inspection = inspectDestination(destination, source, materialization, manifestEntry, adopt)
                String disposition = inspection[0]
                String reason = inspection[1]
                Map action = [
                    kind: materialization,
                    ide: ide,
                    skill: skill.name.toString(),
                    source: source,
                    target: destination,
                    skip: disposition in ['skip', 'adopt'],
                    reason: reason,
                    disposition: disposition
                ]
                if (disposition == 'conflict') {
                    conflicts << [path: destination.path, reason: reason]
                    action.skip = true
                    actions << action
                    return
                }
                actions << action
                if (disposition in ['skip', 'create', 'update', 'adopt']) {
                    Map record = SetupManifest.buildSkillRecord(
                        skill.name.toString(),
                        ide,
                        source,
                        destination,
                        materialization,
                        manifestEntry
                    )
                    if (disposition == 'skip' && manifestEntry) {
                        record = new LinkedHashMap(manifestEntry)
                        record.source_checksum = SetupManifest.treeChecksum(source)
                    }
                    skillRecords << record
                }
            }
        }
        [actions, conflicts, skillRecords]
    }

    static void applySkillAction(Map action) {
        if (action.skip) {
            return
        }
        if (action.disposition == 'conflict') {
            return
        }
        File target = (File) action.target
        File source = (File) action.source
        String kind = action.kind.toString()
        target.parentFile?.mkdirs()
        if (kind == 'symlink') {
            if (Files.isSymbolicLink(target.toPath())) {
                Files.delete(target.toPath())
            } else if (target.isDirectory()) {
                deleteRecursive(target)
            } else if (target.exists()) {
                Files.delete(target.toPath())
            }
            try {
                Files.createSymbolicLink(target.toPath(), source.canonicalFile.toPath())
            } catch (Exception exc) {
                if (System.getProperty('os.name')?.toLowerCase()?.contains('win')) {
                    Process proc = new ProcessBuilder('cmd.exe', '/c', 'mklink', '/J', target.absolutePath, source.canonicalFile.absolutePath).start()
                    if (proc.waitFor() != 0) {
                        throw exc
                    }
                } else {
                    throw exc
                }
            }
            return
        }
        if (kind == 'copy') {
            if (target.exists()) {
                if (Files.isSymbolicLink(target.toPath())) {
                    Files.delete(target.toPath())
                } else {
                    deleteRecursive(target)
                }
            }
            copyTree(source, target)
            action.applied_checksum = SetupManifest.treeChecksum(target)
        }
    }

    static List canRemoveSkillArtifact(File destination, Map manifestEntry) {
        if (!destination.exists() && !Files.isSymbolicLink(destination.toPath())) {
            return [true, 'already absent']
        }
        String materialization = manifestEntry.materialization?.toString()
        if (materialization == 'symlink') {
            if (!Files.isSymbolicLink(destination.toPath())) {
                return [false, 'not a managed symlink']
            }
            File target = symlinkTarget(destination)
            File expected = new File(manifestEntry.source.toString()).canonicalFile
            if (target?.canonicalFile != expected) {
                return [false, 'symlink target mismatch']
            }
            return [true, '']
        }
        if (Files.isSymbolicLink(destination.toPath())) {
            return [false, 'foreign symlink']
        }
        if (!destination.isDirectory()) {
            return [false, 'not a managed copy']
        }
        String applied = manifestEntry.applied_checksum?.toString()
        if (applied) {
            String currentChecksum = SetupManifest.treeChecksum(destination)
            if (currentChecksum != applied) {
                return [false, 'modified copy']
            }
        }
        [true, '']
    }

    static List removeSkillArtifact(File destination, Map manifestEntry) {
        List result = canRemoveSkillArtifact(destination, manifestEntry)
        if (!result[0]) {
            return result
        }
        if (Files.isSymbolicLink(destination.toPath())) {
            Files.delete(destination.toPath())
            return [true, '']
        }
        if (destination.isDirectory()) {
            deleteRecursive(destination)
        }
        [true, result[1]?.toString() ?: '']
    }

    static void cleanupEmptyParent(File path, File stopAt) {
        File current = path
        while (current != stopAt && current.isDirectory()) {
            File[] children = current.listFiles()
            if (children && children.length > 0) {
                break
            }
            File parent = current.parentFile
            current.deleteDir()
            current = parent
        }
    }

    private static File symlinkTarget(File path) {
        if (!Files.isSymbolicLink(path.toPath())) {
            return null
        }
        try {
            return Files.readSymbolicLink(path.toPath()).toFile().canonicalFile
        } catch (IOException ignored) {
            return null
        }
    }

    private static void copyTree(File source, File target) {
        Files.walkFileTree(source.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path relative = source.toPath().relativize(dir)
                Path dest = target.toPath().resolve(relative)
                Files.createDirectories(dest)
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = source.toPath().relativize(file)
                Path dest = target.toPath().resolve(relative)
                Files.createDirectories(dest.parent)
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                FileVisitResult.CONTINUE
            }
        })
    }

    private static void deleteRecursive(File target) {
        if (target.isDirectory()) {
            target.eachFile { File child ->
                deleteRecursive(child)
            }
        }
        target.delete()
    }
}
