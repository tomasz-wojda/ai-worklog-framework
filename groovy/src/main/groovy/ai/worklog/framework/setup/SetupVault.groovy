package ai.worklog.framework.setup

import ai.worklog.framework.core.GlobalConfig
import groovy.json.JsonSlurper

class SetupVault {
    static List validateVaultRoot(File vaultRoot) {
        Map rules = SetupResolver.rules()
        File skillsDir = new File(vaultRoot, rules.vault_skills_dir?.toString() ?: 'skills')
        File manifestPath = new File(skillsDir, new File(rules.vault_manifest?.toString() ?: 'skills/manifest.json').name)
        if (!manifestPath.isFile()) {
            manifestPath = new File(vaultRoot, rules.vault_manifest?.toString() ?: 'skills/manifest.json')
        }
        File validateScript = new File(vaultRoot, rules.vault_validate_script?.toString() ?: 'scripts/validate-skills.sh')
        String skillFile = rules.vault_skill_file?.toString() ?: 'SKILL.md'

        if (!skillsDir.isDirectory()) {
            return [false, 'skills directory missing', [:]]
        }
        if (!manifestPath.isFile()) {
            return [false, 'skill manifest missing', [:]]
        }
        if (!validateScript.isFile()) {
            return [false, 'validate-skills.sh missing', [:]]
        }

        Object manifest
        try {
            manifest = new JsonSlurper().parse(manifestPath, 'UTF-8')
        } catch (Exception ignored) {
            return [false, 'skill manifest unreadable', [:]]
        }
        if (!(manifest instanceof Map)) {
            return [false, 'skill manifest malformed', [:]]
        }
        Map manifestMap = (Map) manifest
        if (!manifestMap.containsKey('version') || !manifestMap.containsKey('skills')) {
            return [false, 'skill manifest missing required fields', [:]]
        }
        if (!(manifestMap.skills instanceof List)) {
            return [false, 'skill manifest skills must be a list', [:]]
        }

        Set<String> seenNames = [] as Set
        for (Object entryValue : (List) manifestMap.skills) {
            if (!(entryValue instanceof Map)) {
                return [false, 'skill manifest entry malformed', manifestMap]
            }
            Map entry = (Map) entryValue
            String name = entry.name?.toString()
            String directory = entry.dir?.toString()
            Object ides = entry.ides ?: []
            if (!name?.trim()) {
                return [false, 'skill manifest entry missing name', manifestMap]
            }
            if (name in seenNames) {
                return [false, "duplicate skill name: ${name}", manifestMap]
            }
            seenNames << name
            if (!directory?.trim()) {
                return [false, "skill manifest entry missing dir: ${name}", manifestMap]
            }
            File skillDir = new File(skillsDir, directory)
            File skillMd = new File(skillDir, skillFile)
            if (!skillDir.isDirectory()) {
                return [false, "skill directory missing: ${name}", manifestMap]
            }
            if (!skillMd.isFile()) {
                return [false, "SKILL.md missing: ${name}", manifestMap]
            }
            if (!(ides instanceof List)) {
                return [false, "invalid ides for skill: ${name}", manifestMap]
            }
            for (Object ideValue : (List) ides) {
                if (!(ideValue.toString() in GlobalConfig.SUPPORTED_IDES)) {
                    return [false, "invalid ide in manifest: ${ideValue}", manifestMap]
                }
            }
        }
        [true, 'valid', manifestMap]
    }

    static List<Map> skillsForIde(Map manifest, String ide) {
        List<Map> skills = []
        ((List) (manifest.skills ?: [])).each { entryValue ->
            if (entryValue instanceof Map) {
                Map entry = (Map) entryValue
                if (((List) (entry.ides ?: [])).contains(ide)) {
                    skills << entry
                }
            }
        }
        skills
    }
}
