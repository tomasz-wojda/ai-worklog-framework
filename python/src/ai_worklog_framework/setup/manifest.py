import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

from ai_worklog_framework.setup.resolver import setup_rules

_MANIFEST_TOP_KEYS: Set[str] = {
    "version",
    "workspace_name",
    "ai_vault_root",
    "ides",
    "skills",
    "synced_at",
}
_SKILL_KEYS: Set[str] = {
    "name",
    "ide",
    "source",
    "destination",
    "materialization",
    "source_checksum",
    "applied_checksum",
    "created_at",
    "synced_at",
}
_SKILL_REQUIRED_KEYS: Set[str] = _SKILL_KEYS - {"applied_checksum"}
_WORKSPACE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_ALLOWED_IDES: Set[str] = {"cursor", "claude", "antigravity"}
_ALLOWED_MATERIALIZATION: Set[str] = {"symlink", "copy"}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def manifest_path(workspace: Path) -> Path:
    return workspace / setup_rules().get("setup_manifest_path", ".ai-worklog/setup.json")


def tree_checksum(path: Path) -> str:
    digest = hashlib.sha256()
    if path.is_file():
        digest.update(path.read_bytes())
        return digest.hexdigest()
    if not path.is_dir():
        return digest.hexdigest()
    for item in sorted(path.rglob("*")):
        if item.is_file():
            relative = item.relative_to(path).as_posix()
            digest.update(relative.encode("utf-8"))
            digest.update(item.read_bytes())
    return digest.hexdigest()


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"Setup manifest {label} is invalid")
    return value


def validate_manifest(data: Any) -> Dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError("Malformed setup manifest")

    keys = set(data.keys())
    unknown = keys - _MANIFEST_TOP_KEYS
    if unknown:
        raise ValueError(f"Setup manifest unknown fields: {', '.join(sorted(unknown))}")

    missing = _MANIFEST_TOP_KEYS - keys
    if missing:
        raise ValueError(f"Setup manifest missing fields: {', '.join(sorted(missing))}")

    expected_version = setup_rules().get("manifest_version", 1)
    try:
        version = int(data["version"])
    except (TypeError, ValueError) as exc:
        raise ValueError("Setup manifest version is invalid") from exc
    if version != expected_version:
        raise ValueError(f"Setup manifest version must be {expected_version}")

    workspace_name = _nonempty_string(data["workspace_name"], "workspace_name")
    if not _WORKSPACE_NAME_PATTERN.match(workspace_name):
        raise ValueError("Setup manifest workspace_name is invalid")

    ai_vault_root = _nonempty_string(data["ai_vault_root"], "ai_vault_root")
    synced_at = _nonempty_string(data["synced_at"], "synced_at")

    if not isinstance(data["ides"], list):
        raise ValueError("Setup manifest ides must be a list")

    ides: List[str] = []
    seen_ides: Set[str] = set()
    for item in data["ides"]:
        if not isinstance(item, str) or item not in _ALLOWED_IDES:
            raise ValueError("Setup manifest ides contain invalid value")
        if item in seen_ides:
            raise ValueError("Setup manifest ides must be unique")
        seen_ides.add(item)
        ides.append(item)
    ides.sort()

    if not isinstance(data["skills"], list):
        raise ValueError("Setup manifest skills must be a list")

    skills: List[Dict[str, Any]] = []
    seen_skill_keys: Set[str] = set()
    for item in data["skills"]:
        if not isinstance(item, dict):
            raise ValueError("Setup manifest skill entry must be an object")

        skill_keys = set(item.keys())
        unknown_skill = skill_keys - _SKILL_KEYS
        if unknown_skill:
            raise ValueError(
                f"Setup manifest skill unknown fields: {', '.join(sorted(unknown_skill))}"
            )

        missing_skill = _SKILL_REQUIRED_KEYS - skill_keys
        if missing_skill:
            raise ValueError(
                f"Setup manifest skill missing fields: {', '.join(sorted(missing_skill))}"
            )

        name = _nonempty_string(item["name"], "skill name")
        ide = item["ide"]
        if not isinstance(ide, str) or ide not in _ALLOWED_IDES:
            raise ValueError("Setup manifest skill ide is invalid")

        source = _nonempty_string(item["source"], "skill source")
        destination = _nonempty_string(item["destination"], "skill destination")

        materialization = item["materialization"]
        if not isinstance(materialization, str) or materialization not in _ALLOWED_MATERIALIZATION:
            raise ValueError("Setup manifest skill materialization is invalid")

        source_checksum = _nonempty_string(item["source_checksum"], "skill source_checksum")
        created_at = _nonempty_string(item["created_at"], "skill created_at")
        skill_synced_at = _nonempty_string(item["synced_at"], "skill synced_at")

        applied_checksum = item.get("applied_checksum")
        if "applied_checksum" in item:
            if applied_checksum is not None and (
                not isinstance(applied_checksum, str) or not applied_checksum
            ):
                raise ValueError("Setup manifest skill applied_checksum is invalid")

        skill_key = f"{ide}:{name}"
        if skill_key in seen_skill_keys:
            raise ValueError("Setup manifest skills must have unique ide:name pairs")
        seen_skill_keys.add(skill_key)

        record: Dict[str, Any] = {
            "name": name,
            "ide": ide,
            "source": source,
            "destination": destination,
            "materialization": materialization,
            "source_checksum": source_checksum,
            "created_at": created_at,
            "synced_at": skill_synced_at,
        }
        if "applied_checksum" in item:
            record["applied_checksum"] = applied_checksum
        skills.append(record)

    return {
        "version": version,
        "workspace_name": workspace_name,
        "ai_vault_root": ai_vault_root,
        "ides": ides,
        "skills": skills,
        "synced_at": synced_at,
    }


