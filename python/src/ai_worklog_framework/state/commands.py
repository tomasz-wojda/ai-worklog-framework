import json
from pathlib import Path
from typing import Any, Dict

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_SYSTEM_ERROR, EXIT_USER_ERROR
from ai_worklog_framework.paths import WorkspacePaths, resolve_workspace
from ai_worklog_framework.redaction import redact_dict
from ai_worklog_framework.state.manager import (
    TicketState,
    list_active_tickets,
    load_state,
    save_state,
)
from ai_worklog_framework.state.patch import apply_path, parse_value
from ai_worklog_framework.state.validator import validate_ticket_state


def _render(data: Dict[str, Any]) -> None:
    print(json.dumps(redact_dict(data), indent=2, ensure_ascii=False))


def _persist(paths: WorkspacePaths, state: TicketState, applying: bool) -> int:
    errors = validate_ticket_state(state.data)
    if errors:
        for error in errors:
            print(f"Validation error: {error}")
        return EXIT_USER_ERROR
    _render(state.data)
    if not applying:
        print("Dry run only. Re-run with --apply to save state.")
        return EXIT_SUCCESS
    try:
        save_state(paths, state)
    except OSError as exc:
        print(f"State write failed: {exc}")
        return EXIT_SYSTEM_ERROR
    print(f"Saved: {paths.ticket_state_file(state.ticket_key)}")
    return EXIT_SUCCESS


def run(args) -> int:
    paths = WorkspacePaths(resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    ))
    action = args.state_action
    if action == "list":
        tickets = list_active_tickets(paths)
        print(f"Ticket states ({len(tickets)}):")
        for key in tickets:
            print(f"  {key}")
        return EXIT_SUCCESS
    if action == "show":
        state_file = paths.ticket_state_file(args.key)
        if not state_file.is_file():
            print(f"State not found: {args.key}")
            return EXIT_USER_ERROR
        _render(load_state(paths, args.key).data)
        return EXIT_SUCCESS
    if action == "init":
        state_file = paths.ticket_state_file(args.key)
        if state_file.exists():
            print(f"State already exists: {args.key}")
            return EXIT_USER_ERROR
        state = TicketState(args.key)
        state.data["summary"] = args.summary or ""
        state.data["services"] = args.service or []
        state.data["governance_mode"] = args.governance_mode
        return _persist(paths, state, args.apply)

    state_file = paths.ticket_state_file(args.key)
    if not state_file.is_file():
        print(f"State not found: {args.key}. Run 'ai-worklog state init {args.key} --apply'.")
        return EXIT_USER_ERROR
    state = load_state(paths, args.key)
    try:
        if action == "set":
            apply_path(state.data, args.path, parse_value(args.value))
        elif action == "blocker":
            if args.state_operation == "add":
                state.add_blocker(args.description, args.owner or "")
            elif args.index < 0 or args.index >= len(state.data["blockers"]):
                raise ValueError(f"Blocker index out of range: {args.index}")
            else:
                state.resolve_blocker(args.index)
        elif action == "decision":
            if args.state_operation == "add":
                if any(item.get("id") == args.id for item in state.data["decisions"]):
                    raise ValueError(f"Decision already exists: {args.id}")
                state.add_decision(args.id, args.description, args.owner or "")
            elif not any(item.get("id") == args.id for item in state.data["decisions"]):
                raise ValueError(f"Decision not found: {args.id}")
            else:
                state.resolve_decision(args.id, args.resolution)
        else:
            print("Usage: ai-worklog state {list|init|show|set|blocker|decision}")
            return EXIT_USER_ERROR
    except ValueError as exc:
        print(str(exc))
        return EXIT_USER_ERROR
    return _persist(paths, state, args.apply)
