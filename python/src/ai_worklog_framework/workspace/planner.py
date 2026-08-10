import os
import shutil
from pathlib import Path
from typing import Any, Dict, List

from ai_worklog_framework.shared import framework_root, load_shared


def plan_init(workspace: Path) -> List[Dict[str, Any]]:
    rules = load_shared("workspace-init.json", {})
    actions: List[Dict[str, Any]] = []
    for relative in rules.get("directories", []):
        target = workspace / relative
        actions.append({
            "kind": "mkdir",
            "target": target,
            "skip": target.is_dir(),
            "reason": "already exists" if target.is_dir() else "",
        })

    for managed_file in rules.get("files", []):
        target = workspace / managed_file["target"]
        actions.append({
            "kind": "copy",
            "source": framework_root() / managed_file["source"],
            "target": target,
            "skip": target.exists(),
            "reason": "already exists" if target.exists() else "",
        })

    interface = workspace / rules["interface_path"]
    for service in rules.get("services", []):
        source = workspace / service
        target = interface / service
        skip = not source.is_dir() or source.is_symlink() or target.exists() or target.is_symlink()
        if not source.is_dir():
            reason = "source absent"
        elif source.is_symlink():
            reason = "source is symlink"
        elif target.exists() or target.is_symlink():
            reason = "target exists"
        else:
            reason = ""
        actions.append({
            "kind": "symlink",
            "source": Path("../..") / service,
            "target": target,
            "skip": skip,
            "reason": reason,
        })
    return actions


def plan_revert(workspace: Path) -> List[Dict[str, Any]]:
    rules = load_shared("workspace-init.json", {})
    interface = workspace / rules["interface_path"]
    actions: List[Dict[str, Any]] = []
    for service in rules.get("services", []):
        target = interface / service
        managed = target.is_symlink() and os.readlink(target) == str(Path("../..") / service)
        actions.append({
            "kind": "unlink",
            "target": target,
            "skip": not managed,
            "reason": "not a managed link" if not managed else "",
        })
    return actions


def apply_plan(actions: List[Dict[str, Any]]) -> None:
    for action in actions:
        if action["skip"]:
            continue
        target = action["target"]
        if action["kind"] == "mkdir":
            target.mkdir(parents=True, exist_ok=True)
        elif action["kind"] == "copy":
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(action["source"], target)
        elif action["kind"] == "symlink":
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(action["source"])
        elif action["kind"] == "unlink":
            target.unlink()


def format_action(action: Dict[str, Any], apply: bool) -> str:
    if action["skip"]:
        return f"skipped: {action['target']} ({action['reason']})"
    prefix = "run:" if apply else "would:"
    if action["kind"] == "mkdir":
        detail = f"mkdir {action['target']}"
    elif action["kind"] == "copy":
        detail = f"copy {action['source']} -> {action['target']}"
    elif action["kind"] == "symlink":
        detail = f"link {action['target']} -> {action['source']}"
    else:
        detail = f"unlink {action['target']}"
    return f"{prefix} {detail}"
