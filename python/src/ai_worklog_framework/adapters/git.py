from typing import Any, Dict, List, Set

from ai_worklog_framework.adapters.process import run_process
from ai_worklog_framework.catalog.loader import load_catalog
from ai_worklog_framework.paths import SAFE_COMPONENT, WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status


def _validate_repo_name(name: str) -> str:
    if not SAFE_COMPONENT.fullmatch(name):
        raise ValueError(f"Invalid repository name: {name}")
    return name


def resolve_repositories(
    paths: WorkspacePaths,
    state: Dict[str, Any],
) -> List[str]:
    catalog = load_catalog(paths)
    repos: Set[str] = set()
    for item in state.get("repositories", []):
        if isinstance(item, str):
            repos.add(item)
        elif isinstance(item, dict) and item.get("local_dir"):
            repos.add(item["local_dir"])
    for service_id in state.get("services", []):
        for repo in catalog.get(service_id, {}).get("repositories", []):
            if repo.get("local_dir"):
                repos.add(repo["local_dir"])
    return sorted(_validate_repo_name(name) for name in repos)


def observe_git(
    paths: WorkspacePaths,
    state: Dict[str, Any],
    timeout: int = 15,
    repositories_root: str = "repos",
) -> List[Observation]:
    observations: List[Observation] = []
    try:
        repositories = resolve_repositories(paths, state)
    except ValueError as exc:
        return [Observation(
            system="git",
            source="git",
            status=Status.ERROR,
            message=str(exc),
        )]

    if not repositories:
        observations.append(Observation(
            system="git",
            source="git",
            status=Status.UNKNOWN,
            message="No repositories configured in ticket state",
        ))
        return observations

    workspace_root = paths.root.resolve()
    allowed_root = (workspace_root / repositories_root).resolve()
    if workspace_root not in allowed_root.parents and allowed_root != workspace_root:
        return [Observation(
            system="git",
            source="git",
            status=Status.ERROR,
            message="Repository root outside workspace",
        )]

    for repo_name in repositories:
        source = f"git:{repo_name}"
        repo_path = (allowed_root / repo_name).resolve()
        if allowed_root not in repo_path.parents and repo_path != allowed_root:
            observations.append(Observation(
                system="git",
                source=source,
                status=Status.ERROR,
                message="Repository path outside workspace repos root",
            ))
            continue
        if not repo_path.is_dir():
            observations.append(Observation(
                system="git",
                source=source,
                status=Status.DEGRADED,
                message="Repository not cloned",
                details={"local_dir": repo_name, "present": False},
            ))
            continue

        branch_code, branch_out, branch_err = run_process(
            ["git", "-C", str(repo_path), "rev-parse", "--abbrev-ref", "HEAD"],
            timeout=timeout,
        )
        head_code, head_out, _ = run_process(
            ["git", "-C", str(repo_path), "rev-parse", "HEAD"],
            timeout=timeout,
        )
        upstream_code, upstream_out, _ = run_process(
            ["git", "-C", str(repo_path), "rev-parse", "@{upstream}"],
            timeout=timeout,
        )
        status_code, status_out, _ = run_process(
            ["git", "-C", str(repo_path), "status", "--porcelain"],
            timeout=timeout,
        )
        if branch_code != 0 or head_code != 0:
            observations.append(Observation(
                system="git",
                source=source,
                status=Status.ERROR,
                message="Git query failed",
                details={"stderr": branch_err},
            ))
            continue

        dirty = bool(status_out.strip())
        ahead = 0
        if upstream_code == 0 and upstream_out.strip():
            count_code, count_out, _ = run_process(
                [
                    "git", "-C", str(repo_path), "rev-list",
                    "--count", f"{upstream_out.strip()}..HEAD",
                ],
                timeout=timeout,
            )
            if count_code == 0 and count_out.strip().isdigit():
                ahead = int(count_out.strip())

        observations.append(Observation(
            system="git",
            source=source,
            status=Status.READY,
            message="Repository inspected",
            details={
                "local_dir": repo_name,
                "present": True,
                "branch": branch_out.strip(),
                "head": head_out.strip(),
                "upstream": upstream_out.strip() if upstream_code == 0 else "",
                "dirty": dirty,
                "ahead_of_upstream": ahead,
            },
        ))
    return observations
