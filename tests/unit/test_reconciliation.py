import json
from argparse import Namespace

import pytest

from ai_worklog_framework.cli import (
    EXIT_BLOCKED,
    EXIT_SUCCESS,
    EXIT_SYSTEM_ERROR,
    EXIT_USER_ERROR,
)
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.reconciliation import commands as reconcile_commands
from ai_worklog_framework.reconciliation.comparators import compare_state, load_rules
from ai_worklog_framework.reconciliation.engine import reconcile_ticket
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.result import Status
from ai_worklog_framework.state.manager import TicketState, save_state


def _write_state(tmp_path, ticket_key="TEST-1", **updates):
    paths = WorkspacePaths(tmp_path)
    state = TicketState(ticket_key)
    for key, value in updates.items():
        parts = key.split(".")
        target = state.data
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = value
    save_state(paths, state)
    return paths


def test_compare_uncommitted_mismatch():
    state = TicketState("TEST-1").data
    state["implementation"]["uncommitted"] = False
    observations = [
        Observation(
            system="git",
            source="git:demo-repo",
            status=Status.READY,
            message="ok",
            details={"present": True, "dirty": True, "ahead_of_upstream": 0},
        )
    ]
    contradictions = compare_state(state, observations, load_rules())
    assert any(item.code == "uncommitted_mismatch" for item in contradictions)


def test_compare_jira_complete_impl_incomplete():
    state = TicketState("TEST-1").data
    state["implementation"]["state"] = "in_progress"
    observations = [
        Observation(
            system="jira",
            source="jira",
            status=Status.READY,
            message="ok",
            details={"summary": "Demo", "status_category": "done", "status": "Done"},
        )
    ]
    contradictions = compare_state(state, observations, load_rules())
    assert any(item.code == "jira_complete_impl_incomplete" for item in contradictions)
    assert contradictions[0].severity == Status.BLOCKED


def test_reconcile_missing_state_returns_user_error(tmp_path, capsys):
    paths = WorkspacePaths(tmp_path)
    code = reconcile_commands.run(Namespace(
        reconcile_action="status",
        key="TEST-999",
        system=None,
        json=False,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_USER_ERROR
    assert "State not found" in capsys.readouterr().out


def test_reconcile_invalid_system_returns_user_error(tmp_path):
    _write_state(tmp_path)
    code = reconcile_commands.run(Namespace(
        reconcile_action="status",
        key="TEST-1",
        system=["invalid"],
        json=False,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_USER_ERROR


def test_reconcile_json_output_sorted(tmp_path, monkeypatch, capsys):
    paths = _write_state(tmp_path, summary="Stored")
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.jira.observe_jira",
        lambda *_args, **_kwargs: [
            Observation(
                system="jira",
                source="jira",
                status=Status.READY,
                message="ok",
                details={"summary": "Observed", "status_category": "indeterminate", "status": "Open"},
            )
        ],
    )
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.git.observe_git",
        lambda *_args, **_kwargs: [],
    )
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.github.observe_github",
        lambda *_args, **_kwargs: [],
    )
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.jenkins.observe_jenkins",
        lambda *_args, **_kwargs: [],
    )
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.argocd.observe_argocd",
        lambda *_args, **_kwargs: [],
    )
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.tempo.observe_tempo",
        lambda *_args, **_kwargs: [],
    )
    code = reconcile_commands.run(Namespace(
        reconcile_action="status",
        key="TEST-1",
        system=["jira"],
        json=True,
        workspace=str(tmp_path),
    ))
    output = capsys.readouterr().out
    payload = json.loads(output)
    assert code == EXIT_SUCCESS
    assert payload["ticket_key"] == "TEST-1"
    assert payload["observations"][0]["system"] == "jira"
    assert any(item["code"] == "jira_summary_mismatch" for item in payload["contradictions"])


def test_reconcile_blocking_contradiction_exit_code(tmp_path, monkeypatch):
    _write_state(tmp_path, **{"implementation.state": "in_progress"})
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.jira.observe_jira",
        lambda *_args, **_kwargs: [
            Observation(
                system="jira",
                source="jira",
                status=Status.READY,
                message="ok",
                details={"summary": "Demo", "status_category": "done", "status": "Done"},
            )
        ],
    )
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.git.observe_git", lambda *_a, **_k: [])
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.github.observe_github", lambda *_a, **_k: [])
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.jenkins.observe_jenkins", lambda *_a, **_k: [])
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.argocd.observe_argocd", lambda *_a, **_k: [])
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.tempo.observe_tempo", lambda *_a, **_k: [])
    code = reconcile_commands.run(Namespace(
        reconcile_action="status",
        key="TEST-1",
        system=["jira"],
        json=False,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_BLOCKED


def test_reconcile_malformed_adapter_exit_code(tmp_path, monkeypatch):
    _write_state(tmp_path)
    monkeypatch.setattr(
        "ai_worklog_framework.reconciliation.engine.jira.observe_jira",
        lambda *_args, **_kwargs: [
            Observation(
                system="jira",
                source="jira",
                status=Status.ERROR,
                message="Malformed Jira response",
            )
        ],
    )
    code = reconcile_commands.run(Namespace(
        reconcile_action="status",
        key="TEST-1",
        system=["jira"],
        json=False,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_SYSTEM_ERROR


def test_reconcile_ticket_selected_system_only(tmp_path, monkeypatch):
    paths = _write_state(tmp_path)
    calls = []

    def _capture(system):
        def _observer(*_args, **_kwargs):
            calls.append(system)
            return []
        return _observer

    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.jira.observe_jira", _capture("jira"))
    monkeypatch.setattr("ai_worklog_framework.reconciliation.engine.git.observe_git", _capture("git"))
    report = reconcile_ticket(paths, "TEST-1", ["git"])
    assert calls == ["git"]
    assert report.ticket_key == "TEST-1"
