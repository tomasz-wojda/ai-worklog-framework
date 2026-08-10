"""
Unit tests for ai_worklog_framework.state.manager module.
"""

import json
from pathlib import Path

import pytest
from ai_worklog_framework.state.manager import (
    TicketState,
    load_state,
    save_state,
    list_active_tickets,
)
from ai_worklog_framework.paths import WorkspacePaths


@pytest.fixture
def state_workspace(tmp_path):
    (tmp_path / "worklog").mkdir()
    (tmp_path / ".ai-worklog" / "state").mkdir(parents=True)
    return tmp_path


class TestTicketState:
    def test_default_state(self):
        state = TicketState("PROJ-1234")
        assert state.ticket_key == "PROJ-1234"
        assert state.get("governance_mode") == "research"
        assert state.get("investigation")["state"] == "not_started"

    def test_update_dimension(self):
        state = TicketState("PROJ-1234")
        state.update_dimension("investigation", {"state": "in_progress"})
        assert state.get("investigation")["state"] == "in_progress"
        assert state.get("investigation")["hypotheses"] == []

    def test_add_and_resolve_blocker(self):
        state = TicketState("PROJ-1234")
        state.add_blocker("AWS credentials expired", "devops")
        assert len(state.get("blockers")) == 1
        assert state.get("blockers")[0]["status"] == "active"
        state.resolve_blocker(0)
        assert state.get("blockers")[0]["status"] == "resolved"

    def test_add_and_resolve_decision(self):
        state = TicketState("PROJ-1234")
        state.add_decision("D1", "Choose approach A or B")
        assert state.get("decisions")[0]["status"] == "open"
        state.resolve_decision("D1", "Selected approach A")
        assert state.get("decisions")[0]["status"] == "resolved"
        assert state.get("decisions")[0]["resolution"] == "Selected approach A"

    def test_set_next_action(self):
        state = TicketState("PROJ-1234")
        state.set_next_action("Push branch and open PR")
        assert state.get("next_action") == "Push branch and open PR"


class TestPersistence:
    def test_save_and_load(self, state_workspace):
        paths = WorkspacePaths(state_workspace)
        state = TicketState("OPS-123")
        state.update_dimension("implementation", {"state": "complete", "uncommitted": True})
        save_state(paths, state)

        loaded = load_state(paths, "OPS-123")
        assert loaded.get("implementation")["state"] == "complete"
        assert loaded.get("implementation")["uncommitted"] is True

    def test_load_missing_returns_default(self, state_workspace):
        paths = WorkspacePaths(state_workspace)
        state = load_state(paths, "NONEXISTENT-1")
        assert state.ticket_key == "NONEXISTENT-1"
        assert state.get("governance_mode") == "research"

    def test_list_active_tickets(self, state_workspace):
        paths = WorkspacePaths(state_workspace)
        save_state(paths, TicketState("PROJ-100"))
        save_state(paths, TicketState("APP-200"))
        tickets = list_active_tickets(paths)
        assert "APP-200" in tickets
        assert "PROJ-100" in tickets
