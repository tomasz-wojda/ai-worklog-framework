import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ai_worklog_framework.shared import framework_root, load_shared


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


def _workspace_layout() -> Tuple[Path, Path, List[str]]:
    rules = load_shared("workspace-init.json", {})
    integrations_path = rules.get("integrations_path", "integrations")
    legacy_path = rules.get("legacy_interface_path", "worklog/interface")
    services = list(rules.get("services", []))
    return Path(integrations_path), Path(legacy_path), services


def _managed_target(service: str, *, legacy: bool = False) -> Path:
    return (Path("../..") if legacy else Path("..")) / service


def _is_managed_link(path: Path, service: str, *, legacy: bool = False) -> bool:
    expected = _managed_target(service, legacy=legacy)
    if not path.is_symlink():
        if sys.platform == "win32" and path.is_dir():
            expected_source = (path.parent / expected).resolve()
            try:
                return path.resolve() == expected_source
            except Exception:
                return False
        return False
    try:
        read = Path(os.readlink(path))
        if read == expected:
            return True
        return (path.parent / read).resolve() == (path.parent / expected).resolve()
    except Exception:
        return False


def _path_present(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _foreign_integration_reason(path: Path) -> str:
    if path.is_symlink():
        return "foreign symlink"
    if path.is_dir():
        return "foreign directory"
    return "foreign file"


def plan_init(workspace: Path) -> Dict[str, Any]:
    rules = load_shared("workspace-init.json", {})
    integrations_rel, legacy_rel, services = _workspace_layout()
    integrations = workspace / integrations_rel
    legacy_interface = workspace / legacy_rel
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
        root_source = workspace / service
        canonical = integrations / service
        legacy = legacy_interface / service
        canonical_managed = _is_managed_link(canonical, service)
        legacy_managed = _is_managed_link(legacy, service, legacy=True)
        canonical_directory = canonical.is_dir() and not canonical.is_symlink()
        canonical_present = _path_present(canonical)
        root_ready = root_source.is_dir() and not root_source.is_symlink()

        if canonical_present and not canonical_managed and not canonical_directory:
            conflicts.append({
                "path": str(canonical),
                "reason": _foreign_integration_reason(canonical),
            })
            actions.append({
                "kind": "symlink",
                "source": _managed_target(service),
                "target": canonical,
                "skip": True,
                "reason": _foreign_integration_reason(canonical),
            })
        elif canonical_managed or canonical_directory:
            actions.append({
                "kind": "symlink",
                "source": _managed_target(service),
                "target": canonical,
                "skip": True,
                "reason": "already linked" if canonical_managed else "integration present",
            })
        elif root_ready:
            actions.append({
                "kind": "symlink",
                "source": _managed_target(service),
                "target": canonical,
                "skip": False,
                "reason": "",
            })
        else:
            actions.append({
                "kind": "symlink",
                "source": _managed_target(service),
                "target": canonical,
                "skip": True,
                "reason": "source absent",
            })

        if legacy_managed:
            can_remove_legacy = canonical_managed or canonical_directory or (
                root_ready and not (
                    canonical_present and not canonical_managed and not canonical_directory
                )
            )
            actions.append({
                "kind": "unlink",
                "target": legacy,
                "skip": not can_remove_legacy,
                "reason": "" if can_remove_legacy else "canonical integration unavailable",
            })

    _append_directory_cleanup(actions, legacy_interface)
    return {"actions": actions, "conflicts": conflicts}


def plan_revert(workspace: Path) -> Dict[str, Any]:
    integrations_rel, legacy_rel, services = _workspace_layout()
    integrations = workspace / integrations_rel
    legacy_interface = workspace / legacy_rel
    actions: List[Dict[str, Any]] = []

    for service in services:
        canonical = integrations / service
        actions.append({
            "kind": "unlink",
            "target": canonical,
            "skip": not _is_managed_link(canonical, service),
            "reason": "not a managed link" if not _is_managed_link(canonical, service) else "",
        })
        legacy = legacy_interface / service
        actions.append({
            "kind": "unlink",
            "target": legacy,
            "skip": not _is_managed_link(legacy, service, legacy=True),
            "reason": "not a managed link" if not _is_managed_link(legacy, service, legacy=True) else "",
        })

    _append_directory_cleanup(actions, integrations)
    _append_directory_cleanup(actions, legacy_interface)
    return {"actions": actions, "conflicts": []}


def legacy_integration_status(workspace: Path) -> Optional[str]:
    _, legacy_rel, services = _workspace_layout()
    legacy_interface = workspace / legacy_rel
    if not legacy_interface.is_dir():
        return None
    remaining = list(legacy_interface.iterdir())
    if not remaining:
        return None
    unmanaged = [
        entry for entry in remaining
        if entry.name not in services or not _is_managed_link(
            entry, entry.name, legacy=True
        )
    ]
    if unmanaged:
        count = len(unmanaged)
        noun = "item" if count == 1 else "items"
        return (
            f"Legacy integration hub contains {count} unmanaged {noun}; "
            "move them to integrations/"
        )
    count = len(remaining)
    noun = "item" if count == 1 else "items"
    return f"Legacy integration hub retains {count} {noun}; run setup repair --apply"


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
            _create_symlink_or_junction(target, action["source"])
        elif action["kind"] == "unlink":
            target.unlink()
        elif action["kind"] == "rmdir":
            if target.is_dir() and not any(target.iterdir()):
                target.rmdir()


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
    else:
        detail = f"unlink {action['target']}"
    return f"{prefix} {detail}"


def _append_directory_cleanup(
    actions: List[Dict[str, Any]],
    directory: Path,
) -> None:
    if not directory.is_dir():
        actions.append({
            "kind": "rmdir",
            "target": directory,
            "skip": True,
            "reason": "not present",
        })
        return
    planned_unlinks = {
        action["target"]
        for action in actions
        if action["kind"] == "unlink" and not action["skip"]
    }
    remaining = [
        entry for entry in directory.iterdir()
        if entry not in planned_unlinks
    ]
    actions.append({
        "kind": "rmdir",
        "target": directory,
        "skip": bool(remaining),
        "reason": "not empty" if remaining else "",
    })
