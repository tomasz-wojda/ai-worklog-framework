import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from ai_worklog_framework.shared import framework_root, load_shared
from ai_worklog_framework.workspace.provenance import load_created, record_created


def _create_symlink_or_junction(target: Path, source: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        target.symlink_to(source, target_is_directory=True)
    except OSError as exc:
        if sys.platform == "win32":
            source_dir = (target.parent / source).resolve()
            cmd = f'cmd.exe /c mklink /J "{target.resolve()}" "{source_dir}"'
            res = subprocess.run(cmd, shell=True, capture_output=True)
            if res.returncode != 0:
                raise exc
        else:
            raise exc


def _workspace_layout() -> Tuple[Path, List[str]]:
    rules = load_shared("workspace-init.json", {})
    integrations_path = rules.get("integrations_path", "integrations")
    services = list(rules.get("services", []))
    return Path(integrations_path), services


def _foreign_integration_reason(path: Path) -> str:
    if path.is_symlink():
        return "foreign symlink"
    if path.is_dir():
        return "foreign directory"
    return "foreign file"


def plan_init(workspace: Path) -> Dict[str, Any]:
    rules = load_shared("workspace-init.json", {})
    integrations_rel, services = _workspace_layout()
    integrations = workspace / integrations_rel
    actions: List[Dict[str, Any]] = []
    conflicts: List[Dict[str, Any]] = []

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

    for service in services:
        canonical = integrations / service
        if canonical.is_symlink():
            actions.append({
                "kind": "mkdir",
                "target": canonical,
                "skip": True,
                "reason": "already linked",
            })
        elif canonical.is_dir():
            actions.append({
                "kind": "mkdir",
                "target": canonical,
                "skip": True,
                "reason": "already exists",
            })
        elif canonical.exists():
            conflicts.append({
                "path": str(canonical),
                "reason": _foreign_integration_reason(canonical),
            })
            actions.append({
                "kind": "mkdir",
                "target": canonical,
                "skip": True,
                "reason": _foreign_integration_reason(canonical),
            })
        else:
            actions.append({
                "kind": "mkdir",
                "target": canonical,
                "skip": False,
                "reason": "",
            })

    return {"actions": actions, "conflicts": conflicts}


def plan_revert(workspace: Path) -> Dict[str, Any]:
    integrations_rel, services = _workspace_layout()
    integrations = workspace / integrations_rel
    created = load_created(workspace)
    actions: List[Dict[str, Any]] = []

    for service in services:
        canonical = integrations / service
        reason = _retain_reason(canonical, (integrations_rel / service).as_posix(), created)
        actions.append({
            "kind": "rmdir",
            "target": canonical,
            "skip": bool(reason),
            "reason": reason,
        })

    return {"actions": actions, "conflicts": []}


def _retain_reason(canonical: Path, relative: str, created: Set[str]) -> str:
    if canonical.is_symlink():
        return "not created by the framework"
    if not canonical.is_dir():
        return "not present"
    if relative not in created:
        return "not created by the framework"
    if any(canonical.iterdir()):
        return "not empty"
    return ""


def legacy_integration_status(workspace: Path) -> Optional[str]:
    return None


def apply_plan(actions: List[Dict[str, Any]], workspace: Optional[Path] = None) -> None:
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
            _create_symlink_or_junction(target, action["source"])
        elif action["kind"] in ("unlink", "delete"):
            if target.exists() or target.is_symlink():
                target.unlink()
        elif action["kind"] == "rmdir":
            if target.is_dir() and not any(target.iterdir()):
                target.rmdir()

    if workspace is not None:
        record_created(workspace, actions)


def format_action(action: Dict[str, Any], apply: bool) -> str:
    if action["skip"]:
        return f"skipped: {action['target']} ({action['reason']})"
    prefix = "run:" if apply else "would:"
    kind = action["kind"]
    if kind == "mkdir":
        detail = f"mkdir {action['target']}"
    elif kind == "copy":
        detail = f"copy {action['source']} -> {action['target']}"
    elif kind == "symlink":
        detail = f"link {action['target']} -> {action['source']}"
    elif kind == "rmdir":
        detail = f"rmdir {action['target']}"
    elif kind == "delete":
        detail = f"delete {action['target']}"
    else:
        detail = f"unlink {action['target']}"
    return f"{prefix} {detail}"
