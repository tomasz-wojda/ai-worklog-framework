from datetime import date, datetime
from typing import Any, Dict, List, Optional
from urllib.parse import quote

from ai_worklog_framework.adapters.http import bearer_headers, http_get_json
from ai_worklog_framework.adapters.jira import fetch_jira_user, observe_jira
from ai_worklog_framework.adapters.process import load_properties
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status


def _jira_config(paths: WorkspacePaths) -> Dict[str, str]:
    return load_properties(paths.service_dir("jira") / "jira.properties")


def _parse_created(value: str) -> date:
    if not value:
        return date.today()
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).date()
    except ValueError:
        return date.today()


def observe_tempo(
    paths: WorkspacePaths,
    ticket_key: str,
    state: Dict[str, Any],
    timeout: int = 10,
    api_path: str = "/rest/tempo-timesheets/3/worklogs",
) -> List[Observation]:
    props = _jira_config(paths)
    url = props.get("jira.url", "").rstrip("/")
    token = props.get("jira.token", "")
    if not url or not token:
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.UNKNOWN,
            message="Tempo credentials unavailable",
        )]

    user = fetch_jira_user(paths, timeout=timeout)
    if not user:
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.UNKNOWN,
            message="Current Jira user unavailable",
        )]

    created = _parse_created(state.get("created_at", ""))
    jira_observations = observe_jira(paths, ticket_key, timeout=timeout)
    for observation in jira_observations:
        if observation.details and observation.details.get("created"):
            created = _parse_created(observation.details["created"])
            break

    username = quote(user.get("name", user.get("key", "")))
    date_from = created.isoformat()
    date_to = date.today().isoformat()
    api_url = (
        f"{url}{api_path}"
        f"?dateFrom={date_from}&dateTo={date_to}&username={username}"
    )
    status_code, payload = http_get_json(api_url, headers=bearer_headers(token), timeout=timeout)
    if status_code == 0:
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.DEGRADED,
            message="Tempo request failed",
        )]
    if payload is None:
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.ERROR,
            message="Malformed Tempo response",
        )]
    if status_code >= 400:
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.DEGRADED,
            message=f"Tempo returned HTTP {status_code}",
        )]

    if not isinstance(payload, list):
        return [Observation(
            system="tempo",
            source="tempo",
            status=Status.ERROR,
            message="Malformed Tempo response",
        )]
    entries = payload
    total_seconds = 0
    matched = 0
    for entry in entries:
        issue_key = (entry.get("issue") or {}).get("key", "")
        if issue_key != ticket_key:
            continue
        matched += 1
        total_seconds += int(entry.get("timeSpentSeconds") or 0)

    return [Observation(
        system="tempo",
        source="tempo",
        status=Status.READY,
        message=f"{matched} Tempo worklog(s) for ticket",
        details={
            "total_seconds": total_seconds,
            "entry_count": matched,
            "username": user.get("displayName", user.get("name", "")),
        },
    )]
