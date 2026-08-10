import json
import re
import shutil
from typing import Any, Dict, List, Set
from urllib.parse import urlparse

from ai_worklog_framework.adapters.process import run_process
from ai_worklog_framework.catalog.loader import load_catalog
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status


def _repo_slugs(state: Dict[str, Any], catalog: Dict[str, Dict[str, Any]]) -> List[str]:
    slugs: Set[str] = set()
    for item in state.get("repositories", []):
        if isinstance(item, dict) and item.get("url"):
            slugs.add(item["url"])
    for service_id in state.get("services", []):
        for repo in catalog.get(service_id, {}).get("repositories", []):
            url = repo.get("url", "")
            if url:
                slugs.add(url)
    return sorted(slugs)


def _repo_from_url(url: str) -> str:
    parsed = urlparse(url)
    path = parsed.path.strip("/")
    if path.endswith(".git"):
        path = path[:-4]
    return path


def observe_github(
    paths: WorkspacePaths,
    ticket_key: str,
    state: Dict[str, Any],
    timeout: int = 15,
) -> List[Observation]:
    if not shutil.which("gh"):
        return [Observation(
            system="github",
            source="github",
            status=Status.UNKNOWN,
            message="GitHub CLI unavailable",
        )]

    catalog = load_catalog(paths)
    slugs = _repo_slugs(state, catalog)
    if not slugs:
        return [Observation(
            system="github",
            source="github",
            status=Status.UNKNOWN,
            message="No repository URLs configured",
        )]

    observations: List[Observation] = []
    search = re.sub(r"[^A-Za-z0-9._-]", "", ticket_key)
    for url in slugs:
        repo = _repo_from_url(url)
        if not repo or ".." in repo.split("/"):
            observations.append(Observation(
                system="github",
                source=f"github:{repo or 'invalid'}",
                status=Status.ERROR,
                message="Invalid repository URL",
            ))
            continue
        source = f"github:{repo}"
        code, stdout, stderr = run_process(
            [
                "gh", "pr", "list",
                "--repo", repo,
                "--search", search,
                "--state", "all",
                "--json", "number,title,state,isDraft,url,headRefName,baseRefName",
                "--limit", "50",
            ],
            timeout=timeout,
        )
        if code != 0:
            observations.append(Observation(
                system="github",
                source=source,
                status=Status.DEGRADED,
                message="GitHub query failed",
                details={"stderr": stderr},
            ))
            continue
        try:
            pulls = json.loads(stdout or "[]")
        except json.JSONDecodeError:
            observations.append(Observation(
                system="github",
                source=source,
                status=Status.ERROR,
                message="Malformed GitHub response",
            ))
            continue
        observations.append(Observation(
            system="github",
            source=source,
            status=Status.READY,
            message=f"{len(pulls)} pull request(s) found",
            details={"repository": repo, "pull_requests": pulls},
        ))
    return observations
