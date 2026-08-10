"""
commands.py — Diagnostics CLI command handlers.

Lists and runs diagnostic packs.

Inputs:
  - Parsed CLI arguments for diag subcommand.

Outputs:
  - Diagnostic results or listing.
"""

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
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
        return _run_pack(args.pack, getattr(args, "namespace", None), getattr(args, "app", None))
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


def _run_pack(pack_id: str, namespace: str = None, app: str = None) -> int:
    """
    Runs a diagnostic pack (stub implementation).

    Full implementation will execute read-only commands and produce evidence bundles.
    """
    if pack_id not in AVAILABLE_PACKS:
        print(f"Unknown pack: {pack_id}")
        print(f"Available: {', '.join(sorted(AVAILABLE_PACKS.keys()))}")
        return EXIT_USER_ERROR

    info = AVAILABLE_PACKS[pack_id]
    print(f"Running: {info['name']}")
    print(f"  Pack: {pack_id}")
    if namespace:
        print(f"  Namespace: {namespace}")
    if app:
        print(f"  Application: {app}")
    print()
    print("  [STUB] Full diagnostic execution not yet implemented.")
    print("  Evidence bundle would be generated here.")
    return EXIT_SUCCESS
