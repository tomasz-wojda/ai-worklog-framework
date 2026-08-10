"""
paths.py — Workspace discovery and canonical path resolution.

Locates the runtime workspace root by searching upward for markers,
then resolves well-known directories without hardcoding user paths.

Inputs:
  - Optional explicit workspace path or CWD-based auto-detection.

Outputs:
  - Resolved workspace root and derived paths.
"""

import os
from pathlib import Path
from typing import Optional

from ai_worklog_framework.shared import load_shared

_PATH_RULES = load_shared(
    "workspace-markers.json",
    {"markers": [".ai-worklog", "worklog", "prompt.log", "jira"], "max_parent_depth": 20},
)
WORKSPACE_MARKERS = _PATH_RULES["markers"]
MAX_PARENT_DEPTH = _PATH_RULES["max_parent_depth"]


def find_workspace_root(start: Optional[Path] = None) -> Optional[Path]:
    """
    Searches upward from start (or CWD) for a directory containing workspace markers.

    Args:
        start: Starting directory (defaults to current working directory).

    Returns:
        Path to workspace root, or None if not found.
    """
    current = start or Path.cwd()
    current = current.resolve()

    for _ in range(MAX_PARENT_DEPTH):
        for marker in WORKSPACE_MARKERS:
            if (current / marker).exists():
                return current
        parent = current.parent
        if parent == current:
            break
        current = parent
    return None


def resolve_workspace(explicit: Optional[str] = None) -> Path:
    """
    Resolves the workspace root from an explicit override or auto-detection.

    Args:
        explicit: User-provided workspace path override.

    Returns:
        Absolute Path to the workspace root.

    Raises:
        SystemExit: When workspace cannot be located.
    """
    if explicit:
        path = Path(explicit).resolve()
        if path.is_dir():
            return path
        raise SystemExit(f"Workspace not found: {explicit}")

    found = find_workspace_root()
    if found:
        return found

    env_ws = os.environ.get("AI_WORKLOG_WORKSPACE")
    if env_ws:
        path = Path(env_ws).resolve()
        if path.is_dir():
            return path

    raise SystemExit(
        "Cannot locate workspace. Use --workspace, set AI_WORKLOG_WORKSPACE, "
        "or run from within a workspace directory."
    )


class WorkspacePaths:
    """
    Resolved paths derived from the workspace root.

    Attributes:
        root: Workspace root directory.
        worklog: Active worklog directory.
        worklog_done: Archived worklog directory.
        state_dir: Structured ticket state directory.
        config_dir: Framework configuration directory.
        catalog_dir: Service catalog definitions.
        interface_dir: Service interface symlinks.
        prompt_log: Append-only prompt log file.
    """

    def __init__(self, root: Path):
        self.root = root
        self.worklog = root / "worklog"
        self.worklog_done = root / "worklog" / "done"
        self.state_dir = root / ".ai-worklog" / "state"
        self.config_dir = root / ".ai-worklog"
        self.catalog_dir = root / ".ai-worklog" / "catalog"
        self.interface_dir = root / "worklog" / "interface"
        self.prompt_log = root / "prompt.log"

    def service_dir(self, service: str) -> Path:
        """
        Returns the canonical path for a service directory.
        Checks interface symlink first, falls back to workspace root.

        Args:
            service: Service identifier (e.g., 'jira', 'jenkins').

        Returns:
            Path to the service directory.
        """
        interface = self.interface_dir / service
        if interface.exists():
            return interface
        root_svc = self.root / service
        if root_svc.exists():
            return root_svc
        return interface

    def ticket_state_file(self, ticket_key: str) -> Path:
        """
        Returns the state file path for a given ticket.

        Args:
            ticket_key: JIRA ticket key (e.g., 'PROJ-1234').

        Returns:
            Path to the ticket's state JSON file.
        """
        return self.state_dir / f"{ticket_key}.json"
