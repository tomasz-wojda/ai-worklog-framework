"""
manager.py — Per-ticket structured state management.

Creates, reads, updates, and queries ticket state files stored under
.ai-worklog/state/<TICKET-KEY>.json. All operations are append-safe:
updates never discard prior dimension values without explicit resolution.

Inputs:
  - Ticket key and state dimension updates.

Outputs:
  - TicketState dataclass with all dimensions.
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.paths import WorkspacePaths


class TicketState:
    """
    Represents the structured state of a single ticket.

    All lifecycle dimensions are independent. State is loaded from
    and persisted to a JSON file under .ai-worklog/state/.
    """

    def __init__(self, ticket_key: str, data: Optional[Dict[str, Any]] = None):
        self.ticket_key = ticket_key
        self._data = data or self._default_state(ticket_key)

    @staticmethod
    def _default_state(ticket_key: str) -> Dict[str, Any]:
        now = datetime.now(timezone.utc).isoformat()
        return {
            "ticket_key": ticket_key,
            "summary": "",
            "created_at": now,
            "updated_at": now,
            "governance_mode": "research",
            "investigation": {"state": "not_started", "hypotheses": [], "findings": [], "evidence_refs": []},
            "implementation": {"state": "not_started", "local_changes": [], "uncommitted": False},
            "pull_requests": [],
            "builds": [],
            "gitops": {"state": "not_applicable", "changes": []},
            "synchronization": {"state": "unknown", "manual_actions": []},
            "verification": {"state": "not_started", "checks": []},
            "closeout": {
                "implementation_complete": False,
                "deployment_complete": False,
                "tempo_logged": False,
                "tempo_seconds": 0,
                "worklog_archived": False,
                "handover_generated": False,
            },
            "decisions": [],
            "blockers": [],
            "next_action": "",
            "services": [],
            "repositories": [],
        }

    @property
    def data(self) -> Dict[str, Any]:
        return self._data

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def update_dimension(self, dimension: str, updates: Dict[str, Any]) -> None:
        """
        Updates a single lifecycle dimension, preserving other fields.

        Args:
            dimension: Top-level dimension key (e.g., 'investigation', 'implementation').
            updates: Dictionary of field updates within that dimension.
        """
        if dimension in self._data and isinstance(self._data[dimension], dict):
            self._data[dimension].update(updates)
        else:
            self._data[dimension] = updates
        self._data["updated_at"] = datetime.now(timezone.utc).isoformat()

    def set_next_action(self, action: str) -> None:
        self._data["next_action"] = action
        self._data["updated_at"] = datetime.now(timezone.utc).isoformat()

    def add_blocker(self, description: str, owner: str = "") -> None:
        self._data["blockers"].append({
            "description": description,
            "status": "active",
            "owner": owner,
            "since": datetime.now(timezone.utc).isoformat(),
        })
        self._data["updated_at"] = datetime.now(timezone.utc).isoformat()

    def resolve_blocker(self, index: int) -> None:
        if 0 <= index < len(self._data["blockers"]):
            self._data["blockers"][index]["status"] = "resolved"
            self._data["updated_at"] = datetime.now(timezone.utc).isoformat()

    def add_decision(self, decision_id: str, description: str, owner: str = "") -> None:
        self._data["decisions"].append({
            "id": decision_id,
            "description": description,
            "status": "open",
            "resolution": "",
            "owner": owner,
        })
        self._data["updated_at"] = datetime.now(timezone.utc).isoformat()

    def resolve_decision(self, decision_id: str, resolution: str) -> None:
        for d in self._data["decisions"]:
            if d["id"] == decision_id:
                d["status"] = "resolved"
                d["resolution"] = resolution
                break
        self._data["updated_at"] = datetime.now(timezone.utc).isoformat()


def load_state(paths: WorkspacePaths, ticket_key: str) -> TicketState:
    """
    Loads ticket state from disk, creating default if absent.

    Args:
        paths: Workspace paths.
        ticket_key: JIRA ticket key.

    Returns:
        TicketState instance.
    """
    state_file = paths.ticket_state_file(ticket_key)
    if state_file.is_file():
        try:
            with open(state_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            return TicketState(ticket_key, data)
        except (json.JSONDecodeError, OSError):
            pass
    return TicketState(ticket_key)


def save_state(paths: WorkspacePaths, state: TicketState) -> None:
    """
    Persists ticket state to disk.

    Args:
        paths: Workspace paths.
        state: TicketState to save.
    """
    state_file = paths.ticket_state_file(state.ticket_key)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    with open(state_file, "w", encoding="utf-8") as f:
        json.dump(state.data, f, indent=2, ensure_ascii=False)


def list_active_tickets(paths: WorkspacePaths) -> List[str]:
    """
    Lists all tickets with active state files.

    Args:
        paths: Workspace paths.

    Returns:
        List of ticket keys.
    """
    if not paths.state_dir.is_dir():
        return []
    return [f.stem for f in sorted(paths.state_dir.glob("*.json"))]
