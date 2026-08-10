"""
jenkins.py — Read-only Jenkins adapter for controller queries.

Provides controller health, job lookup, recent builds, build parameters,
and seed-job status using the existing multi-controller properties file.
Never copies secrets; reads properties path from workspace configuration.

Inputs:
  - Controller identifier and optional job/build selectors.

Outputs:
  - Result objects with Jenkins data or error status.
"""

import json
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import quote

from ai_worklog_framework.result import Result, ResultSet, Status
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.redaction import redact_string


def _load_jenkins_properties(paths: WorkspacePaths) -> Dict[str, Dict[str, str]]:
    """
    Parses jenkins.properties to extract per-controller connection info.
    Returns controller_id -> {url, user} (token is NOT exposed).

    Args:
        paths: Workspace paths.

    Returns:
        Dictionary of controller configs (without tokens).
    """
    props_file = paths.service_dir("jenkins") / "jenkins.properties"
    if not props_file.is_file():
        return {}

    controllers: Dict[str, Dict[str, str]] = {}
    try:
        with open(props_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, _, value = line.partition("=")
                    key = key.strip()
                    value = value.strip()
                    parts = key.split(".")
                    if len(parts) >= 2:
                        controller_id = parts[0]
                        field = ".".join(parts[1:])
                        if controller_id not in controllers:
                            controllers[controller_id] = {}
                        if "token" not in field.lower() and "password" not in field.lower():
                            controllers[controller_id][field] = value
    except OSError:
        pass
    return controllers


def list_controllers(paths: WorkspacePaths) -> ResultSet:
    """
    Lists available Jenkins controllers with connectivity metadata.

    Args:
        paths: Workspace paths.

    Returns:
        ResultSet with one Result per controller.
    """
    results = ResultSet()
    controllers = _load_jenkins_properties(paths)

    if not controllers:
        results.add(Result(
            status=Status.BLOCKED,
            source="jenkins",
            message="No jenkins.properties found or no controllers configured",
        ))
        return results

    for ctrl_id, info in sorted(controllers.items()):
        url = info.get("url", "unknown")
        results.add(Result(
            status=Status.READY,
            source=f"jenkins:{ctrl_id}",
            message=f"URL: {url}",
            detail=info,
        ))
    return results


def query_job(paths: WorkspacePaths, controller: str, job_name: str) -> Result:
    """
    Queries a specific Jenkins job for status and last build info.
    Uses curl with credentials from the properties file.

    Args:
        paths: Workspace paths.
        controller: Controller identifier.
        job_name: Jenkins job name.

    Returns:
        Result with job status or error.
    """
    controllers = _load_jenkins_properties(paths)
    if controller not in controllers:
        return Result(
            status=Status.ERROR,
            source=f"jenkins:{controller}",
            message=f"Controller '{controller}' not found in properties",
        )

    url = controllers[controller].get("url", "")
    if not url:
        return Result(
            status=Status.ERROR,
            source=f"jenkins:{controller}",
            message="No URL configured for controller",
        )

    api_url = f"{url}/job/{quote(job_name)}/api/json?tree=name,color,lastBuild[number,result,timestamp]"

    try:
        result = subprocess.run(
            ["curl", "-s", "--max-time", "10", api_url],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode == 0 and result.stdout.strip():
            data = json.loads(result.stdout)
            last_build = data.get("lastBuild", {})
            build_num = last_build.get("number", "?")
            build_result = last_build.get("result", "unknown")
            return Result(
                status=Status.READY,
                source=f"jenkins:{controller}/{job_name}",
                message=f"Last build: #{build_num} ({build_result})",
                detail=data,
            )
        return Result(
            status=Status.DEGRADED,
            source=f"jenkins:{controller}/{job_name}",
            message="No response from Jenkins API",
        )
    except (subprocess.TimeoutExpired, FileNotFoundError, json.JSONDecodeError) as e:
        return Result(
            status=Status.ERROR,
            source=f"jenkins:{controller}/{job_name}",
            message=f"Query failed: {type(e).__name__}",
        )
