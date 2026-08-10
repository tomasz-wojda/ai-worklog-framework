import json

import pytest

from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.state.manager import TicketState, load_state, save_state
from ai_worklog_framework.state.patch import apply_path, parse_value
from ai_worklog_framework.state.validator import validate_ticket_state


def test_state_patch_validates_and_persists_atomically(tmp_path):
    paths = WorkspacePaths(tmp_path)
    state = TicketState("TEST-1")
    previous = apply_path(state.data, "implementation.state", "in_progress")
    save_state(paths, state)

    assert previous == "not_started"
    assert load_state(paths, "TEST-1").data["implementation"]["state"] == "in_progress"
    assert not list(paths.state_dir.glob("*.tmp"))


def test_state_patch_parses_json_values():
    assert parse_value("true") is True
    assert parse_value('["one"]') == ["one"]
    assert parse_value("plain") == "plain"


@pytest.mark.parametrize(
    ("path", "value"),
    [
        ("implementation.state", "invalid"),
        ("implementation.uncommitted", "yes"),
        ("unknown.path", "value"),
        ("ticket_key", "OTHER-1"),
    ],
)
def test_state_patch_rejects_invalid_changes(path, value):
    state = TicketState("TEST-1")
    with pytest.raises(ValueError):
        apply_path(state.data, path, value)


def test_state_validator_rejects_unknown_top_level_field():
    state = TicketState("TEST-1").data
    state["unexpected"] = True
    assert validate_ticket_state(state) == ["Unknown top-level field: unexpected"]


def test_ticket_key_rejects_path_traversal(tmp_path):
    paths = WorkspacePaths(tmp_path)
    with pytest.raises(ValueError):
        paths.ticket_state_file("../../outside")
