import json
from datetime import datetime

from ai_worklog_framework.cli import (
    EXIT_BLOCKED,
    EXIT_SUCCESS,
    EXIT_SYSTEM_ERROR,
    EXIT_USER_ERROR,
)
from ai_worklog_framework.paths import WorkspacePaths, resolve_workspace
from ai_worklog_framework.redaction import redact_dict
from ai_worklog_framework.reconciliation.engine import SYSTEMS, reconcile_ticket, workspace_rules
from ai_worklog_framework.result import Status


def run(args) -> int:
    if not args.reconcile_action:
        print("Usage: ai-worklog reconcile {status}")
        return EXIT_USER_ERROR
    if args.reconcile_action != "status":
        return EXIT_USER_ERROR

    workspace = resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    )
    paths = WorkspacePaths(workspace)
    rules = workspace_rules(paths)
    selected = args.system or rules.get("systems", list(SYSTEMS))
    invalid = [item for item in selected if item not in SYSTEMS]
    if invalid:
        print(f"Invalid system(s): {', '.join(invalid)}")
        return EXIT_USER_ERROR
    state_file = paths.ticket_state_file(args.key)
    if not state_file.is_file():
        print(f"State not found: {args.key}")
        return EXIT_USER_ERROR

    try:
        report = reconcile_ticket(
            paths,
            args.key,
            sorted(set(selected)),
        )
    except ValueError as exc:
        print(str(exc))
        return EXIT_USER_ERROR
    except Exception as exc:
        print(f"Reconciliation failed: {exc}")
        return EXIT_SYSTEM_ERROR

    if args.json:
        print(json.dumps(redact_dict(report.to_dict()), indent=2, ensure_ascii=False))
    else:
        _render_human(report)

    if any(item.status == Status.ERROR for item in report.observations):
        return EXIT_SYSTEM_ERROR
    blocking = {Status.BLOCKED.value, "blocked"}
    if any(item.severity.value in blocking for item in report.contradictions):
        return EXIT_BLOCKED
    return EXIT_SUCCESS


def _render_human(report) -> None:
    print(f"{'=' * 72}")
    print(f"  RECONCILIATION: {report.ticket_key}")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 72}")
    print()
    print("OBSERVATIONS:")
    indicators = {
        Status.READY: "[OK]",
        Status.DEGRADED: "[DEGRADED]",
        Status.BLOCKED: "[BLOCKED]",
        Status.ERROR: "[ERROR]",
        Status.UNKNOWN: "[?]",
    }
    if report.observations:
        for item in report.observations:
            indicator = indicators.get(item.status, "[?]")
            print(f"  {indicator} {item.source}: {item.message}")
    else:
        print("  (none)")
    print()
    print("CONTRADICTIONS:")
    if report.contradictions:
        for item in report.contradictions:
            indicator = indicators.get(item.severity, "[?]")
            print(f"  {indicator} {item.source}: {item.code} - {item.message}")
    else:
        print("  (none)")
    print()
    print(f"OVERALL: {report.overall_status.value}")
    print(f"{'=' * 72}")
