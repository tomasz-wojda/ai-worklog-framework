import os
import shutil
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ai_worklog_framework.setup.manifest import build_skill_record, manifest_skill_index, tree_checksum
from ai_worklog_framework.setup.resolver import ide_materialization, setup_rules
from ai_worklog_framework.setup.vault import skills_for_ide


def _symlink_target(path: Path) -> Optional[Path]:
    if not path.is_symlink():
        return None
    try:
        return path.resolve()
    except OSError:
        return None


def inspect_destination(
    destination: Path,
    source: Path,
    materialization: str,
    manifest_entry: Optional[Dict[str, Any]],
    adopt: bool,
) -> Tuple[str, str]:
    if not destination.exists() and not destination.is_symlink():
        return "create", ""
    if materialization == "symlink":
        if destination.is_symlink():
            target = _symlink_target(destination)
            if target == source.resolve():
                if manifest_entry:
                    return "skip", "already linked"
                return "adopt", "matching unmanaged link"
            if manifest_entry and target is not None and str(target) == manifest_entry.get("source"):
                return "update", "stale symlink"
            return "conflict", "foreign symlink"
        if destination.is_dir() or destination.is_file():
            return "conflict", "foreign file or directory"
        return "create", ""
    if destination.is_symlink():
        return "conflict", "foreign symlink"
    if not destination.is_dir():
        return "conflict", "foreign file"
    current_checksum = tree_checksum(destination)
    if manifest_entry:
        applied = manifest_entry.get("applied_checksum")
        if applied and current_checksum != applied:
            return "conflict", "modified copy"
        if applied and current_checksum == applied:
            source_checksum = tree_checksum(source)
            if source_checksum != manifest_entry.get("source_checksum"):
                return "update", "source changed"
            return "skip", "copy current"
    return "create", ""


def plan_skill_materialization(
    *,
    workspace: Path,
    vault_root: Path,
    vault_manifest: Dict[str, Any],
    ides: List[str],
    existing_manifest: Optional[Dict[str, Any]],
    adopt: bool,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    rules = setup_rules()
    skills_dir = vault_root / rules.get("vault_skills_dir", "skills")
    skill_index = manifest_skill_index(existing_manifest)
    actions: List[Dict[str, Any]] = []
    conflicts: List[Dict[str, Any]] = []
    skill_records: List[Dict[str, Any]] = []

    for ide in sorted(ides):
        profile = ide_materialization(ide)
        destination_root = workspace / profile["destination"]
        materialization = profile["materialization"]
        for skill in skills_for_ide(vault_manifest, ide):
            source = skills_dir / skill["dir"]
            destination = destination_root / skill["name"]
            key = f"{ide}:{skill['name']}"
            manifest_entry = skill_index.get(key)
            disposition, reason = inspect_destination(
                destination,
                source,
                materialization,
                manifest_entry,
                adopt,
            )
            action = {
                "kind": materialization,
                "ide": ide,
                "skill": skill["name"],
                "source": source,
                "target": destination,
                "skip": disposition in ("skip", "adopt"),
                "reason": reason,
                "disposition": disposition,
            }
            if disposition == "conflict":
                conflicts.append({"path": str(destination), "reason": reason})
                action["skip"] = True
                actions.append(action)
                continue
            actions.append(action)
            if disposition in ("skip", "create", "update", "adopt"):
                record = build_skill_record(
                    name=skill["name"],
                    ide=ide,
                    source=source,
                    destination=destination,
                    materialization=materialization,
                    existing=manifest_entry,
                )
                if disposition == "skip" and manifest_entry:
                    record = dict(manifest_entry)
                    record["source_checksum"] = tree_checksum(source)
                skill_records.append(record)

    return actions, conflicts, skill_records


def apply_skill_action(action: Dict[str, Any]) -> None:
    if action.get("skip"):
        return
    disposition = action.get("disposition")
    if disposition == "conflict":
        return
    target = action["target"]
    source = action["source"]
    kind = action["kind"]
    target.parent.mkdir(parents=True, exist_ok=True)
    if kind == "symlink":
        if target.is_symlink() or target.exists():
            target.unlink()
        target.symlink_to(source.resolve())
        return
    if kind == "copy":
        if target.exists():
            if target.is_symlink():
                target.unlink()
            else:
                shutil.rmtree(target)
        shutil.copytree(source, target, symlinks=False)
        action["applied_checksum"] = tree_checksum(target)


def can_remove_skill_artifact(
    destination: Path,
    manifest_entry: Dict[str, Any],
) -> Tuple[bool, str]:
    if not destination.exists() and not destination.is_symlink():
        return True, "already absent"
    materialization = manifest_entry.get("materialization")
    if materialization == "symlink":
        if not destination.is_symlink():
            return False, "not a managed symlink"
        target = _symlink_target(destination)
        expected = Path(str(manifest_entry.get("source"))).resolve()
        if target != expected:
            return False, "symlink target mismatch"
        return True, ""
    if destination.is_symlink():
        return False, "foreign symlink"
    if not destination.is_dir():
        return False, "not a managed copy"
    applied = manifest_entry.get("applied_checksum")
    if applied:
        current_checksum = tree_checksum(destination)
        if current_checksum != applied:
            return False, "modified copy"
    return True, ""


def remove_skill_artifact(
    destination: Path,
    manifest_entry: Dict[str, Any],
) -> Tuple[bool, str]:
    ok, reason = can_remove_skill_artifact(destination, manifest_entry)
    if not ok:
        return False, reason
    if destination.is_symlink():
        destination.unlink()
        return True, ""
    if destination.is_dir():
        shutil.rmtree(destination)
    return True, reason


def cleanup_empty_parent(path: Path, stop_at: Path) -> None:
    current = path
    while current != stop_at and current.is_dir():
        try:
            next(current.iterdir())
            break
        except StopIteration:
            pass
        parent = current.parent
        current.rmdir()
        current = parent
