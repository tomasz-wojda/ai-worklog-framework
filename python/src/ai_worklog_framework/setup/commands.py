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
    load_global_config,
    resolve_workspace_selection,
    set_ai_vault_root,
    set_runtime,
    set_workspace_ides,
    validate_workspace_name,
)
from ai_worklog_framework.setup.checks import find_workspace_registration
from ai_worklog_framework.setup.planner import (
    apply_init_or_repair_plan,
    apply_revert_plan,
    format_filesystem_action,
    plan_setup_init,
    plan_setup_repair,
    plan_setup_revert,
)
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


def _workspace_context(
    explicit_path: Optional[str],
    explicit_name: Optional[str],
) -> tuple[Path, Optional[str], bool, bool]:
    resolved = resolve_workspace_selection(explicit_path, explicit_name)
    workspace = resolved["path"]
    name = resolved.get("name") or find_workspace_registration(workspace)
    config = load_global_config()
    registered = bool(name and name in config.get("workspaces", {}))
    is_default = bool(name and config.get("default_workspace") == name)
    return workspace, name, registered, is_default


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


def run_init(args) -> int:
    json_output = bool(getattr(args, "json", False))
    apply = bool(getattr(args, "apply", False))
    try:
        validate_workspace_name(args.name)
        workspace = canonical_workspace_path(args.path)
        if not workspace.is_dir():
            raise ValueError(f"Workspace not found: {args.path}")

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
        if args.name in config.get("workspaces", {}):
            existing_ides = list(config["workspaces"][args.name].get("ides") or [])

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
            workspace_name=args.name,
            plan=plan,
            runtime=runtime,
            runtime_source=runtime_source,
            vault_root=vault_root,
            vault_source=vault_source,
            ides=ides,
            apply=apply,
        )

        if not json_output:
            for action in plan.get("workspace_actions", []):
                print(format_filesystem_action(action, apply))
            for action in plan.get("skill_actions", []):
                print(format_filesystem_action(action, apply))
            for conflict in plan.get("conflicts", []):
                print(f"conflict: {conflict['path']} ({conflict['reason']})")
            if not apply:
                print("Dry run only. Re-run with --apply to make changes.")

        if apply:
            if plan.get("conflicts"):
                render_report(report, json_output)
                return EXIT_BLOCKED
            try:
                apply_init_or_repair_plan(
                    workspace=workspace,
                    workspace_name=args.name,
                    vault_root=vault_root,
                    ides=ides,
                    plan=plan,
                )
                config = load_global_config()
                make_default = bool(getattr(args, "default", False)) or config.get("default_workspace") is None
                add_workspace(args.name, str(workspace), make_default=make_default)
                set_workspace_ides(args.name, ides)
                if explicit_runtime is not None:
                    set_runtime(explicit_runtime)
                set_ai_vault_root(str(vault_root))
            except OSError as exc:
                if json_output:
                    render_report({**report, "status": "error", "message": str(exc)}, True)
                else:
                    print(f"Setup operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Setup init complete"
            finalize_applied_action_report(report)

        render_report(report, json_output)
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
        workspace, name, registered, is_default = _workspace_context(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
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
        workspace, name, registered, is_default = _workspace_context(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
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
        workspace, name, registered, _ = _workspace_context(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
        )
        if not registered or not name:
            raise ValueError("Workspace is not registered")

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

        if not json_output:
            for action in plan.get("workspace_actions", []):
                print(format_filesystem_action(action, apply))
            for action in plan.get("skill_actions", []):
                print(format_filesystem_action(action, apply))
            for conflict in plan.get("conflicts", []):
                print(f"conflict: {conflict['path']} ({conflict['reason']})")
            if not apply:
                print("Dry run only. Re-run with --apply to make changes.")

        if apply:
            if plan.get("conflicts"):
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
                    print(f"Setup operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Setup repair complete"
            finalize_applied_action_report(report)

        render_report(report, json_output)
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
        workspace, name, registered, _ = _workspace_context(
            getattr(args, "workspace", None),
            getattr(args, "workspace_name", None),
        )
        if not registered or not name:
            raise ValueError("Workspace is not registered")

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

        if not json_output:
            for action in plan.get("service_actions", []):
                print(format_filesystem_action(action, apply))
            for action in plan.get("skill_actions", []):
                print(format_filesystem_action(action, apply))
            for conflict in plan.get("conflicts", []):
                print(f"conflict: {conflict['path']} ({conflict['reason']})")
            if not apply:
                print("Dry run only. Re-run with --apply to make changes.")

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
                    print(f"Setup operation failed: {exc}")
                return EXIT_SYSTEM_ERROR
            report["status"] = "ready"
            report["message"] = "Setup revert complete"
            finalize_applied_action_report(report)

        render_report(report, json_output)
        return exit_code_for_report(report)
    except ValueError as exc:
        if json_output:
            render_report({"operation": "revert", "status": "error", "message": str(exc)}, True)
        else:
            print(str(exc))
        return EXIT_USER_ERROR


def run(args) -> int:
    action = args.setup_action
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
    print("Usage: ai-worklog setup {init|check|show|repair|revert} ...")
    return EXIT_USER_ERROR
