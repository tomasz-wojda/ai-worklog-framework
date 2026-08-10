"""
closeout.py — Close-out report and handover generation.

Combines ticket state, PR list, builds, verification evidence,
Tempo status, manual actions, and open risks into paste-ready reports.

Inputs:
  - Ticket key.

Outputs:
  - Close-out report printed to stdout.
"""

from datetime import datetime
from typing import Dict, Any

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.state.manager import load_state


def run(args) -> int:
    """
    Dispatches closeout subcommands.

    Args:
        args: Parsed argparse Namespace with closeout_action.

    Returns:
        Exit code.
    """
    if not args.closeout_action:
        print("Usage: ai-worklog closeout {report}")
        return EXIT_USER_ERROR

    if args.closeout_action == "report":
        workspace = resolve_workspace(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
        )
        paths = WorkspacePaths(workspace)
        return _report(paths, args.key)
    return EXIT_USER_ERROR


def _report(paths: WorkspacePaths, ticket_key: str) -> int:
    """Generates a close-out report for a ticket."""
    state = load_state(paths, ticket_key)
    data = state.data

    print(f"{'=' * 72}")
    print(f"  CLOSE-OUT REPORT: {ticket_key}")
    print(f"  Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 72}")
    print()

    # Summary
    if data.get("summary"):
        print(f"SUMMARY: {data['summary']}")
        print()

    # PRs
    prs = data.get("pull_requests", [])
    if prs:
        print(f"PULL REQUESTS ({len(prs)}):")
        for pr in prs:
            state_icon = {"merged": "[MERGED]", "open": "[OPEN]", "closed": "[CLOSED]"}.get(pr.get("state", ""), "[?]")
            print(f"  {state_icon} {pr.get('repo', '?')} #{pr.get('number', '?')}: {pr.get('url', '')}")
        print()

    # Builds
    builds = data.get("builds", [])
    if builds:
        print(f"BUILDS ({len(builds)}):")
        for build in builds:
            result_icon = {"success": "[OK]", "failure": "[FAIL]"}.get(build.get("result", ""), "[?]")
            print(f"  {result_icon} {build.get('job', '?')} #{build.get('number', '?')} → {build.get('artifact', 'n/a')}")
        print()

    # Synchronization
    sync = data.get("synchronization", {})
    if sync.get("state") != "unknown":
        print(f"SYNCHRONIZATION: {sync.get('state', 'unknown')}")
        if sync.get("manual_actions"):
            print("  Manual actions:")
            for action in sync["manual_actions"]:
                print(f"    - {action}")
        print()

    # Verification
    verif = data.get("verification", {})
    checks = verif.get("checks", [])
    if checks:
        print(f"VERIFICATION ({len(checks)} checks):")
        for check in checks:
            icon = "[PASS]" if check.get("passed") else "[FAIL]"
            print(f"  {icon} {check.get('name', '?')} ({check.get('timestamp', '')})")
        print()

    # Closeout status
    closeout = data.get("closeout", {})
    print("CLOSE-OUT STATUS:")
    print(f"  Implementation complete: {'Yes' if closeout.get('implementation_complete') else 'No'}")
    print(f"  Deployment complete:     {'Yes' if closeout.get('deployment_complete') else 'No'}")
    print(f"  Tempo logged:            {'Yes' if closeout.get('tempo_logged') else 'No'}")
    if closeout.get("tempo_seconds"):
        hours = closeout["tempo_seconds"] / 3600
        print(f"  Tempo total:             {hours:.1f}h")
    print(f"  Worklog archived:        {'Yes' if closeout.get('worklog_archived') else 'No'}")
    print(f"  Handover generated:      {'Yes' if closeout.get('handover_generated') else 'No'}")
    print()

    # Unresolved
    open_decisions = [d for d in data.get("decisions", []) if d.get("status") == "open"]
    active_blockers = [b for b in data.get("blockers", []) if b.get("status") == "active"]
    if open_decisions or active_blockers:
        print("UNRESOLVED ITEMS:")
        for d in open_decisions:
            print(f"  [DECISION] {d.get('id', '?')}: {d.get('description', '')}")
        for b in active_blockers:
            print(f"  [BLOCKER] {b.get('description', '')}")
        print()

    # Worklog archive eligibility
    eligible = []
    if paths.worklog.is_dir():
        for f in paths.worklog.glob(f"*_{ticket_key}*.log"):
            eligible.append(f.name)
    if eligible:
        print("ARCHIVABLE WORKLOGS:")
        for name in sorted(eligible):
            print(f"  - worklog/{name} → worklog/done/{name}")
        print()

    print(f"{'=' * 72}")
    return EXIT_SUCCESS
