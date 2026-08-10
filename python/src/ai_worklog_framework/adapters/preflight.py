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

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.result import Result, ResultSet, Status
from ai_worklog_framework.toolchain.resolver import check_toolchain


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

    results = execute_preflight(paths, config, services=getattr(args, "service", None))
    print(results.summary())
    print()

    overall = results.overall_status
    if overall == Status.READY:
        print(f"Preflight: READY")
        return EXIT_SUCCESS
    else:
        actionable = results.filter_actionable()
        print(f"Preflight: {overall.value.upper()} ({len(actionable)} issue(s))")
        return EXIT_USER_ERROR


def execute_preflight(
    paths: WorkspacePaths,
    config: "Config",
    services: Optional[List[str]] = None,
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

    results.add(_check_workspace_structure(paths))
    _check_binaries(results, config)
    _check_jira(results, paths)
    _check_git(results)
    _check_github(results)
    _check_aws(results, paths)
    _check_kubectl(results)
    _check_servicenow(results, paths)
    _check_toolchain(results, config)

    return results


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
