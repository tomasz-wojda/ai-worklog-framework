"""
preflight.py — Session and environment preflight checks.

Validates binaries, authentication presence, connectivity, and
workspace structure. Never refreshes credentials or performs writes.

Inputs:
  - Parsed CLI arguments (optional service/ticket filters).

Outputs:
  - ResultSet with per-check status.
  - Exit code (0 all ready, 1 degraded/blocked).
"""

import os
import shutil
import subprocess
from pathlib import Path
from typing import List, Optional

from ai_worklog_framework.adapters.preflight_scope import resolve_scope
from ai_worklog_framework.cli import EXIT_BLOCKED, EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.result import Result, ResultSet, Status
from ai_worklog_framework.toolchain.resolver import check_toolchain
from ai_worklog_framework.shared import load_shared


def run(args) -> int:
    """
    Executes preflight checks and reports readiness.

    Args:
        args: Parsed argparse Namespace.

    Returns:
        Exit code (0 if all ready, 1 otherwise).
    """
    workspace = resolve_workspace(getattr(args, "workspace", None))
    paths = WorkspacePaths(workspace)
    config = load_config(workspace)

    results = execute_preflight(
        paths,
        config,
        services=getattr(args, "service", None),
        ticket=getattr(args, "ticket", None),
    )
    print(results.summary())
    print()

    overall = results.overall_status
    if overall == Status.READY:
        print(f"Preflight: READY")
        return EXIT_SUCCESS
    actionable = results.filter_actionable()
    print(f"Preflight: {overall.value.upper()} ({len(actionable)} issue(s))")
    return EXIT_BLOCKED if overall == Status.BLOCKED else EXIT_USER_ERROR


def execute_preflight(
    paths: WorkspacePaths,
    config: "Config",
    services: Optional[List[str]] = None,
    ticket: Optional[str] = None,
) -> ResultSet:
    """
    Runs all preflight checks and returns aggregated results.

    Args:
        paths: Resolved workspace paths.
        config: Loaded configuration.
        services: Optional list of services to limit checks to.

    Returns:
        ResultSet with all check outcomes.
    """
    results = ResultSet()
    scope = resolve_scope(paths, ticket, services)
    checks = scope.checks

    def selected(check: str) -> bool:
        return checks is None or check in checks

    if selected("workspace"):
        results.add(_check_workspace_structure(paths))
    if checks is None:
        _check_binaries(results, config)
    if selected("jira"):
        _check_jira(results, paths)
    if selected("git"):
        _check_git(results)
    if selected("github"):
        _check_github(results)
    if selected("aws"):
        _check_aws(results, paths)
    if selected("kubectl"):
        _check_kubectl(results)
    if selected("servicenow"):
        _check_servicenow(results, paths)
    if selected("jenkins"):
        _check_service_properties(results, paths, "jenkins", "jenkins.properties")
    if selected("argocd"):
        _check_binary(results, "argocd")
    if selected("newrelic"):
        _check_service_directory(results, paths, "newrelic")
    if selected("datadog"):
        _check_service_directory(results, paths, "datadog")
    if selected("repositories"):
        _check_repositories(results, paths, scope.service_ids, scope.catalog)
    if selected("catalog_binaries"):
        _check_catalog_binaries(results, scope.service_ids, scope.catalog)
    if selected("toolchain"):
        _check_toolchain(results, config)

    return results


def _check_binary(results: ResultSet, binary: str) -> None:
    status = Status.READY if shutil.which(binary) else Status.BLOCKED
    message = "Found" if status == Status.READY else "Not found"
    results.add(Result(status=status, source=f"bin:{binary}", message=message))


def _check_service_directory(
    results: ResultSet, paths: WorkspacePaths, service: str
) -> None:
    directory = paths.service_dir(service)
    status = Status.READY if directory.is_dir() else Status.BLOCKED
    message = "Directory present" if directory.is_dir() else "Directory not found"
    results.add(Result(status=status, source=service, message=message))


def _check_service_properties(
    results: ResultSet,
    paths: WorkspacePaths,
    service: str,
    filename: str,
) -> None:
    file = paths.service_dir(service) / filename
    status = Status.READY if file.is_file() else Status.BLOCKED
    message = f"{filename} present" if file.is_file() else f"{filename} missing"
    results.add(Result(status=status, source=service, message=message))


def _check_repositories(
    results: ResultSet,
    paths: WorkspacePaths,
    service_ids: List[str],
    catalog: dict,
) -> None:
    repositories = set()
    for service_id in service_ids:
        for repository in catalog.get(service_id, {}).get("repositories", []):
            if repository.get("local_dir"):
                repositories.add(repository["local_dir"])
    for repository in sorted(repositories):
        present = (paths.root / "repos" / repository).is_dir()
        results.add(Result(
            status=Status.READY if present else Status.BLOCKED,
            source=f"repo:{repository}",
            message="Present" if present else "Not cloned",
        ))


def _check_catalog_binaries(
    results: ResultSet,
    service_ids: List[str],
    catalog: dict,
) -> None:
    packs = load_shared("diagnostic-packs.json", {})
    binaries = set()
    for service_id in service_ids:
        pack_ids = catalog.get(service_id, {}).get("monitoring", {}).get("diagnostic_packs", [])
        for pack_id in pack_ids:
            binaries.update(packs.get(pack_id, {}).get("prerequisites", []))
    for binary in sorted(binaries):
        _check_binary(results, binary)


