import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.global_config import load_global_config
from ai_worklog_framework.setup.manifest import compose_manifest, load_manifest, manifest_path
from ai_worklog_framework.setup.materialize import (
    apply_skill_action,
    can_remove_skill_artifact,
    cleanup_empty_parent,
    plan_skill_materialization,
    remove_skill_artifact,
)
from ai_worklog_framework.setup.resolver import ide_materialization
from ai_worklog_framework.workspace.planner import apply_plan, plan_init, plan_revert


def plan_setup_init(
    *,
    workspace: Path,
    vault_root: Path,
    vault_manifest: Dict[str, Any],
    ides: List[str],
    adopt: bool,
) -> Dict[str, Any]:
    workspace_actions = plan_init(workspace)
    existing_manifest = load_manifest(workspace)
    skill_actions, skill_conflicts, skill_records = plan_skill_materialization(
        workspace=workspace,
        vault_root=vault_root,
        vault_manifest=vault_manifest,
        ides=ides,
        existing_manifest=existing_manifest,
        adopt=adopt,
    )
    return {
        "workspace_actions": workspace_actions["actions"],
        "skill_actions": skill_actions,
        "conflicts": skill_conflicts + workspace_actions.get("conflicts", []),
        "skill_records": skill_records,
        "existing_manifest": existing_manifest,
    }


def plan_setup_repair(
    *,
    workspace: Path,
    vault_root: Path,
    vault_manifest: Dict[str, Any],
    ides: List[str],
    adopt: bool,
) -> Dict[str, Any]:
    return plan_setup_init(
        workspace=workspace,
        vault_root=vault_root,
        vault_manifest=vault_manifest,
        ides=ides,
        adopt=adopt,
    )


def plan_setup_revert(
    *,
    workspace: Path,
    ides: Optional[List[str]] = None,
) -> Dict[str, Any]:
    existing_manifest = load_manifest(workspace)
    service_actions = plan_revert(workspace)["actions"]
    skill_actions: List[Dict[str, Any]] = []
    conflicts: List[Dict[str, Any]] = []
    remaining_skills: List[Dict[str, Any]] = []
    target_ides = set(ides) if ides else None

    if existing_manifest:
        for entry in existing_manifest.get("skills", []):
            if target_ides is not None and entry.get("ide") not in target_ides:
                remaining_skills.append(entry)
                continue
            destination = Path(str(entry["destination"]))
            managed, reason = can_remove_skill_artifact(destination, entry)
            action = {
                "kind": "remove",
                "ide": entry.get("ide"),
                "skill": entry.get("name"),
                "target": destination,
                "skip": not managed,
                "reason": reason,
            }
            if not managed and destination.exists():
                conflicts.append({"path": str(destination), "reason": reason})
            skill_actions.append(action)

    remaining_ides = sorted({entry.get("ide") for entry in remaining_skills if entry.get("ide")})
    return {
        "service_actions": service_actions,
        "skill_actions": skill_actions,
        "conflicts": conflicts,
        "remaining_skills": remaining_skills,
        "remaining_ides": remaining_ides,
        "existing_manifest": existing_manifest,
    }


def apply_init_or_repair_plan(
    *,
    workspace: Path,
    workspace_name: str,
    vault_root: Path,
    ides: List[str],
    plan: Dict[str, Any],
) -> None:
    apply_plan(plan.get("workspace_actions", []))
    for action in plan.get("skill_actions", []):
        apply_skill_action(action)

    skill_records = list(plan.get("skill_records") or [])
    for action in plan.get("skill_actions", []):
        applied = action.get("applied_checksum")
        if not applied or action.get("skip"):
            continue
        target = str(action["target"].resolve())
        for record in skill_records:
            if record["destination"] == target:
                record["applied_checksum"] = applied

    manifest = compose_manifest(
        workspace_name=workspace_name,
        ai_vault_root=vault_root,
        ides=ides,
        skills=skill_records,
    )
    from ai_worklog_framework.setup.manifest import save_manifest

    save_manifest(workspace, manifest)


def apply_revert_plan(
    *,
    workspace: Path,
    workspace_name: str,
    vault_root: Optional[Path],
    plan: Dict[str, Any],
) -> None:
    apply_plan(plan.get("service_actions", []))
    for action in plan.get("skill_actions", []):
        if action.get("skip"):
            continue
        entry = _manifest_entry(plan.get("existing_manifest"), action)
        if entry is None:
            continue
        destination = action["target"]
        remove_skill_artifact(destination, entry)
        profile = ide_materialization(str(action.get("ide")))
        cleanup_empty_parent(destination.parent, workspace / profile["destination"])

    remaining = list(plan.get("remaining_skills") or [])
    path = manifest_path(workspace)
    if remaining and vault_root is not None:
        manifest = compose_manifest(
            workspace_name=workspace_name,
            ai_vault_root=vault_root,
            ides=list(plan.get("remaining_ides") or []),
            skills=remaining,
        )
        from ai_worklog_framework.setup.manifest import save_manifest

        save_manifest(workspace, manifest)
    elif path.is_file():
        path.unlink()


