"""
commands.py — Delivery CLI command handlers.

Provides read-only delivery state calculation.

Inputs:
  - Parsed CLI arguments for delivery subcommand.

Outputs:
  - Delivery status report or exit code.
"""

from datetime import datetime

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.shared import load_shared
from ai_worklog_framework.state.manager import load_state

DELIVERY_RULES = load_shared("delivery-rules.json", {})

def run(args) -> int:
    """
    Dispatches delivery subcommands.

    Args:
        args: Parsed argparse Namespace with delivery_action.

    Returns:
        Exit code.
    """
    if not args.delivery_action:
        print("Usage: ai-worklog delivery {status}")
        return EXIT_USER_ERROR

    if args.delivery_action == "status":
        workspace = resolve_workspace(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
        )
        paths = WorkspacePaths(workspace)
        return _status(paths, args.key)
    return EXIT_USER_ERROR


def _status(paths: WorkspacePaths, ticket_key: str) -> int:
    """Shows delivery lifecycle status for a ticket."""
    state = load_state(paths, ticket_key)
    data = state.data

    print(f"{'=' * 72}")
    print(f"  DELIVERY STATUS: {ticket_key}")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 72}")
    print()

    stages = [
        ("Investigation", data.get("investigation", {}).get("state", "unknown")),
        ("Implementation", data.get("implementation", {}).get("state", "unknown")),
        ("Pull Requests", _pr_summary(data.get("pull_requests", []))),
        ("Builds", _build_summary(data.get("builds", []))),
        ("GitOps", data.get("gitops", {}).get("state", "unknown")),
        ("Synchronization", data.get("synchronization", {}).get("state", "unknown")),
        ("Verification", data.get("verification", {}).get("state", "unknown")),
    ]

    for stage_name, status in stages:
        indicator = _status_indicator(status)
        print(f"  {indicator} {stage_name}: {status}")

    print()

    # Gaps
    gaps = _identify_gaps(data)
    if gaps:
        print("DELIVERY GAPS:")
        for gap in gaps:
            print(f"  - {gap}")
        print()

    print(f"{'=' * 72}")
    return EXIT_SUCCESS


def _pr_summary(prs: list) -> str:
    if not prs:
        return "none"
    merged = sum(1 for p in prs if p.get("state") == "merged")
    open_count = sum(1 for p in prs if p.get("state") == "open")
    if open_count > 0:
        return f"{open_count} open, {merged} merged"
    if merged > 0:
        return f"all {merged} merged"
    return f"{len(prs)} total"


def _build_summary(builds: list) -> str:
    if not builds:
        return "none"
    success = sum(1 for b in builds if b.get("result") == "success")
    failed = sum(1 for b in builds if b.get("result") == "failure")
    if failed > 0:
        return f"{failed} failed, {success} success"
    return f"{success} success"


def _status_indicator(status: str) -> str:
    positive = DELIVERY_RULES.get("positive_statuses", [])
    negative = DELIVERY_RULES.get("negative_statuses", [])
    if any(p in status.lower() for p in positive):
        return "[OK]"
    if any(n in status.lower() for n in negative):
        return "[!!]"
    if status in DELIVERY_RULES.get("empty_statuses", []):
        return "[--]"
    return "[..]"


def _identify_gaps(data: dict) -> list:
    gaps = []
    messages = DELIVERY_RULES.get("gap_messages", {})
    impl = data.get("implementation", {})
    if impl.get("state") == "complete" and impl.get("uncommitted"):
        gaps.append(messages.get("uncommitted", "Implementation complete but changes uncommitted"))

    prs = data.get("pull_requests", [])
    merged_prs = [p for p in prs if p.get("state") == "merged"]
    builds = data.get("builds", [])

    if merged_prs and not builds:
        gaps.append(messages.get("merged_without_build", "PRs merged but no builds recorded"))

    if builds and data.get("gitops", {}).get("state") == "not_applicable":
        gaps.append(messages.get("build_without_gitops", "Builds exist but GitOps state not tracked"))

    sync = data.get("synchronization", {})
    if sync.get("state") == "forced_sync_required":
        gaps.append(messages.get("forced_sync", "ArgoCD requires manual forced sync"))

    if data.get("verification", {}).get("state") == "not_started" and merged_prs:
        gaps.append(messages.get("unverified_merge", "PRs merged but no live verification recorded"))

    return gaps