def load_manifest(workspace: Path) -> Optional[Dict[str, Any]]:
    path = manifest_path(workspace)
    if not path.is_file():
        return None
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Malformed setup manifest: {path}") from exc
    return validate_manifest(data)


def save_manifest(workspace: Path, data: Dict[str, Any]) -> None:
    validated = validate_manifest(data)
    path = manifest_path(workspace)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".setup.", suffix=".tmp", dir=path.parent)
    tmp = Path(tmp_path)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(validated, handle, indent=4)
            handle.write("\n")
        os.replace(tmp, path)
    except OSError:
        if tmp.exists():
            tmp.unlink()
        raise


def manifest_skill_index(manifest: Optional[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    if not manifest:
        return {}
    index: Dict[str, Dict[str, Any]] = {}
    for entry in manifest.get("skills", []):
        key = f"{entry['ide']}:{entry['name']}"
        index[key] = entry
    return index


def build_skill_record(
    *,
    name: str,
    ide: str,
    source: Path,
    destination: Path,
    materialization: str,
    existing: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    now = utc_now()
    source_checksum = tree_checksum(source)
    applied_checksum = None
    if materialization == "copy" and destination.is_dir():
        applied_checksum = tree_checksum(destination)
    created_at = existing.get("created_at", now) if existing else now
    return {
        "name": name,
        "ide": ide,
        "source": str(source.resolve()),
        "destination": str(destination.resolve()),
        "materialization": materialization,
        "source_checksum": source_checksum,
        "applied_checksum": applied_checksum,
        "created_at": created_at,
        "synced_at": now,
    }


def compose_manifest(
    *,
    workspace_name: str,
    ai_vault_root: Path,
    ides: List[str],
    skills: List[Dict[str, Any]],
) -> Dict[str, Any]:
    rules = setup_rules()
    now = utc_now()
    return {
        "version": rules.get("manifest_version", 1),
        "workspace_name": workspace_name,
        "ai_vault_root": str(ai_vault_root.resolve()),
        "ides": sorted(set(ides)),
        "skills": skills,
        "synced_at": now,
    }
