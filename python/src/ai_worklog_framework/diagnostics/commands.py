"""
commands.py — Diagnostics CLI command handlers.

Lists and runs diagnostic packs.

Inputs:
  - Parsed CLI arguments for diag subcommand.

Outputs:
  - Diagnostic results or listing.
"""

import json
from pathlib import Path

from ai_worklog_framework.cli import EXIT_BLOCKED, EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.diagnostics.executor import run_pack
from ai_worklog_framework.paths import WorkspacePaths, resolve_workspace
from ai_worklog_framework.shared import load_shared


AVAILABLE_PACKS = load_shared("diagnostic-packs.json", {})


def run(args) -> int:
    """
    Dispatches diagnostics subcommands.

    Args:
        args: Parsed argparse Namespace with diag_action.

    Returns:
        Exit code.
    """
    if not args.diag_action:
        print("Usage: ai-worklog diag {list|run}")
        return EXIT_USER_ERROR

    if args.diag_action == "list":
        return _list_packs()
    elif args.diag_action == "run":
        parameters = {}
        for item in getattr(args, "param", []) or []:
            if "=" not in item:
                print(f"Invalid parameter: {item} (expected key=value)")
                return EXIT_USER_ERROR
            key, value = item.split("=", 1)
            parameters[key] = value
        if getattr(args, "namespace", None):
            parameters["namespace"] = args.namespace
        if getattr(args, "app", None):
            parameters["app"] = args.app
        if getattr(args, "service", None):
            parameters["service"] = args.service
        workspace = resolve_workspace(getattr(args, "workspace", None))
        return _run_pack(
            args.pack,
            parameters,
            WorkspacePaths(workspace),
            getattr(args, "output", None),
            getattr(args, "json", False),
        )
    return EXIT_USER_ERROR


def _list_packs() -> int:
    """Lists all available diagnostic packs."""
    print(f"Available diagnostic packs ({len(AVAILABLE_PACKS)}):")
    print()
    for pack_id, info in sorted(AVAILABLE_PACKS.items()):
        ro = "read-only" if info["read_only"] else "WRITE-CAPABLE"
        print(f"  {pack_id}")
        print(f"    {info['name']} [{ro}]")
        print(f"    {info['description']}")
        print(f"    requires: {', '.join(info['prerequisites'])}")
        print()
    return EXIT_SUCCESS


def _run_pack(
    pack_id: str,
    parameters: dict,
    paths: WorkspacePaths,
    output: str = None,
    json_output: bool = False,
) -> int:
    """
    Runs a diagnostic pack (stub implementation).

    Full implementation will execute read-only commands and produce evidence bundles.
    """
    if pack_id not in AVAILABLE_PACKS:
        print(f"Unknown pack: {pack_id}")
        print(f"Available: {', '.join(sorted(AVAILABLE_PACKS.keys()))}")
        return EXIT_USER_ERROR

    info = AVAILABLE_PACKS[pack_id]
    try:
        bundle, evidence_path = run_pack(
            pack_id,
            info,
            parameters,
            paths,
            Path(output) if output else None,
        )
    except (OSError, ValueError) as exc:
        print(f"Diagnostic execution failed: {exc}")
        return EXIT_USER_ERROR
    if json_output:
        print(json.dumps(bundle.to_dict(), indent=2, ensure_ascii=False))
    else:
        print(f"Running: {info['name']}")
        print(f"  Pack: {pack_id}")
        print(f"  Status: {bundle.status}")
        for step in bundle.steps:
            print(f"  [{step.exit_code}] {step.id} ({step.duration_ms}ms)")
            if step.stderr:
                print(f"    {step.stderr}")
        print(f"  Evidence: {evidence_path}")
    if bundle.status == "success":
        return EXIT_SUCCESS
    if bundle.status == "blocked":
        return EXIT_BLOCKED
    return EXIT_USER_ERROR
