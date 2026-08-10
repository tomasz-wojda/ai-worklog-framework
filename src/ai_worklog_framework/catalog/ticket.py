"""
ticket.py — Ticket preparation report generation.

Combines JIRA metadata, service catalog matches, repository presence,
worklog discovery, PR discovery, and environment readiness into a
structured preparation report for ticket pickup.

Inputs:
  - JIRA ticket key.
  - Workspace paths and catalog data.

Outputs:
  - Preparation report printed to stdout.
  - Exit code (0 success, 1 user error).
"""

import json
import glob
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR, EXIT_SYSTEM_ERROR
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.catalog.loader import load_catalog, find_services_for_ticket
from ai_worklog_framework.result import Result, ResultSet, Status


def run(args) -> int:
    """
    Dispatches ticket subcommands.

    Args:
        args: Parsed argparse Namespace with ticket_action set.

    Returns:
        Exit code integer.
    """
    if not args.ticket_action:
        print("Usage: ai-worklog ticket {prepare}")
        return EXIT_USER_ERROR

    if args.ticket_action == "prepare":
        workspace = resolve_workspace(getattr(args, "workspace", None))
        return prepare(workspace, args.key)
    return EXIT_USER_ERROR


def prepare(workspace: Path, ticket_key: str) -> int:
    """
    Generates a preparation report for a ticket.

    Args:
        workspace: Resolved workspace root.
        ticket_key: JIRA ticket key (e.g., 'PROJ-1234').

    Returns:
        Exit code integer.
    """
    paths = WorkspacePaths(workspace)
    catalog = load_catalog(paths)
    report = PreparationReport(ticket_key, paths, catalog)
    report.gather()
    report.render()
    return EXIT_SUCCESS


