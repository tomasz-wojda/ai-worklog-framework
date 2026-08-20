"""
commands.py — Toolchain CLI for active runtime diagnostics.

Inputs:
  - Parsed CLI arguments for toolchain subcommand.

Outputs:
  - Human-readable runtime inventory.
"""

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import resolve_workspace
from ai_worklog_framework.toolchain.resolver import check_toolchain


def run(args) -> int:
    if not args.toolchain_action:
        print("Usage: ai-worklog toolchain {check|list}")
        return EXIT_USER_ERROR

    workspace = resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    )
    config = load_config(workspace)
    toolchain_cfg = config.toolchain

    if args.toolchain_action == "check":
        return _check(toolchain_cfg)
    if args.toolchain_action == "list":
        return _list(toolchain_cfg)
    return EXIT_USER_ERROR


def _check(toolchain_cfg) -> int:
    results = check_toolchain(toolchain_cfg)
    print(results.summary())
    print()
    overall = results.overall_status.value.upper()
    print(f"Toolchain: {overall}")
    return EXIT_SUCCESS if results.ok else EXIT_USER_ERROR


def _list(toolchain_cfg) -> int:
    print("Detected runtimes:")
    print(check_toolchain(toolchain_cfg).summary())
    return EXIT_SUCCESS
