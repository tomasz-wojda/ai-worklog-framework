from pathlib import Path
from typing import List, Optional

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
    load_global_config,
    print_json,
    remove_workspace,
    resolve_workspace_selection,
    set_ai_vault_root,
    set_default_workspace,
    set_runtime,
    set_workspace_ides,
    show_default_workspace,
    validate_workspace_name,
)
from ai_worklog_framework.setup.checks import find_workspace_registration
from ai_worklog_framework.setup.planner import (
    apply_init_or_repair_plan,
    apply_revert_plan,
    plan_setup_init,
    plan_setup_repair,
    plan_setup_revert,
    print_compact_action_plan,
)
from ai_worklog_framework.setup.planner import _setup_print_row
from ai_worklog_framework.setup.report import (
    build_action_report,
    build_check_report,
    build_show_report,
    exit_code_for_report,
    finalize_applied_action_report,
    render_report,
)
from ai_worklog_framework.setup.resolver import (
    normalize_ide_selection,
    parse_ide_args,
    resolve_ai_vault_root,
    resolve_runtime_selection,
    validate_runtime,
)
from ai_worklog_framework.setup.vault import validate_vault_root


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


def _workspace_context(
    explicit_path: Optional[str],
    explicit_name: Optional[str],
    positional: Optional[str] = None,
) -> tuple[Path, Optional[str], bool, bool, str]:
    name: Optional[str] = None
    if positional:
        config = load_global_config()
        workspaces = config.get("workspaces", {})
        target = positional
        source = "explicit_path"
        if positional in workspaces:
            name = positional
            target = workspaces[positional]["path"]
            source = "workspace_name"
        workspace = canonical_workspace_path(target)
        if not workspace.is_dir():
            raise ValueError(f"Workspace not found: {positional}")
    else:
        resolved = resolve_workspace_selection(explicit_path, explicit_name)
        workspace = resolved["path"]
        name = resolved.get("name")
        source = resolved["source"]
    if not name:
        name = find_workspace_registration(workspace)
    config = load_global_config()
    registered = bool(name and name in config.get("workspaces", {}))
    is_default = bool(name and config.get("default_workspace") == name)
    return workspace, name, registered, is_default, source


def _unregistered_error(workspace: Path, source: str) -> ValueError:
    message = f"Workspace is not registered: {workspace} (source: {source})"
    if source == "cwd_legacy":
        message += ". Directory is not initialized; run 'workspace init' to initialize it."
    return ValueError(message)


def _resolve_vault_or_error(
    workspace: Path,
    cli_override: Optional[str],
) -> tuple[Path, str, dict]:
    vault_root, vault_source = resolve_ai_vault_root(workspace, cli_override=cli_override)
    if vault_root is None:
        raise ValueError("AI vault not found")
    valid, message, manifest = validate_vault_root(vault_root)
    if not valid:
        raise ValueError(message)
    return vault_root, vault_source or "unknown", manifest


def _persist_revert_ides(name: str, remaining_ides: List[str]) -> None:
    set_workspace_ides(name, remaining_ides)


def _print_action_conflicts(conflicts: list) -> None:
    for conflict in conflicts:
        _setup_print_row("Conflict", f"{conflict['path']} ({conflict['reason']})", ok=False)


def _render_human_action_plan(plan: dict, apply: bool, operation: str) -> None:
    print(f"Workspace {operation}")
    print_compact_action_plan(plan, apply)
    _print_action_conflicts(plan.get("conflicts") or [])
    print()