class PreparationReport:
    """
    Assembles a ticket preparation report from multiple data sources.

    Attributes:
        ticket_key: JIRA ticket key.
        paths: Workspace paths.
        catalog: Loaded service catalog.
    """

    def __init__(self, ticket_key: str, paths: WorkspacePaths, catalog: Dict[str, Dict[str, Any]]):
        self.ticket_key = ticket_key
        self.paths = paths
        self.catalog = catalog
        self.jira_data: Optional[Dict[str, Any]] = None
        self.matched_services: List[str] = []
        self.existing_worklogs: List[Path] = []
        self.archived_worklogs: List[Path] = []
        self.repo_status: Dict[str, str] = {}
        self.open_prs: List[Dict[str, str]] = []
        self.readiness = ResultSet()

    def gather(self) -> None:
        """Collects all preparation data from available sources."""
        self._find_worklogs()
        self._match_services()
        self._check_repositories()
        self._find_prs()
        self._assess_readiness()

    def _find_worklogs(self) -> None:
        """Discovers active and archived worklogs for this ticket."""
        if self.paths.worklog.is_dir():
            for f in self.paths.worklog.glob(f"*_{self.ticket_key}*.log"):
                self.existing_worklogs.append(f)

        if self.paths.worklog_done.is_dir():
            for f in self.paths.worklog_done.glob(f"*_{self.ticket_key}*.log"):
                self.archived_worklogs.append(f)

    def _match_services(self) -> None:
        """Identifies catalog services related to this ticket."""
        project = self.ticket_key.rsplit("-", 1)[0] if "-" in self.ticket_key else None
        self.matched_services = find_services_for_ticket(
            self.catalog,
            jira_project=project,
        )

    def _check_repositories(self) -> None:
        """Checks local presence of repositories related to matched services."""
        repos_dir = self.paths.root / "repos"
        for svc_id in self.matched_services:
            entry = self.catalog.get(svc_id, {})
            for repo in entry.get("repositories", []):
                local_dir = repo.get("local_dir", "")
                if not local_dir:
                    continue
                repo_path = repos_dir / local_dir
                if repo_path.is_dir():
                    self.repo_status[local_dir] = "present"
                else:
                    self.repo_status[local_dir] = "NOT CLONED"

    def _find_prs(self) -> None:
        """Discovers open PRs mentioning this ticket key via gh CLI."""
        try:
            result = subprocess.run(
                ["gh", "pr", "list", "--search", self.ticket_key, "--state", "open", "--json", "number,title,url,repository"],
                capture_output=True, text=True, timeout=15
            )
            if result.returncode == 0 and result.stdout.strip():
                prs = json.loads(result.stdout)
                for pr in prs:
                    self.open_prs.append({
                        "number": str(pr.get("number", "")),
                        "title": pr.get("title", ""),
                        "url": pr.get("url", ""),
                    })
        except (subprocess.TimeoutExpired, FileNotFoundError, json.JSONDecodeError):
            pass

    def _assess_readiness(self) -> None:
        """Runs lightweight readiness checks for matched services."""
        if not self.matched_services:
            self.readiness.add(Result(
                status=Status.UNKNOWN,
                source="catalog",
                message="No catalog services matched this ticket"
            ))
            return

        missing_repos = [k for k, v in self.repo_status.items() if v == "NOT CLONED"]
        if missing_repos:
            self.readiness.add(Result(
                status=Status.DEGRADED,
                source="repositories",
                message=f"Not cloned: {', '.join(missing_repos)}"
            ))
        else:
            self.readiness.add(Result(
                status=Status.READY,
                source="repositories",
                message="All referenced repositories present"
            ))

    def render(self) -> None:
        """Prints the preparation report to stdout."""
        print(f"{'=' * 72}")
        print(f"  PREPARATION REPORT: {self.ticket_key}")
        print(f"  Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
        print(f"{'=' * 72}")
        print()

        # Worklogs
        print("WORKLOG HISTORY:")
        if self.existing_worklogs:
            print("  Active:")
            for wl in sorted(self.existing_worklogs):
                print(f"    - {wl.name}")
        if self.archived_worklogs:
            print("  Archived (in done/):")
            for wl in sorted(self.archived_worklogs):
                print(f"    - {wl.name}")
        if not self.existing_worklogs and not self.archived_worklogs:
            print("  No previous worklogs found.")
        print()

        # Service matches
        print("MATCHED SERVICES:")
        if self.matched_services:
            for svc_id in self.matched_services:
                entry = self.catalog.get(svc_id, {})
                print(f"  - {svc_id}: {entry.get('name', 'unnamed')} ({entry.get('type', '?')})")
        else:
            print("  No catalog matches. Manual service identification required.")
        print()

        # Repositories
        if self.repo_status:
            print("REPOSITORIES:")
            for repo, status in sorted(self.repo_status.items()):
                indicator = "[OK]" if status == "present" else "[MISSING]"
                print(f"  {indicator} {repo}")
            print()

        # Open PRs
        if self.open_prs:
            print("OPEN PULL REQUESTS:")
            for pr in self.open_prs:
                print(f"  #{pr['number']}: {pr['title']}")
                if pr.get("url"):
                    print(f"         {pr['url']}")
            print()

        # Delivery path
        print("DELIVERY PATH:")
        if self.matched_services:
            for svc_id in self.matched_services:
                entry = self.catalog.get(svc_id, {})
                delivery = entry.get("delivery_path", [])
                if delivery:
                    print(f"  {svc_id}: {' → '.join(delivery)}")
        if not any(self.catalog.get(s, {}).get("delivery_path") for s in self.matched_services):
            print("  Not defined in catalog. Manual identification required.")
        print()

        # Readiness
        print("READINESS:")
        print(self.readiness.summary())
        print()

        # Gaps
        print("PREPARATION GAPS:")
        gaps = []
        if not self.matched_services:
            gaps.append("- No catalog service match; add entry or identify service manually")
        missing_repos = [k for k, v in self.repo_status.items() if v == "NOT CLONED"]
        if missing_repos:
            gaps.append(f"- Missing repos: {', '.join(missing_repos)}")
        if not gaps:
            gaps.append("- None identified")
        for gap in gaps:
            print(f"  {gap}")
        print()
        print(f"{'=' * 72}")
