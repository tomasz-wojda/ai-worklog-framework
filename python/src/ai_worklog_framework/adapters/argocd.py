import json
import shutil
from typing import Any, Dict, List, Set
from urllib.parse import quote

from ai_worklog_framework.adapters.process import run_process
from ai_worklog_framework.catalog.loader import load_catalog
from ai_worklog_framework.paths import SAFE_COMPONENT, WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status


def _validate_app_name(name: str) -> str:
    if not SAFE_COMPONENT.fullmatch(name):
        raise ValueError(f"Invalid ArgoCD application name: {name}")
    return name


def _application_names(state: Dict[str, Any], catalog: Dict[str, Dict[str, Any]]) -> List[str]:
    apps: Set[str] = set()
    stored = state.get("synchronization", {}).get("argocd_app")
    if stored:
        apps.add(stored)
    for service_id in state.get("services", []):
        for app in catalog.get(service_id, {}).get("argocd", {}).get("applications", []):
            name = app.get("name")
            if name:
                apps.add(name)
    return sorted(_validate_app_name(name) for name in apps)


def observe_argocd(
    paths: WorkspacePaths,
    state: Dict[str, Any],
    timeout: int = 15,
) -> List[Observation]:
    if not shutil.which("argocd"):
        return [Observation(
            system="argocd",
            source="argocd",
            status=Status.UNKNOWN,
            message="ArgoCD CLI unavailable",
        )]

    catalog = load_catalog(paths)
    try:
        applications = _application_names(state, catalog)
    except ValueError as exc:
        return [Observation(
            system="argocd",
            source="argocd",
            status=Status.ERROR,
            message=str(exc),
        )]

    if not applications:
        stored_state = state.get("synchronization", {}).get("state", "unknown")
        if stored_state != "unknown":
            return [Observation(
                system="argocd",
                source="argocd",
                status=Status.UNKNOWN,
                message="Synchronization tracked but no ArgoCD application configured",
            )]
        return [Observation(
            system="argocd",
            source="argocd",
            status=Status.UNKNOWN,
            message="No ArgoCD applications configured",
        )]

    observations: List[Observation] = []
    for app_name in applications:
        source = f"argocd:{app_name}"
        code, stdout, stderr = run_process(
            ["argocd", "app", "get", app_name, "-o", "json"],
            timeout=timeout,
        )
        if code != 0:
            observations.append(Observation(
                system="argocd",
                source=source,
                status=Status.DEGRADED,
                message="ArgoCD query failed",
                details={"stderr": stderr},
            ))
            continue
        try:
            payload = json.loads(stdout or "{}")
        except json.JSONDecodeError:
            observations.append(Observation(
                system="argocd",
                source=source,
                status=Status.ERROR,
                message="Malformed ArgoCD response",
            ))
            continue
        status = payload.get("status", {})
        sync = status.get("sync", {})
        health = status.get("health", {})
        history = status.get("history") or []
        live_revision = ""
        if history:
            live_revision = history[0].get("revision", "")
        observations.append(Observation(
            system="argocd",
            source=source,
            status=Status.READY,
            message="ArgoCD application fetched",
            details={
                "application": app_name,
                "sync_status": sync.get("status", ""),
                "health_status": health.get("status", ""),
                "revision": sync.get("revision", live_revision),
            },
        ))
    return observations
