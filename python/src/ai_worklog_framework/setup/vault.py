import json
from pathlib import Path
from typing import Any, Dict, List, Tuple

from ai_worklog_framework.global_config import SUPPORTED_IDES
from ai_worklog_framework.setup.resolver import setup_rules


def validate_vault_root(vault_root: Path) -> Tuple[bool, str, Dict[str, Any]]:
    rules = setup_rules()
    skills_dir = vault_root / rules.get("vault_skills_dir", "skills")
    manifest_path = skills_dir / Path(rules.get("vault_manifest", "skills/manifest.json")).name
    if not manifest_path.is_file():
        manifest_path = vault_root / rules.get("vault_manifest", "skills/manifest.json")
    validate_script = vault_root / rules.get("vault_validate_script", "scripts/validate-skills.sh")
    skill_file = rules.get("vault_skill_file", "SKILL.md")

    if not skills_dir.is_dir():
        return False, "skills directory missing", {}
    if not manifest_path.is_file():
        return False, "skill manifest missing", {}
    if not validate_script.is_file():
        return False, "validate-skills.sh missing", {}

    try:
        with manifest_path.open("r", encoding="utf-8") as handle:
            manifest = json.load(handle)
    except (OSError, json.JSONDecodeError):
        return False, "skill manifest unreadable", {}

    if not isinstance(manifest, dict):
        return False, "skill manifest malformed", {}
    if "version" not in manifest or "skills" not in manifest:
        return False, "skill manifest missing required fields", {}
    if not isinstance(manifest["skills"], list):
        return False, "skill manifest skills must be a list", {}

    seen_names: set[str] = set()
    for entry in manifest["skills"]:
        if not isinstance(entry, dict):
            return False, "skill manifest entry malformed", manifest
        name = entry.get("name")
        directory = entry.get("dir")
        ides = entry.get("ides", [])
        if not isinstance(name, str) or not name.strip():
            return False, "skill manifest entry missing name", manifest
        if name in seen_names:
            return False, f"duplicate skill name: {name}", manifest
        seen_names.add(name)
        if not isinstance(directory, str) or not directory.strip():
            return False, f"skill manifest entry missing dir: {name}", manifest
        skill_dir = skills_dir / directory
        skill_md = skill_dir / skill_file
        if not skill_dir.is_dir():
            return False, f"skill directory missing: {name}", manifest
        if not skill_md.is_file():
            return False, f"SKILL.md missing: {name}", manifest
        if not isinstance(ides, list):
            return False, f"invalid ides for skill: {name}", manifest
        for ide in ides:
            if ide not in SUPPORTED_IDES:
                return False, f"invalid ide in manifest: {ide}", manifest

    return True, "valid", manifest


def skills_for_ide(manifest: Dict[str, Any], ide: str) -> List[Dict[str, Any]]:
    skills: List[Dict[str, Any]] = []
    for entry in manifest.get("skills", []):
        if ide in entry.get("ides", []):
            skills.append(entry)
    return skills
