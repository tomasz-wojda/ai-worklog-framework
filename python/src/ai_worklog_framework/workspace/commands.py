from pathlib import Path

from ai_worklog_framework.cli import (
    EXIT_BLOCKED,
    EXIT_SUCCESS,
    EXIT_SYSTEM_ERROR,
    EXIT_USER_ERROR,
)
from ai_worklog_framework.global_config import (
    add_workspace,
    canonical_workspace_path,
    current_workspace,
    list_workspaces,
    print_json,
    remove_workspace,
    set_default_workspace,
    show_default_workspace,
    show_workspace,
)
from ai_worklog_framework.workspace.planner import (
    apply_plan,
    format_action,
    plan_init,
    plan_revert,
)


def _selector(args, key: str):
    if not hasattr(args, key):
        return None
    return getattr(args, key)


def _selectors(args):
    return _selector(args, "workspace"), _selector(args, "workspace_name")


def _availability_suffix(entry: dict) -> str:
    return " [available]" if entry.get("available") else " [missing]"


def _default_suffix(entry: dict) -> str:
    return " [default]" if entry.get("default") else ""


def _render_human(payload: dict) -> None:
    operation = payload.get("operation")
    if operation == "add":
        print(f"Registered workspace {payload['name']}: {payload['path']}")
        if payload.get("default"):
            print(f"Default workspace: {payload['name']}")
        if payload.get("unchanged"):
            print("No changes required.")
    elif operation == "list":
        workspaces = payload.get("workspaces", [])
        print(f"Registered workspaces ({len(workspaces)}):")
        if not workspaces:
            print("  none")
        else:
            for entry in workspaces:
                print(
                    f"  {entry['name']}  {entry['path']}"
                    f"{_availability_suffix(entry)}{_default_suffix(entry)}"
                )
    elif operation == "show":
        print(
            f"Workspace {payload['name']}: {payload['path']}"
            f"{_availability_suffix(payload)}{_default_suffix(payload)}"
        )
    elif operation == "default":
        print(
            f"Default workspace: {payload['name']}"
            if payload.get("name")
            else "Default workspace: none"
        )
    elif operation == "current":
        print(f"Workspace: {payload['path']}")
        print(f"Source: {payload['source']}")
        if payload.get("name"):
            print(f"Name: {payload['name']}")
    elif operation == "remove":
        print(f"Removed workspace registration: {payload['name']}")


def _render(payload: dict, json: bool) -> int:
    if json:
        print_json(payload)
    else:
        _render_human(payload)
    return EXIT_SUCCESS


def _handle_error(action: str, json: bool, exc: ValueError) -> int:
    if json:
        print_json({"operation": action, "status": "error", "message": str(exc)})
    else:
        print(str(exc))
    return EXIT_USER_ERROR


def _run_init_revert(args) -> int:
    target = canonical_workspace_path(args.path)
    if not target.is_dir():
        print(f"Workspace not found: {args.path}")
        return EXIT_USER_ERROR

    actions = plan_init(target) if args.workspace_action == "init" else plan_revert(target)
    applying = bool(args.apply)
    for conflict in actions.get("conflicts", []):
        print(f"conflict: {conflict['path']} ({conflict['reason']})")
    for action in actions["actions"]:
        print(format_action(action, applying))

    if not applying:
        print("Dry run only. Re-run with --apply to make changes.")
        return EXIT_SUCCESS

    if actions.get("conflicts"):
        return EXIT_BLOCKED

    try:
        apply_plan(actions["actions"])
    except OSError as exc:
        print(f"Workspace operation failed: {exc}")
        return EXIT_SYSTEM_ERROR
    print("Workspace operation complete.")
    return EXIT_SUCCESS


def run(args) -> int:
    action = args.workspace_action
    if action in ("init", "revert"):
        return _run_init_revert(args)
    json = bool(getattr(args, "json", False))
    try:
        if action == "add":
            payload = add_workspace(args.name, args.path, make_default=bool(args.default))
        elif action == "list":
            payload = list_workspaces()
        elif action == "show":
            payload = show_workspace(args.name)
        elif action == "default":
            payload = (
                set_default_workspace(args.name)
                if args.name is not None
                else show_default_workspace()
            )
        elif action == "current":
            explicit_path, explicit_name = _selectors(args)
            payload = current_workspace(explicit_path, explicit_name)
        elif action == "remove":
            payload = remove_workspace(args.name)
        else:
            print(
                "Usage: ai-worklog workspace "
                "{init|revert|add|list|show|default|current|remove} ..."
            )
            return EXIT_USER_ERROR
        return _render(payload, json)
    except ValueError as exc:
        return _handle_error(action, json, exc)
    except OSError as exc:
        print(f"Config write failed: {exc}")
        return EXIT_SYSTEM_ERROR