def _manifest_entry(
    manifest: Optional[Dict[str, Any]],
    action: Dict[str, Any],
) -> Optional[Dict[str, Any]]:
    if not manifest:
        return None
    for entry in manifest.get("skills", []):
        if entry.get("name") == action.get("skill") and entry.get("ide") == action.get("ide"):
            return entry
    return None


def pending_action_count(plan: Dict[str, Any]) -> int:
    total = 0
    for key in ("workspace_actions", "service_actions", "skill_actions"):
        for action in plan.get(key, []):
            if not action.get("skip"):
                total += 1
    return total


ACTION_PLAN_KEYS = ("workspace_actions", "service_actions", "skill_actions")
LABEL_WIDTH = 17


def collect_plan_actions(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    actions: List[Dict[str, Any]] = []
    for key in ACTION_PLAN_KEYS:
        actions.extend(plan.get(key, []))
    return actions


def action_plan_counts(plan: Dict[str, Any]) -> tuple[int, int]:
    actions = collect_plan_actions(plan)
    skipped = sum(1 for action in actions if action.get("skip"))
    active = sum(1 for action in actions if not action.get("skip"))
    return skipped, active


def _setup_use_color() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if sys.platform == "win32":
        term = os.environ.get("TERM", "")
        wt = os.environ.get("WT_SESSION", "")
        ansicon = os.environ.get("ANSICON", "")
        conemu = os.environ.get("ConEmuANSI", "")
        if not wt and not ansicon and not conemu and term in ("", "dumb"):
            return False
    return sys.stdout.isatty() and os.environ.get("TERM") != "dumb"


def _is_utf8_console() -> bool:
    encoding = getattr(sys.stdout, "encoding", "") or ""
    return "utf-8" in encoding.lower() or os.environ.get("PYTHONIOENCODING", "").lower().startswith("utf")


def _setup_print_row(label: str, detail: str, *, ok: bool = True) -> None:
    use_color = _setup_use_color()
    is_utf8 = _is_utf8_console()
    mark_symbol = ("✓" if is_utf8 else "[OK]") if ok else ("✗" if is_utf8 else "[FAIL]")
    if use_color:
        mark = f"\033[32m{mark_symbol}\033[0m" if ok else f"\033[31m{mark_symbol}\033[0m"
        dim = "\033[2m"
        reset = "\033[0m"
    else:
        mark = mark_symbol
        dim = ""
        reset = ""
    print(f"  {mark} {label:<{LABEL_WIDTH}} {dim}{detail}{reset}")


def format_action_detail(action: Dict[str, Any]) -> str:
    kind = action.get("kind")
    target = action.get("target")
    source = action.get("source")
    reason = action.get("reason")
    suffix = f" ({reason})" if reason else ""
    if kind == "mkdir":
        detail = f"mkdir {target}"
    elif kind == "copy":
        detail = f"copy {source} -> {target}"
    elif kind == "symlink":
        if isinstance(source, Path) and not source.is_absolute():
            link_source = source
        elif hasattr(source, "resolve"):
            link_source = source.resolve()
        else:
            link_source = source
        detail = f"link {target} -> {link_source}"
    elif kind == "rmdir":
        detail = f"rmdir {target}"
    elif kind == "remove":
        detail = f"remove {target}"
    elif kind == "unlink":
        detail = f"unlink {target}"
    else:
        detail = f"{kind} {target}"
    return f"{detail}{suffix}"


def print_compact_actions(actions: List[Dict[str, Any]], apply: bool) -> None:
    skipped = [action for action in actions if action.get("skip")]
    active = [action for action in actions if not action.get("skip")]

    if skipped:
        noun = "action" if len(skipped) == 1 else "actions"
        _setup_print_row("Skipped", f"{len(skipped)} {noun}")

    for action in active:
        print(f"      {format_action_detail(action)}")

    if active and not apply:
        noun = "action" if len(active) == 1 else "actions"
        message = f"{len(active)} pending {noun}. Re-run with --apply to make changes."
        if _setup_use_color():
            print(f"\n  \033[2m{message}\033[0m")
        else:
            print(f"\n  {message}")


def print_compact_action_plan(plan: Dict[str, Any], apply: bool) -> None:
    print_compact_actions(collect_plan_actions(plan), apply)


def format_filesystem_action(action: Dict[str, Any], apply: bool) -> str:
    if action.get("skip"):
        prefix = "skipped:"
    elif apply:
        prefix = "run:"
    else:
        prefix = "would:"
    kind = action.get("kind")
    target = action.get("target")
    source = action.get("source")
    reason = action.get("reason")
    suffix = f" ({reason})" if reason else ""
    if kind == "mkdir":
        detail = f"mkdir {target}"
    elif kind == "copy":
        detail = f"copy {source} -> {target}"
    elif kind == "symlink":
        if isinstance(source, Path) and not source.is_absolute():
            link_source = source
        elif hasattr(source, "resolve"):
            link_source = source.resolve()
        else:
            link_source = source
        detail = f"link {target} -> {link_source}"
    elif kind == "rmdir":
        detail = f"rmdir {target}"
    elif kind == "remove":
        detail = f"remove {target}"
    elif kind == "unlink":
        detail = f"unlink {target}"
    else:
        detail = f"{kind} {target}"
    return f"{prefix} {detail}{suffix}"
