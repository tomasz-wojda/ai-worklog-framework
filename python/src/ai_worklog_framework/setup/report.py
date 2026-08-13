import json
from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.global_config import load_global_config, print_json
from ai_worklog_framework.result import Status
from ai_worklog_framework.setup.checks import aggregate_check_status, run_setup_checks
from ai_worklog_framework.setup.manifest import load_manifest
from ai_worklog_framework.setup.planner import (
    _setup_print_row,
    pending_action_count,
    print_compact_action_plan,
    print_compact_actions,
)
from ai_worklog_framework.setup.resolver import ide_materialization, resolve_ai_vault_root, resolve_runtime_selection
from ai_worklog_framework.setup.vault import validate_vault_root


def _manifest_summary(manifest: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    summary: Dict[str, Any] = {"skill_count": len(manifest.get("skills", [])) if manifest else 0}
    if manifest:
        summary["version"] = manifest["version"]
        summary["synced_at"] = manifest["synced_at"]
    return summary


def build_show_report(
    *,
    workspace: Path,
    workspace_name: Optional[str],
    registered: bool,
    is_default: bool,
) -> Dict[str, Any]:
    runtime, runtime_source, runtime_ok = resolve_runtime_selection()
    vault_root, vault_source = resolve_ai_vault_root(workspace)
    vault_valid = False
    if vault_root is not None:
        vault_valid, _, _ = validate_vault_root(vault_root)

    manifest = load_manifest(workspace)
    ides = []
    if workspace_name:
        config = load_global_config()
        entry = config.get("workspaces", {}).get(workspace_name)
        if entry:
            ides = list(entry.get("ides") or [])
    if not ides and manifest:
        ides = list(manifest.get("ides") or [])

    ide_profiles = []
    conflicts = []
    for ide in ides:
        profile = ide_materialization(ide)
        managed_count = 0
        conflict_count = 0
        if manifest:
            managed_count = sum(1 for item in manifest.get("skills", []) if item.get("ide") == ide)
        ide_profiles.append(
            {
                "id": ide,
                "materialization": profile["materialization"],
                "managed_count": managed_count,
                "conflict_count": conflict_count,
            }
        )

    pending = 0
    if vault_root and ides:
        valid, _, vault_manifest = validate_vault_root(vault_root)
        if valid:
            from ai_worklog_framework.setup.materialize import plan_skill_materialization

            _, conflicts, _ = plan_skill_materialization(
                workspace=workspace,
                vault_root=vault_root,
                vault_manifest=vault_manifest,
                ides=ides,
                existing_manifest=manifest,
                adopt=False,
            )
            pending = len(conflicts)
            for profile in ide_profiles:
                if profile["id"] in ides:
                    profile["conflict_count"] = sum(
                        1 for item in conflicts if item["path"].startswith(
                            str(workspace / ide_materialization(profile["id"])["destination"])
                        )
                    )

    return {
        "operation": "show",
        "status": Status.READY.value,
        "message": "Setup summary",
        "workspace": {
            "name": workspace_name,
            "path": str(workspace.resolve()),
            "default": is_default,
            "registered": registered,
            "available": workspace.is_dir(),
        },
        "runtime": {
            "value": runtime,
            "source": runtime_source,
            "available": runtime_ok,
        },
        "ai_vault": {
            "path": str(vault_root) if vault_root else None,
            "source": vault_source,
            "valid": vault_valid,
        },
        "ides": ide_profiles,
        "conflicts": conflicts,
        "pending_actions": pending,
        "manifest": _manifest_summary(manifest),
    }


def build_check_report(
    *,
    workspace: Path,
    workspace_name: Optional[str],
    registered: bool,
    is_default: bool,
) -> Dict[str, Any]:
    checks = run_setup_checks(
        workspace=workspace,
        workspace_name=workspace_name,
        include_preflight=True,
    )
    status = aggregate_check_status(checks)
    runtime, runtime_source, runtime_ok = resolve_runtime_selection()
    vault_root, vault_source = resolve_ai_vault_root(workspace)
    vault_valid = False
    if vault_root is not None:
        vault_valid, _, _ = validate_vault_root(vault_root)

    manifest = load_manifest(workspace)
    ides = []
    if workspace_name:
        config = load_global_config()
        entry = config.get("workspaces", {}).get(workspace_name)
        if entry:
            ides = list(entry.get("ides") or [])

    return {
        "operation": "check",
        "status": status.value,
        "message": f"Setup check: {status.value}",
        "workspace": {
            "name": workspace_name,
            "path": str(workspace.resolve()),
            "default": is_default,
            "registered": registered,
            "available": workspace.is_dir(),
        },
        "runtime": {
            "value": runtime,
            "source": runtime_source,
            "available": runtime_ok,
        },
        "ai_vault": {
            "path": str(vault_root) if vault_root else None,
            "source": vault_source,
            "valid": vault_valid,
        },
        "ides": [{"id": ide, **ide_materialization(ide), "managed_count": 0, "conflict_count": 0} for ide in ides],
        "checks": checks,
        "conflicts": [],
        "pending_actions": 0,
        "manifest": _manifest_summary(manifest),
    }


def finalize_applied_action_report(report: Dict[str, Any]) -> None:
    actions = report.get("actions") or []
    applied = sum(1 for action in actions if not action.get("skip"))
    skipped = sum(1 for action in actions if action.get("skip"))
    report["applied_actions"] = applied
    report["skipped_actions"] = skipped
    report["pending_actions"] = 0


_ACTION_OPERATIONS = frozenset({"init", "repair", "revert"})


def _render_action_footer(report: Dict[str, Any]) -> None:
    from ai_worklog_framework.setup.planner import _is_utf8_console, _setup_use_color

    applied = report.get("applied_actions") or 0
    skipped = report.get("skipped_actions")
    if skipped is None:
        actions = report.get("actions") or []
        skipped = sum(1 for action in actions if action.get("skip"))
    status = str(report.get("status", "ready")).upper()
    if _setup_use_color():
        if status == "READY":
            color = "\033[32m"
        elif status in {"BLOCKED", "ERROR"}:
            color = "\033[31m"
        else:
            color = "\033[33m"
        reset = "\033[0m"
    else:
        color = ""
        reset = ""
    dot = "·" if _is_utf8_console() else "-"
    print(f"\n{color}{status}{reset}  {applied} applied {dot} {skipped} skipped")


def build_action_report(
    *,
    operation: str,
    workspace: Path,
    workspace_name: Optional[str],
    plan: Dict[str, Any],
    runtime: str,
    runtime_source: str,
    vault_root: Optional[Path],
    vault_source: Optional[str],
    ides: List[str],
    apply: bool,
) -> Dict[str, Any]:
    actions: List[Dict[str, Any]] = []
    for key in ("workspace_actions", "service_actions", "skill_actions"):
        for action in plan.get(key, []):
            actions.append(
                {
                    "kind": action.get("kind"),
                    "target": str(action.get("target")),
                    "source": str(action.get("source")) if action.get("source") else None,
                    "skip": bool(action.get("skip")),
                    "reason": action.get("reason", ""),
                }
            )

    conflicts = list(plan.get("conflicts") or [])
    pending = pending_action_count(plan)
    if conflicts and operation in ("init", "repair"):
        status = Status.BLOCKED
    elif pending and not apply:
        status = Status.DEGRADED
    elif pending and apply:
        status = Status.READY
    else:
        status = Status.READY

    return {
        "operation": operation,
        "status": status.value,
        "message": f"Setup {operation} {'applied' if apply else 'planned'}",
        "workspace": {
            "name": workspace_name,
            "path": str(workspace.resolve()),
            "default": False,
            "registered": True,
            "available": workspace.is_dir(),
        },
        "runtime": {
            "value": runtime,
            "source": runtime_source,
            "available": True,
        },
        "ai_vault": {
            "path": str(vault_root) if vault_root else None,
            "source": vault_source,
            "valid": vault_root is not None,
        },
        "ides": [{"id": ide, **ide_materialization(ide), "managed_count": 0, "conflict_count": 0} for ide in ides],
        "actions": actions,
        "conflicts": conflicts,
        "pending_actions": pending,
        "applied_actions": 0,
        "skipped_actions": sum(1 for action in actions if action.get("skip")),
    }


def render_report(report: Dict[str, Any], json_output: bool, *, actions_printed: bool = False) -> None:
    if json_output:
        print_json(report)
        return
    print(f"Setup {report['operation']}: {report['status']}")
    workspace = report.get("workspace") or {}
    if workspace:
        label = workspace.get("name") or workspace.get("path")
        print(f"  Workspace: {label}")
    runtime = report.get("runtime") or {}
    if runtime:
        print(f"  Runtime: {runtime.get('value')} ({runtime.get('source')})")
    vault = report.get("ai_vault") or {}
    if vault.get("path"):
        print(f"  AI vault: {vault.get('path')} ({vault.get('source')})")
    for check in report.get("checks") or []:
        print(f"  [{check['status'].upper()}] {check['layer']}: {check['message']}")
    operation = report.get("operation")
    if operation in _ACTION_OPERATIONS and actions_printed:
        for conflict in report.get("conflicts") or []:
            _setup_print_row("Conflict", f"{conflict['path']} ({conflict['reason']})", ok=False)
        _render_action_footer(report)
        return
    if operation in _ACTION_OPERATIONS:
        print_compact_actions(report.get("actions") or [], apply=False)
        for conflict in report.get("conflicts") or []:
            _setup_print_row("Conflict", f"{conflict['path']} ({conflict['reason']})", ok=False)
        return
    for conflict in report.get("conflicts") or []:
        print(f"  conflict: {conflict['path']} ({conflict['reason']})")
    pending_actions = report.get("pending_actions") or 0
    if pending_actions:
        print(f"  Pending actions: {pending_actions}")


def exit_code_for_report(report: Dict[str, Any]) -> int:
    from ai_worklog_framework.cli import (
        EXIT_BLOCKED,
        EXIT_SUCCESS,
        EXIT_SYSTEM_ERROR,
        EXIT_USER_ERROR,
    )

    status = report.get("status")
    operation = report.get("operation")
    if status == Status.ERROR.value:
        return EXIT_SYSTEM_ERROR
    if status == Status.BLOCKED.value:
        return EXIT_BLOCKED
    if status == Status.DEGRADED.value:
        return EXIT_USER_ERROR if operation == "check" else EXIT_SUCCESS
    return EXIT_SUCCESS
