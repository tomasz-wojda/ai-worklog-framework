package ai.worklog.framework.setup

import ai.worklog.framework.core.JsonFiles
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class SetupManifest {
    private static final Set MANIFEST_TOP_KEYS = [
        'version',
        'workspace_name',
        'ai_vault_root',
        'ides',
        'skills',
        'synced_at'
    ] as Set
    private static final Set SKILL_KEYS = [
        'name',
        'ide',
        'source',
        'destination',
        'materialization',
        'source_checksum',
        'applied_checksum',
        'created_at',
        'synced_at'
    ] as Set
    private static final Set SKILL_REQUIRED_KEYS = SKILL_KEYS - ['applied_checksum'] as Set
    private static final Set ALLOWED_IDES = ['cursor', 'claude', 'antigravity'] as Set
    private static final Set ALLOWED_MATERIALIZATION = ['symlink', 'copy'] as Set

    static String utcNow() {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .format(OffsetDateTime.now(ZoneOffset.UTC))
    }

    static File manifestPath(File workspace) {
        new File(workspace, SetupResolver.rules().setup_manifest_path?.toString() ?: '.ai-worklog/setup.json')
    }

    static String treeChecksum(File path) {
        MessageDigest digest = MessageDigest.getInstance('SHA' + '-256')
        if (path.isFile()) {
            digest.update(Files.readAllBytes(path.toPath()))
            return toHex(digest.digest())
        }
        if (!path.isDirectory()) {
            return toHex(digest.digest())
        }
        List<File> files = []
        path.eachFileRecurse { File item ->
            if (item.isFile()) {
                files << item
            }
        }
        files.sort { a, b ->
            path.toPath().relativize(a.toPath()).toString() <=> path.toPath().relativize(b.toPath()).toString()
        }
        files.each { File item ->
            String relative = path.toPath().relativize(item.toPath()).toString().replace('\\', '/')
            digest.update(relative.bytes)
            digest.update(Files.readAllBytes(item.toPath()))
        }
        toHex(digest.digest())
    }

    static Map validateManifest(Object data) {
        if (!(data instanceof Map)) {
            throw new IllegalArgumentException('Malformed setup manifest')
        }
        Map manifest = (Map) data
        Set keys = manifest.keySet()
        Set unknown = keys - MANIFEST_TOP_KEYS
        if (unknown) {
            throw new IllegalArgumentException("Setup manifest unknown fields: ${unknown.sort().join(', ')}")
        }
        Set missing = MANIFEST_TOP_KEYS - keys
        if (missing) {
            throw new IllegalArgumentException("Setup manifest missing fields: ${missing.sort().join(', ')}")
        }

        int expectedVersion = (SetupResolver.rules().manifest_version ?: 1) as int
        int version
        try {
            version = manifest.version as int
        } catch (Exception ignored) {
            throw new IllegalArgumentException('Setup manifest version is invalid')
        }
        if (version != expectedVersion) {
            throw new IllegalArgumentException("Setup manifest version must be ${expectedVersion}")
        }

        String workspaceName = nonemptyString(manifest.workspace_name, 'workspace_name')
        if (!(workspaceName ==~ /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/)) {
            throw new IllegalArgumentException('Setup manifest workspace_name is invalid')
        }

        String aiVaultRoot = nonemptyString(manifest.ai_vault_root, 'ai_vault_root')
        String syncedAt = nonemptyString(manifest.synced_at, 'synced_at')

        if (!(manifest.ides instanceof List)) {
            throw new IllegalArgumentException('Setup manifest ides must be a list')
        }
        List<String> ides = []
        Set seenIdes = [] as Set
        ((List) manifest.ides).each { item ->
            if (!(item instanceof String) || !(item in ALLOWED_IDES)) {
                throw new IllegalArgumentException('Setup manifest ides contain invalid value')
            }
            if (seenIdes.contains(item)) {
                throw new IllegalArgumentException('Setup manifest ides must be unique')
            }
            seenIdes << item
            ides << item
        }
        ides = ides.sort()

        if (!(manifest.skills instanceof List)) {
            throw new IllegalArgumentException('Setup manifest skills must be a list')
        }

        List<Map> skills = []
        Set seenSkillKeys = [] as Set
        ((List) manifest.skills).each { entryValue ->
            if (!(entryValue instanceof Map)) {
                throw new IllegalArgumentException('Setup manifest skill entry must be an object')
            }
            Map entry = (Map) entryValue
            Set skillKeys = entry.keySet()
            Set unknownSkill = skillKeys - SKILL_KEYS
            if (unknownSkill) {
                throw new IllegalArgumentException(
                    "Setup manifest skill unknown fields: ${unknownSkill.sort().join(', ')}"
                )
            }
            Set missingSkill = SKILL_REQUIRED_KEYS - skillKeys
            if (missingSkill) {
                throw new IllegalArgumentException(
                    "Setup manifest skill missing fields: ${missingSkill.sort().join(', ')}"
                )
            }

            String name = nonemptyString(entry.name, 'skill name')
            if (!(entry.ide instanceof String) || !(entry.ide in ALLOWED_IDES)) {
                throw new IllegalArgumentException('Setup manifest skill ide is invalid')
            }
            String ide = entry.ide as String
            String source = nonemptyString(entry.source, 'skill source')
            String destination = nonemptyString(entry.destination, 'skill destination')
            if (!(entry.materialization instanceof String) || !(entry.materialization in ALLOWED_MATERIALIZATION)) {
                throw new IllegalArgumentException('Setup manifest skill materialization is invalid')
            }
            String materialization = entry.materialization as String
            String sourceChecksum = nonemptyString(entry.source_checksum, 'skill source_checksum')
            String createdAt = nonemptyString(entry.created_at, 'skill created_at')
            String skillSyncedAt = nonemptyString(entry.synced_at, 'skill synced_at')

            if (entry.containsKey('applied_checksum')) {
                Object appliedChecksum = entry.applied_checksum
                if (appliedChecksum != null && (!(appliedChecksum instanceof String) || !appliedChecksum)) {
                    throw new IllegalArgumentException('Setup manifest skill applied_checksum is invalid')
                }
            }

            String skillKey = "${ide}:${name}"
            if (seenSkillKeys.contains(skillKey)) {
                throw new IllegalArgumentException('Setup manifest skills must have unique ide:name pairs')
            }
            seenSkillKeys << skillKey

            Map record = [
                name: name,
                ide: ide,
                source: source,
                destination: destination,
                materialization: materialization,
                source_checksum: sourceChecksum,
                created_at: createdAt,
                synced_at: skillSyncedAt
            ]
            if (entry.containsKey('applied_checksum')) {
                record.applied_checksum = entry.applied_checksum
            }
            skills << record
        }

        [
            version: version,
            workspace_name: workspaceName,
            ai_vault_root: aiVaultRoot,
            ides: ides,
            skills: skills,
            synced_at: syncedAt
        ]
    }

    static Map loadManifest(File workspace) {
        File path = manifestPath(workspace)
        if (!path.isFile()) {
            return null
        }
        try {
            Object data = new JsonSlurper().parse(path, 'UTF-8')
            return validateManifest(data)
        } catch (IllegalArgumentException exception) {
            throw exception
        } catch (Exception exception) {
            throw new IllegalArgumentException("Malformed setup manifest: ${path}", exception)
        }
    }

    static void saveManifest(File workspace, Map data) {
        Map validated = validateManifest(data)
        JsonFiles.write(manifestPath(workspace), validated)
    }

    static Map<String, Map> manifestSkillIndex(Map manifest) {
        if (!manifest) {
            return [:]
        }
        Map<String, Map> index = [:]
        ((List) manifest.skills).each { entryValue ->
            Map entry = (Map) entryValue
            index["${entry.ide}:${entry.name}"] = entry
        }
        index
    }

    static Map buildSkillRecord(
        String name,
        String ide,
        File source,
        File destination,
        String materialization,
        Map existing = null
    ) {
        String now = utcNow()
        String sourceChecksum = treeChecksum(source)
        String appliedChecksum = null
        if (materialization == 'copy' && destination.isDirectory()) {
            appliedChecksum = treeChecksum(destination)
        }
        String createdAt = existing?.created_at ?: now
        [
            name: name,
            ide: ide,
            source: source.canonicalFile.path,
            destination: destination.canonicalFile.path,
            materialization: materialization,
            source_checksum: sourceChecksum,
            applied_checksum: appliedChecksum,
            created_at: createdAt,
            synced_at: now
        ]
    }

    static Map composeManifest(
        String workspaceName,
        File aiVaultRoot,
        List<String> ides,
        List<Map> skills
    ) {
        Map rules = SetupResolver.rules()
        [
            version: (rules.manifest_version ?: 1) as int,
            workspace_name: workspaceName,
            ai_vault_root: aiVaultRoot.canonicalFile.path,
            ides: ides.unique().sort(),
            skills: skills,
            synced_at: utcNow()
        ]
    }

    private static String nonemptyString(Object value, String label) {
        if (!(value instanceof String) || !value) {
            throw new IllegalArgumentException("Setup manifest ${label} is invalid")
        }
        value as String
    }

    private static String toHex(byte[] bytes) {
        bytes.collect { String.format('%02x', it & 0xff) }.join('')
    }
}
