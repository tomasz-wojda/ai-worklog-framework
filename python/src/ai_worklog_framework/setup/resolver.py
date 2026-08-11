import os
import shutil
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ai_worklog_framework.global_config import (
    SUPPORTED_IDES,
    SUPPORTED_RUNTIMES,
    canonical_workspace_path,
    load_global_config,
)
from ai_worklog_framework.shared import load_shared


def setup_rules() -> Dict[str, Any]:
    return load_shared(
        "setup-rules.json",
        {
            "manifest_version": 1,
            "report_version": 1,
            "ai_vault_environment": "AI_WORKLOG_AI_VAULT_ROOT",
            "setup_manifest_path": ".ai-worklog/setup.json",
            "vault_manifest": "skills/manifest.json",
            "vault_skills_dir": "skills",
            "vault_validate_script": "scripts/validate-skills.sh",
            "vault_skill_file": "SKILL.md",
            "workspace_fallback_subpath": "repos/ai-vault",
            "supported_ides": ["cursor", "claude", "antigravity"],
            "ides": {},
            "auto_detection": {},
        },
    )


def ide_materialization(ide: str) -> Dict[str, str]:
    rules = setup_rules()
    profile = rules.get("ides", {}).get(ide)
    if not profile:
        raise ValueError(f"Invalid ide: {ide}")
    return {
        "destination": profile["destination"],
        "materialization": profile["materialization"],
    }


def resolve_ai_vault_root(
    workspace: Path,
    cli_override: Optional[str] = None,
    environment: Optional[Dict[str, str]] = None,
) -> Tuple[Optional[Path], Optional[str]]:
    env = environment if environment is not None else os.environ
    rules = setup_rules()
    env_key = rules.get("ai_vault_environment", "AI_WORKLOG_AI_VAULT_ROOT")
    fallback = workspace / rules.get("workspace_fallback_subpath", "repos/ai-vault")

    candidates: List[Tuple[str, Optional[str]]] = [
        ("cli", cli_override),
        ("env", env.get(env_key)),
        ("global", None),
        ("workspace_fallback", str(fallback)),
    ]

    for source, raw in candidates:
        if source == "global":
            config = load_global_config()
            raw = config.get("ai_vault_root")
            if not raw:
                continue
        elif not raw:
            continue
        try:
            resolved = canonical_workspace_path(str(raw))
        except ValueError:
            continue
        if resolved.is_dir():
            return resolved, source
    return None, None


def validate_runtime(runtime: str) -> bool:
    if runtime not in SUPPORTED_RUNTIMES:
        return False
    if runtime == "python":
        return shutil.which("python3") is not None or shutil.which("python") is not None
    return shutil.which("groovy") is not None


def resolve_runtime_selection(
    explicit: Optional[str] = None,
) -> Tuple[str, str, bool]:
    config = load_global_config()
    if explicit:
        if explicit not in SUPPORTED_RUNTIMES:
            raise ValueError(f"Invalid runtime: {explicit}")
        return explicit, "explicit", validate_runtime(explicit)
    current = config.get("runtime", "groovy")
    return current, "global", validate_runtime(current)


def _user_home(environment: Optional[Dict[str, str]] = None) -> Path:
    env = environment if environment is not None else os.environ
    home = env.get("HOME")
    if home:
        return Path(home)
    return Path.home()


def detect_ides(workspace: Path, environment: Optional[Dict[str, str]] = None) -> List[str]:
    env = environment if environment is not None else os.environ
    rules = setup_rules()
    detected: List[str] = []
    home_dir = _user_home(env)
    for ide in sorted(rules.get("supported_ides", [])):
        if ide not in SUPPORTED_IDES:
            continue
        hints = rules.get("auto_detection", {}).get(ide, {})
        found = False
        for command in hints.get("commands", []):
            if shutil.which(command, path=env.get("PATH")):
                found = True
                break
        if not found:
            for home in hints.get("config_homes", []):
                if (workspace / home).exists() or (home_dir / home).exists():
                    found = True
                    break
        if found:
            detected.append(ide)
    return detected


def normalize_ide_selection(
    requested: Optional[List[str]],
    existing: Optional[List[str]] = None,
    workspace: Optional[Path] = None,
) -> List[str]:
    existing = list(existing or [])
    if not requested:
        if workspace is None:
            raise ValueError("Workspace path required for auto IDE detection")
        resolved = detect_ides(workspace)
        if not resolved:
            raise ValueError("No IDE detected")
        return sorted(set(existing) | set(resolved))
    if "auto" in requested:
        if len(requested) > 1:
            raise ValueError("--ide auto cannot be combined with explicit IDE values")
        if workspace is None:
            raise ValueError("Workspace path required for auto IDE detection")
        resolved = detect_ides(workspace)
        if not resolved:
            raise ValueError("No IDE detected")
        return sorted(set(existing) | set(resolved))
    normalized: List[str] = []
    for ide in requested:
        if ide not in SUPPORTED_IDES:
            raise ValueError(f"Invalid ide: {ide}")
        if ide not in normalized:
            normalized.append(ide)
    return sorted(set(existing) | set(normalized))


def parse_ide_args(ide_values: Optional[List[str]]) -> Optional[List[str]]:
    if not ide_values:
        return None
    return list(ide_values)
