from pathlib import Path

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_SYSTEM_ERROR, EXIT_USER_ERROR
from ai_worklog_framework.workspace.planner import (
    apply_plan,
    format_action,
    plan_init,
    plan_revert,
)


def run(args) -> int:
    target = Path(args.path).expanduser().resolve()
    if not target.is_dir():
        print(f"Workspace not found: {target}")
        return EXIT_USER_ERROR

    actions = plan_init(target) if args.workspace_action == "init" else plan_revert(target)
    applying = bool(args.apply)
    for action in actions:
        print(format_action(action, applying))

    if not applying:
        print("Dry run only. Re-run with --apply to make changes.")
        return EXIT_SUCCESS

    try:
        apply_plan(actions)
    except OSError as exc:
        print(f"Workspace operation failed: {exc}")
        return EXIT_SYSTEM_ERROR
    print("Workspace operation complete.")
    return EXIT_SUCCESS