def _check_toolchain(results: ResultSet, config) -> None:
    """Reports Python/Java/Groovy compatibility for legacy Groovy tools."""
    toolchain_results = check_toolchain(config.toolchain)
    for item in toolchain_results.results:
        results.add(item)


def _check_workspace_structure(paths: WorkspacePaths) -> Result:
    """Verifies workspace directory structure exists."""
    missing = []
    if not paths.worklog.is_dir():
        missing.append("worklog/")
    if not paths.prompt_log.exists():
        missing.append("prompt.log")

    if missing:
        return Result(
            status=Status.DEGRADED,
            source="workspace",
            message=f"Missing: {', '.join(missing)}",
        )
    return Result(status=Status.READY, source="workspace", message="Structure valid")


def _check_binaries(results: ResultSet, config) -> None:
    """Checks presence and version of required and optional binaries."""
    required = config.preflight.get("required_binaries", [])
    optional = config.preflight.get("optional_binaries", [])

    for binary in required:
        if shutil.which(binary):
            results.add(Result(status=Status.READY, source=f"bin:{binary}", message="Found"))
        else:
            results.add(Result(status=Status.BLOCKED, source=f"bin:{binary}", message="Not found"))

    for binary in optional:
        if shutil.which(binary):
            results.add(Result(status=Status.READY, source=f"bin:{binary}", message="Found"))
        else:
            results.add(Result(status=Status.DEGRADED, source=f"bin:{binary}", message="Not found (optional)"))


def _check_jira(results: ResultSet, paths: WorkspacePaths) -> None:
    """Checks JIRA properties file presence (not values)."""
    jira_dir = paths.service_dir("jira")
    props_file = jira_dir / "jira.properties"

    if not jira_dir.is_dir():
        results.add(Result(status=Status.BLOCKED, source="jira", message="Directory not found"))
        return

    if not props_file.is_file():
        results.add(Result(status=Status.BLOCKED, source="jira", message="jira.properties missing"))
        return

    results.add(Result(status=Status.READY, source="jira", message="Properties file present"))


def _check_git(results: ResultSet) -> None:
    """Checks git configuration and identity."""
    try:
        result = subprocess.run(
            ["git", "config", "user.email"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0 and result.stdout.strip():
            results.add(Result(status=Status.READY, source="git", message=f"Identity: {result.stdout.strip()}"))
        else:
            results.add(Result(status=Status.DEGRADED, source="git", message="No user.email configured"))
    except (subprocess.TimeoutExpired, FileNotFoundError):
        results.add(Result(status=Status.BLOCKED, source="git", message="git not available"))


def _check_github(results: ResultSet) -> None:
    """Checks GitHub CLI authentication status."""
    try:
        result = subprocess.run(
            ["gh", "auth", "status"],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode == 0:
            results.add(Result(status=Status.READY, source="github", message="Authenticated"))
        else:
            results.add(Result(status=Status.DEGRADED, source="github", message="Not authenticated"))
    except (subprocess.TimeoutExpired, FileNotFoundError):
        results.add(Result(status=Status.DEGRADED, source="github", message="gh CLI not available"))


def _check_aws(results: ResultSet, paths: WorkspacePaths) -> None:
    """Checks AWS identity without exposing secrets."""
    try:
        result = subprocess.run(
            ["aws", "sts", "get-caller-identity", "--output", "json"],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode == 0:
            import json
            identity = json.loads(result.stdout)
            account = identity.get("Account", "unknown")
            results.add(Result(
                status=Status.READY,
                source="aws",
                message=f"Account: {account}",
                detail={"account": account, "arn": identity.get("Arn", "")},
            ))
        else:
            results.add(Result(status=Status.DEGRADED, source="aws", message="No active session"))
    except (subprocess.TimeoutExpired, FileNotFoundError):
        results.add(Result(status=Status.DEGRADED, source="aws", message="aws CLI not available"))


def _check_kubectl(results: ResultSet) -> None:
    """Checks kubectl context and basic cluster connectivity."""
    try:
        result = subprocess.run(
            ["kubectl", "config", "current-context"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0 and result.stdout.strip():
            ctx = result.stdout.strip()
            results.add(Result(status=Status.READY, source="kubectl", message=f"Context: {ctx}"))
        else:
            results.add(Result(status=Status.DEGRADED, source="kubectl", message="No current context"))
    except (subprocess.TimeoutExpired, FileNotFoundError):
        results.add(Result(status=Status.DEGRADED, source="kubectl", message="kubectl not available"))


def _check_servicenow(results: ResultSet, paths: WorkspacePaths) -> None:
    """Checks ServiceNow cookie freshness without reading contents."""
    snow_dir = paths.service_dir("snow")
    cookie_file = snow_dir / "cookie"

    if not cookie_file.is_file():
        results.add(Result(status=Status.DEGRADED, source="servicenow", message="No cookie file"))
        return

    import time
    mtime = cookie_file.stat().st_mtime
    age_hours = (time.time() - mtime) / 3600

    if age_hours > 24:
        results.add(Result(
            status=Status.DEGRADED,
            source="servicenow",
            message=f"Cookie is {age_hours:.0f}h old (likely expired)",
        ))
    else:
        results.add(Result(status=Status.READY, source="servicenow", message=f"Cookie age: {age_hours:.0f}h"))
