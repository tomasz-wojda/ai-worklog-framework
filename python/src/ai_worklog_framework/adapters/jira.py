from typing import Any, Dict, List, Optional

from ai_worklog_framework.adapters.http import bearer_headers, http_get_json
from ai_worklog_framework.adapters.process import load_properties
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status


def _jira_config(paths: WorkspacePaths) -> Dict[str, str]:
    props_file = paths.service_dir("jira") / "jira.properties"
    return load_properties(props_file)


def observe_jira(
    paths: WorkspacePaths,
    ticket_key: str,
    timeout: int = 10,
) -> List[Observation]:
    props = _jira_config(paths)
    url = props.get("jira.url", "").rstrip("/")
    token = props.get("jira.token", "")
    if not url or not token:
        return [Observation(
            system="jira",
            source="jira",
            status=Status.UNKNOWN,
            message="Jira credentials unavailable",
        )]

    api_url = (
        f"{url}/rest/api/2/issue/{ticket_key}"
        "?fields=summary,status,assignee,created,updated"
    )
    status_code, payload = http_get_json(api_url, headers=bearer_headers(token), timeout=timeout)
    if status_code == 0:
        return [Observation(
            system="jira",
            source="jira",
            status=Status.DEGRADED,
            message="Jira request failed",
        )]
    if not isinstance(payload, dict):
        return [Observation(
            system="jira",
            source="jira",
            status=Status.ERROR,
            message="Malformed Jira response",
        )]
    if status_code >= 400:
        return [Observation(
            system="jira",
            source="jira",
            status=Status.DEGRADED,
            message=f"Jira returned HTTP {status_code}",
        )]

    fields = payload.get("fields", {})
    issue_status = fields.get("status", {})
    assignee = fields.get("assignee") or {}
    details = {
        "summary": fields.get("summary", ""),
        "status": issue_status.get("name", ""),
        "status_category": (issue_status.get("statusCategory") or {}).get("key", ""),
        "assignee": assignee.get("displayName", assignee.get("name", "")),
        "created": fields.get("created", ""),
        "updated": fields.get("updated", ""),
    }
    return [Observation(
        system="jira",
        source="jira",
        status=Status.READY,
        message="Jira issue fetched",
        details=details,
    )]


def fetch_jira_user(paths: WorkspacePaths, timeout: int = 10) -> Optional[Dict[str, Any]]:
    props = _jira_config(paths)
    url = props.get("jira.url", "").rstrip("/")
    token = props.get("jira.token", "")
    if not url or not token:
        return None
    status_code, payload = http_get_json(
        f"{url}/rest/api/2/myself",
        headers=bearer_headers(token),
        timeout=timeout,
    )
    if status_code == 200 and isinstance(payload, dict):
        return payload
    return None
