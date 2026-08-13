from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.global_config import (
    config_file_path,
    print_json,
    set_runtime,
    show_configuration,
    show_runtime,
)


def _availability_suffix(entry: dict) -> str:
    return " [available]" if entry.get("available") else " [missing]"


def _default_suffix(entry: dict) -> str:
    return " [default]" if entry.get("default") else ""


def _ides_suffix(entry: dict) -> str:
    ides = entry.get("ides") or []
    if ides:
        return f"  ides: {', '.join(ides)}"
    return "  ides: none"


def _render_human(payload: dict) -> None:
    operation = payload.get("operation")
    if operation == "show":
        print(f"Global configuration ({config_file_path()}):")
        print(f"  version: {payload['version']}")
        print(f"  runtime: {payload['runtime']}")
        print(f"  AI Vault root: {payload.get('ai_vault_root') or 'none'}")
        print(f"  default workspace: {payload.get('default_workspace') or 'none'}")
        if payload.get("workspaces"):
            print("  workspaces:")
            for entry in payload["workspaces"]:
                print(
                    f"    {entry['name']}  {entry['path']}"
                    f"{_availability_suffix(entry)}{_default_suffix(entry)}{_ides_suffix(entry)}"
                )
        else:
            print("  workspaces: none")
    elif operation == "runtime":
        print(f"Runtime: {payload['runtime']}")
    elif operation == "ai_vault_root":
        print(f"AI Vault Root: {payload.get('ai_vault_root') or 'none'}")


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


def run(args) -> int:
    action = args.config_action
    json = bool(getattr(args, "json", False))
    if not action:
        print("Usage: ai-worklog config {show|runtime|set-ai-vault-root}")
        return EXIT_USER_ERROR
    try:
        if action == "show":
            return _render(show_configuration(), json)
        if action == "runtime":
            if getattr(args, "runtime", None) is None:
                return _render(show_runtime(), json)
            return _render(set_runtime(args.runtime), json)
        if action == "set-ai-vault-root":
            from ai_worklog_framework.global_config import set_ai_vault_root
            return _render(set_ai_vault_root(getattr(args, "path", None)), json)
        print("Usage: ai-worklog config {show|runtime|set-ai-vault-root}")
        return EXIT_USER_ERROR
    except ValueError as exc:
        return _handle_error(action, json, exc)
