"""
commands.py — Catalog CLI command handlers.

Provides validate, show, and search operations against the service catalog.

Inputs:
  - Parsed CLI arguments from cli.py catalog subcommand.

Outputs:
  - Exit code (0 success, 1 user error, 2 system error).
"""

import json
import sys
from pathlib import Path
from typing import Dict, List, Any

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR, EXIT_SYSTEM_ERROR
from ai_worklog_framework.paths import resolve_workspace, WorkspacePaths
from ai_worklog_framework.catalog.loader import load_catalog, validate_entry


def run(args) -> int:
    """
    Dispatches catalog subcommands.

    Args:
        args: Parsed argparse Namespace with catalog_action set.

    Returns:
        Exit code integer.
    """
    if not args.catalog_action:
        print("Usage: ai-worklog catalog {validate|show|search}")
        return EXIT_USER_ERROR

    workspace = resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    )
    paths = WorkspacePaths(workspace)

    if args.catalog_action == "validate":
        return _validate(paths)
    elif args.catalog_action == "show":
        return _show(paths, args.service)
    elif args.catalog_action == "search":
        return _search(paths, args.query)
    return EXIT_USER_ERROR


def _validate(paths: WorkspacePaths) -> int:
    """Validates all catalog entries against the schema."""
    catalog = load_catalog(paths)
    if not catalog:
        print("No catalog entries found.")
        return EXIT_USER_ERROR

    errors = []
    for entry_id, entry in catalog.items():
        entry_errors = validate_entry(entry)
        if entry_errors:
            errors.extend([(entry_id, e) for e in entry_errors])

    if errors:
        print(f"Catalog validation: {len(errors)} error(s)")
        for entry_id, err in errors:
            print(f"  [{entry_id}] {err}")
        return EXIT_USER_ERROR

    print(f"Catalog validation: PASS ({len(catalog)} entries)")
    return EXIT_SUCCESS


def _show(paths: WorkspacePaths, service_id: str) -> int:
    """Shows a single catalog entry."""
    catalog = load_catalog(paths)
    entry = catalog.get(service_id)
    if not entry:
        print(f"Service not found: {service_id}")
        available = list(catalog.keys())
        if available:
            print(f"Available: {', '.join(sorted(available))}")
        return EXIT_USER_ERROR

    print(json.dumps(entry, indent=2, ensure_ascii=False))
    return EXIT_SUCCESS


def _search(paths: WorkspacePaths, query: str) -> int:
    """Searches catalog entries by text match across all fields."""
    catalog = load_catalog(paths)
    query_lower = query.lower()
    matches = []

    for entry_id, entry in catalog.items():
        searchable = json.dumps(entry, ensure_ascii=False).lower()
        if query_lower in searchable:
            matches.append((entry_id, entry.get("name", entry_id)))

    if not matches:
        print(f"No catalog entries match: {query}")
        return EXIT_USER_ERROR

    print(f"Found {len(matches)} match(es) for '{query}':")
    for entry_id, name in sorted(matches):
        print(f"  {entry_id}: {name}")
    return EXIT_SUCCESS
