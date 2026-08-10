"""
daily.py — Day start and day end report generation.

Day Start: board priority, readiness, stale blockers, active local work,
PRs, Tempo, and open monitoring signals.
Day End: time gaps, delivery state, uncommitted work, and continuation capsule.

Inputs:
  - Parsed CLI args with day_action.

Outputs:
  - Reports printed to stdout.
  - Exit code.
"""

from datetime import datetime
from pathlib import Path
from typing import List

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.state.manager import list_active_tickets, load_state, TicketState
from ai_worklog_framework.catalog.loader import load_catalog
from ai_worklog_framework.result import ResultSet, Status


def run(args) -> int:
    """
    Dispatches day subcommands.

    Args:
        args: Parsed argparse Namespace with day_action.

    Returns:
        Exit code.
    """
    if not args.day_action:
        print("Usage: ai-worklog day {start|end}")
        return EXIT_USER_ERROR

    workspace = resolve_workspace(getattr(args, "workspace", None))
    paths = WorkspacePaths(workspace)

    if args.day_action == "start":
        return _day_start(paths)
    elif args.day_action == "end":
        return _day_end(paths)
    return EXIT_USER_ERROR


def _day_start(paths: WorkspacePaths) -> int:
    """Generates day start report."""
    print(f"{'=' * 72}")
    print(f"  DAY START REPORT")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 72}")
    print()

    tickets = list_active_tickets(paths)
    if tickets:
        print(f"ACTIVE TICKETS ({len(tickets)}):")
        for key in tickets:
            state = load_state(paths, key)
            mode = state.get("governance_mode", "?")
            next_action = state.get("next_action", "")
            active_blockers = [b for b in state.get("blockers", []) if b.get("status") == "active"]
            status_parts = [f"mode={mode}"]
            if active_blockers:
                status_parts.append(f"BLOCKED({len(active_blockers)})")
            if state.get("implementation", {}).get("uncommitted"):
                status_parts.append("UNCOMMITTED")
            print(f"  {key}: {', '.join(status_parts)}")
            if next_action:
                print(f"    next: {next_action}")
        print()
    else:
        print("No active ticket state files found.")
        print()

    worklogs = list(paths.worklog.glob("*.log")) if paths.worklog.is_dir() else []
    worklogs = [w for w in worklogs if w.name != "tickets.log"]
    if worklogs:
        print(f"ACTIVE WORKLOGS ({len(worklogs)}):")
        for wl in sorted(worklogs):
            print(f"  - {wl.name}")
        print()

    print("RECOMMENDATIONS:")
    print("  - Run 'ai-worklog preflight' to verify environment readiness")
    print("  - Run Jira summary to check board state")
    print("  - Review Tempo for today's logged hours")
    print()
    print(f"{'=' * 72}")
    return EXIT_SUCCESS


def _day_end(paths: WorkspacePaths) -> int:
    """Generates day end summary and continuation capsule."""
    print(f"{'=' * 72}")
    print(f"  DAY END SUMMARY")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 72}")
    print()

    tickets = list_active_tickets(paths)

    uncommitted = []
    open_blockers = []
    continuation = []

    for key in tickets:
        state = load_state(paths, key)
        if state.get("implementation", {}).get("uncommitted"):
            uncommitted.append(key)
        for b in state.get("blockers", []):
            if b.get("status") == "active":
                open_blockers.append((key, b.get("description", "")))
        next_action = state.get("next_action", "")
        if next_action:
            continuation.append((key, next_action))

    if uncommitted:
        print("UNCOMMITTED WORK:")
        for key in uncommitted:
            print(f"  - {key}")
        print()

    if open_blockers:
        print("UNRESOLVED BLOCKERS:")
        for key, desc in open_blockers:
            print(f"  - [{key}] {desc}")
        print()

    print("CONTINUATION CAPSULE:")
    if continuation:
        for key, action in continuation:
            print(f"  {key}: {action}")
    else:
        print("  No explicit next actions recorded.")
    print()

    print("REMINDERS:")
    print("  - Verify Tempo hours are logged for today")
    print("  - Check for open PRs requiring review")
    print("  - Archive completed worklogs to done/")
    print()
    print(f"{'=' * 72}")
    return EXIT_SUCCESS
