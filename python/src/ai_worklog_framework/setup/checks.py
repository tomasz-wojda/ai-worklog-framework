from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.adapters.preflight import execute_preflight
from ai_worklog_framework.config import load_config
from ai_worklog_framework.global_config import (
    load_global_config,
    workspace_available,
)
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.result import Status
from ai_worklog_framework.setup.manifest import load_manifest
from ai_worklog_framework.setup.materialize import plan_skill_materialization
from ai_worklog_framework.setup.resolver import ide_materialization, resolve_ai_vault_root, resolve_runtime_selection
from ai_worklog_framework.setup.vault import validate_vault_root
from ai_worklog_framework.workspace.planner import legacy_integration_status


def _check(status: Status, layer: str, message: str) -> Dict[str, Any]:
    return {"layer": layer, "status": status.value, "message": message}


def run_setup_checks(
    *,
    workspace: Path,
    workspace_name: Optional[str] = None,
    include_preflight: bool = True,
) -> List[Dict[str, Any]]:
    checks: List[Dict[str, Any]] = []

    try:
        config = load_global_config()
        checks.append(_check(Status.READY, "global", "Configuration valid"))
    except (ValueError, OSError) as exc:
        checks.append(_check(Status.ERROR, "global", str(exc)))
        return checks

    registered = False
    registered_ides: List[str] = []
    if workspace_name and workspace_name in config.get("workspaces", {}):
        registered = True
        entry = config["workspaces"][workspace_name]
        registered_ides = list(entry.get("ides") or [])
        if str(Path(entry["path"]).resolve()) != str(workspace.resolve()):
            checks.append(_check(Status.BLOCKED, "workspace", "Registered path mismatch"))
        elif workspace_available(str(workspace)):
            checks.append(_check(Status.READY, "workspace", "Registered and available"))
        else:
            checks.append(_check(Status.BLOCKED, "workspace", "Registered path unavailable"))
    elif workspace_available(str(workspace)):
        checks.append(_check(Status.DEGRADED, "workspace", "Available but not registered"))
    else:
        checks.append(_check(Status.BLOCKED, "workspace", "Workspace unavailable"))

    paths = WorkspacePaths(workspace)
    missing = []
    if not paths.worklog.is_dir():
        missing.append("worklog/")
    if not paths.config_dir.is_dir():
        missing.append(".ai-worklog/")
    if missing:
        checks.append(_check(Status.BLOCKED, "structure", f"Missing: {', '.join(missing)}"))
    else:
        checks.append(_check(Status.READY, "structure", "Workspace structure present"))

    if not paths.integrations_dir.is_dir():
        checks.append(_check(Status.DEGRADED, "integrations", "integrations/ missing"))
    else:
        checks.append(_check(Status.READY, "integrations", "Integration hub present"))

    legacy_message = legacy_integration_status(workspace)
    if legacy_message:
        checks.append(_check(Status.DEGRADED, "integrations", legacy_message))

    runtime, runtime_source, runtime_ok = resolve_runtime_selection()
    if runtime_ok:
        checks.append(_check(Status.READY, "runtime", f"{runtime} available ({runtime_source})"))
    else:
        checks.append(_check(Status.BLOCKED, "runtime", f"{runtime} unavailable ({runtime_source})"))

    vault_root, vault_source = resolve_ai_vault_root(workspace)
    vault_manifest: Dict[str, Any] = {}
    if vault_root is None:
        checks.append(_check(Status.BLOCKED, "ai_vault", "AI vault not found"))
    else:
        valid, message, vault_manifest = validate_vault_root(vault_root)
        if valid:
            checks.append(_check(Status.READY, "ai_vault", f"Valid ({vault_source})"))
            checks.append(_check(Status.READY, "vault_manifest", "Skill manifest valid"))
        else:
            checks.append(_check(Status.BLOCKED, "ai_vault", message))

    ides = registered_ides or (load_manifest(workspace) or {}).get("ides", [])
    if not ides:
        checks.append(_check(Status.BLOCKED, "ide", "No IDE profiles registered"))
    else:
        checks.append(_check(Status.READY, "ide", f"Profiles: {', '.join(ides)}"))

    setup_manifest = load_manifest(workspace)
    if vault_root and vault_manifest and ides:
        _, conflicts, _ = plan_skill_materialization(
            workspace=workspace,
            vault_root=vault_root,
            vault_manifest=vault_manifest,
            ides=ides,
            existing_manifest=setup_manifest,
            adopt=False,
        )
        if conflicts:
            checks.append(
                _check(Status.BLOCKED, "materialization", f"{len(conflicts)} conflict(s)")
            )
        else:
            checks.append(_check(Status.READY, "materialization", "Managed destinations valid"))

        stale = _stale_entries(workspace, setup_manifest, vault_root, vault_manifest, ides)
        if stale:
            checks.append(_check(Status.DEGRADED, "freshness", f"{len(stale)} stale item(s)"))
        else:
            checks.append(_check(Status.READY, "freshness", "Up to date"))

    if include_preflight:
        try:
            ws_config = load_config(workspace)
            preflight = execute_preflight(paths, ws_config)
            status = preflight.overall_status
            checks.append(
                _check(
                    status if status != Status.UNKNOWN else Status.DEGRADED,
                    "preflight",
                    preflight.summary().split("\n")[0] if preflight.results else "No checks",
                )
            )
        except Exception as exc:
            checks.append(_check(Status.ERROR, "preflight", str(exc)))

    return checks


def _stale_entries(
    workspace: Path,
    setup_manifest: Optional[Dict[str, Any]],
    vault_root: Path,
    vault_manifest: Dict[str, Any],
    ides: List[str],
) -> List[str]:
    stale: List[str] = []
    actions, _, _ = plan_skill_materialization(
        workspace=workspace,
        vault_root=vault_root,
        vault_manifest=vault_manifest,
        ides=ides,
        existing_manifest=setup_manifest,
        adopt=False,
    )
    for action in actions:
        if action.get("disposition") == "update" and not action.get("skip"):
            stale.append(str(action["target"]))
    return stale


def aggregate_check_status(checks: List[Dict[str, Any]]) -> Status:
    priority = [Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.READY]
    mapping = {item.value: item for item in Status}
    worst = Status.READY
    for check in checks:
        status = mapping.get(check["status"], Status.UNKNOWN)
        if priority.index(status) < priority.index(worst):
            worst = status
    return worst


def find_workspace_registration(workspace: Path) -> Optional[str]:
    config = load_global_config()
    target = str(workspace.resolve())
    for name, entry in config.get("workspaces", {}).items():
        if entry.get("path") == target:
            return str(name)
    return None
