import json
import os
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Set

PROVENANCE_VERSION = 1
PROVENANCE_RELATIVE = ".ai-worklog/created.json"


def provenance_path(workspace: Path) -> Path:
    return workspace / PROVENANCE_RELATIVE


def _relative(workspace: Path, target: Path) -> str:
    try:
        return Path(target).resolve().relative_to(workspace.resolve()).as_posix()
    except ValueError:
        return ""


def load_created(workspace: Path) -> Set[str]:
    path = provenance_path(workspace)
    if not path.is_file():
        return set()
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError):
        return set()
    if not isinstance(data, dict):
        return set()
    entries = data.get("directories")
    if not isinstance(entries, list):
        return set()
    return {entry for entry in entries if isinstance(entry, str) and entry}


def created_from_actions(workspace: Path, actions: List[Dict[str, Any]]) -> Set[str]:
    created: Set[str] = set()
    for action in actions:
        if action.get("skip") or action.get("kind") != "mkdir":
            continue
        relative = _relative(workspace, action["target"])
        if relative:
            created.add(relative)
    return created


def record_created(workspace: Path, actions: List[Dict[str, Any]]) -> None:
    created = created_from_actions(workspace, actions)
    if not created:
        return
    merged = sorted(load_created(workspace) | created)
    payload: Dict[str, Any] = {
        "version": PROVENANCE_VERSION,
        "directories": merged,
    }
    path = provenance_path(workspace)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".created.", suffix=".tmp", dir=path.parent)
    tmp = Path(tmp_path)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=4)
            handle.write("\n")
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def forget_created(workspace: Path, relatives: Set[str]) -> None:
    remaining = load_created(workspace) - relatives
    path = provenance_path(workspace)
    if not path.is_file():
        return
    payload: Dict[str, Any] = {
        "version": PROVENANCE_VERSION,
        "directories": sorted(remaining),
    }
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=4)
        handle.write("\n")