def run_init(args) -> int:
    json_output = bool(getattr(args, "json", False))
    apply = bool(getattr(args, "apply", False))
    try:
        config = load_global_config()
        workspaces = config.get("workspaces", {})
        target_name = getattr(args, "name", None)
        target_path = getattr(args, "path", None)

        if not target_name and not target_path:
            explicit_path, explicit_name = _selectors(args)
            if explicit_name and explicit_name in workspaces:
                target_name = explicit_name
                target_path = workspaces[explicit_name]["path"]
            elif explicit_path:
                target_path = explicit_path
                target_name = explicit_name or find_workspace_registration(canonical_workspace_path(explicit_path)) or "workspace"
            elif config.get("default_workspace") and config["default_workspace"] in workspaces:
                target_name = config["default_workspace"]
                target_path = workspaces[target_name]["path"]
        elif target_name and not target_path:
            if target_name in workspaces:
                target_path = workspaces[target_name]["path"]
            else:
                target_path = target_name
                target_name = find_workspace_registration(canonical_workspace_path(target_path)) or "workspace"
        elif target_path in workspaces:
            target_path = workspaces[target_path]["path"]

        if not target_name or not target_path:
            raise ValueError("Usage: ai-worklog workspace init [<name>] [<path>] [-w workspace] [--ide IDE] [--runtime groovy|python] [--ai-vault PATH] [--default] [--json] [--apply]")

        validate_workspace_name(target_name)
        workspace = canonical_workspace_path(target_path)
        if not workspace.is_dir():
            raise ValueError(f"Workspace not found: {target_path}")

        vault_root, vault_source, vault_manifest = _resolve_vault_or_error(
            workspace,
            getattr(args, "ai_vault", None),
        )

        explicit_runtime = getattr(args, "runtime", None)
        if explicit_runtime and not validate_runtime(explicit_runtime):
            raise ValueError(f"Runtime unavailable: {explicit_runtime}")
        runtime, runtime_source, _ = resolve_runtime_selection(explicit_runtime)
        if explicit_runtime:
            runtime = explicit_runtime
            runtime_source = "explicit"

        config = load_global_config()
        existing_ides: List[str] = []
        if target_name in config.get("workspaces", {}):
            existing_ides = list(config["workspaces"][target_name].get("ides") or [])

        requested = parse_ide_args(getattr(args, "ide", None))
        ides = normalize_ide_selection(requested, existing_ides, workspace)

        plan = plan_setup_init(
            workspace=workspace,
            vault_root=vault_root,
            vault_manifest=vault_manifest,
            ides=ides,
            adopt=apply,
        )

        report = build_action_report(
            operation="init",
            workspace=workspace,
            workspace_name=target_name,
            plan=plan,
            runtime=runtime,
            runtime_source=runtime_source,
            vault_root=vault_root,
            vault_source=vault_source,
            ides=ides,
            apply=apply,
        )

        if apply:
            if plan.get("conflicts"):
                if not json_output:
                    _render_human_action_plan(plan, apply=False, operation="init")
                render_report(report, json_output)
                return EXIT_BLOCKED
            try:
                apply_init_or_repair_plan(
                    workspace=workspace,
                    workspace_name=target_name,
                    vault_root=vault_root,
                    ides=ides,
                    plan=plan,
                )
                config = load_global_config()
                make_default = bool(getattr(args, "default", False)) or config.get("default_workspace") is None
                add_workspace(target_name, str(workspace), make_default=make_default)
                set_workspace_ides(target_name, ides)
                if explicit_runtime is not None:
                    set_runtime(explicit_runtime)
                set_ai_vault_root(str(vault_root))
            except OSError as exc:
                if json_output:
                    render_report({**report, "status": "error", "message": str(exc)}, True)
                else:
                    print(f"Workspace operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Workspace init complete"
            finalize_applied_action_report(report)

        if not json_output:
            _render_human_action_plan(plan, apply, operation="init")

        render_report(report, json_output, actions_printed=not json_output)
        return exit_code_for_report(report)
    except ValueError as exc:
        if json_output:
            render_report({"operation": "init", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def run_check(args) -> int:
    json_output = bool(getattr(args, "json", False))
    try:
        explicit_path, explicit_name = _selectors(args)
        workspace, name, registered, is_default, _ = _workspace_context(
            explicit_path,
            explicit_name,
        )
        report = build_check_report(
            workspace=workspace,
            workspace_name=name,
            registered=registered,
            is_default=is_default,
        )
        render_report(report, json_output)
        return exit_code_for_report(report)
    except ValueError as exc:
        if json_output:
            render_report({"operation": "check", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def run_show(args) -> int:
    json_output = bool(getattr(args, "json", False))
    try:
        explicit_path, explicit_name = _selectors(args)
        workspace, name, registered, is_default, _ = _workspace_context(
            explicit_path,
            explicit_name,
            getattr(args, "name", None),
        )
        report = build_show_report(
            workspace=workspace,
            workspace_name=name,
            registered=registered,
            is_default=is_default,
        )
        render_report(report, json_output)
        return EXIT_SUCCESS
    except ValueError as exc:
        if json_output:
            render_report({"operation": "show", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def run_repair(args) -> int:
    json_output = bool(getattr(args, "json", False))
    apply = bool(getattr(args, "apply", False))
    try:
        explicit_path, explicit_name = _selectors(args)
        workspace, name, registered, _, source = _workspace_context(
            explicit_path,
            explicit_name,
        )
        if not registered or not name:
            raise _unregistered_error(workspace, source)

        config = load_global_config()
        registered_ides = list(config["workspaces"][name].get("ides") or [])
        if not registered_ides:
            raise ValueError("No IDE profiles registered for workspace")

        filter_ides = parse_ide_args(getattr(args, "ide", None))
        ides = registered_ides
        if filter_ides:
            invalid = [ide for ide in filter_ides if ide != "auto" and ide not in registered_ides]
            if invalid:
                raise ValueError(f"IDE not registered: {', '.join(invalid)}")
            ides = [ide for ide in filter_ides if ide != "auto"]

        vault_root, vault_source, vault_manifest = _resolve_vault_or_error(workspace, None)
        runtime, runtime_source, _ = resolve_runtime_selection()

        plan = plan_setup_repair(
            workspace=workspace,
            vault_root=vault_root,
            vault_manifest=vault_manifest,
            ides=ides,
            adopt=apply,
        )

        report = build_action_report(
            operation="repair",
            workspace=workspace,
            workspace_name=name,
            plan=plan,
            runtime=runtime,
            runtime_source=runtime_source,
            vault_root=vault_root,
            vault_source=vault_source,
            ides=ides,
            apply=apply,
        )

        if apply:
            if plan.get("conflicts"):
                if not json_output:
                    _render_human_action_plan(plan, apply=False, operation="repair")
                render_report(report, json_output)
                return EXIT_BLOCKED
            try:
                apply_init_or_repair_plan(
                    workspace=workspace,
                    workspace_name=name,
                    vault_root=vault_root,
                    ides=ides,
                    plan=plan,
                )
            except OSError as exc:
                if json_output:
                    render_report({**report, "status": "error", "message": str(exc)}, True)
                else:
                    print(f"Workspace operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Workspace repair complete"
            finalize_applied_action_report(report)

        if not json_output:
            _render_human_action_plan(plan, apply, operation="repair")

        render_report(report, json_output, actions_printed=not json_output)
        return exit_code_for_report(report)
    except ValueError as exc:
        if json_output:
            render_report({"operation": "repair", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def run_revert(args) -> int:
    json_output = bool(getattr(args, "json", False))
    apply = bool(getattr(args, "apply", False))
    try:
        explicit_path, explicit_name = _selectors(args)
        workspace, name, registered, _, source = _workspace_context(
            explicit_path,
            explicit_name,
            getattr(args, "path", None),
        )
        if not registered or not name:
            raise _unregistered_error(workspace, source)

        filter_ides = parse_ide_args(getattr(args, "ide", None))
        if filter_ides and "auto" in filter_ides:
            raise ValueError("--ide auto cannot be used with revert")

        vault_root, vault_source = resolve_ai_vault_root(workspace)
        runtime, runtime_source, _ = resolve_runtime_selection()

        plan = plan_setup_revert(workspace=workspace, ides=filter_ides)
        config = load_global_config()
        ides = list(config["workspaces"][name].get("ides") or [])

        report = build_action_report(
            operation="revert",
            workspace=workspace,
            workspace_name=name,
            plan=plan,
            runtime=runtime,
            runtime_source=runtime_source,
            vault_root=vault_root,
            vault_source=vault_source,
            ides=ides,
            apply=apply,
        )

        if apply:
            try:
                apply_revert_plan(
                    workspace=workspace,
                    workspace_name=name,
                    vault_root=vault_root,
                    plan=plan,
                )
                _persist_revert_ides(name, list(plan.get("remaining_ides") or []))
            except OSError as exc:
                if json_output:
                    render_report({**report, "status": "error", "message": str(exc)}, True)
                else:
                    print(f"Workspace operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Workspace revert complete"
            finalize_applied_action_report(report)

        if not json_output:
            _render_human_action_plan(plan, apply, operation="revert")

        render_report(report, json_output, actions_printed=not json_output)
        return exit_code_for_report(report)
    except ValueError as exc:
        if json_output:
            render_report({"operation": "revert", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def _render_ides(payload: dict, changed: bool) -> None:
    ides = payload.get("ides") or []
    print(f"IDEs for {payload['name']}: {', '.join(ides) if ides else 'none'}")
    if changed:
        print("Run 'ai-worklog workspace repair --apply' to materialize.")


def run_ides(args) -> int:
    json_output = bool(getattr(args, "json", False))
    try:
        explicit_path, explicit_name = _selectors(args)
        workspace, name, registered, _, source = _workspace_context(
            explicit_path,
            explicit_name,
        )
        if not registered or not name:
            raise _unregistered_error(workspace, source)

        requested = list(getattr(args, "ide", None) or [])
        if not requested:
            config = load_global_config()
            current = list(config["workspaces"][name].get("ides") or [])
            payload = {
                "operation": "ides",
                "status": "ok",
                "name": name,
                "ides": current,
            }
            if json_output:
                print_json(payload)
            else:
                _render_ides(payload, False)
            return EXIT_SUCCESS

        ides = normalize_ide_selection(requested, [], workspace)
        payload = set_workspace_ides(name, ides)
        if json_output:
            print_json(payload)
        else:
            _render_ides(payload, True)
        return EXIT_SUCCESS
    except ValueError as exc:
        if json_output:
            print_json({"operation": "ides", "status": "error", "message": str(exc)})
        else:
            print(str(exc))
        return EXIT_USER_ERROR


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


def run(args) -> int:
    action = args.workspace_action
    if action == "init":
        return run_init(args)
    if action == "check":
        return run_check(args)
    if action == "show":
        return run_show(args)
    if action == "repair":
        return run_repair(args)
    if action == "revert":
        return run_revert(args)
    if action == "ides":
        return run_ides(args)

    json = bool(getattr(args, "json", False))
    try:
        if action == "add":
            payload = add_workspace(args.name, args.path, make_default=bool(args.default))
        elif action == "list":
            payload = list_workspaces()
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
                "{init|check|show|repair|revert|ides|add|list|default|current|remove} ..."
            )
            return EXIT_USER_ERROR
        return _render(payload, json)
    except ValueError as exc:
        return _handle_error(action, json, exc)
    except OSError as exc:
        print(f"Config write failed: {exc}")
        return EXIT_SYSTEM_ERROR
