import json
import sqlite3
from pathlib import Path

import pytest

from ai_worklog_framework.adapters.preflight import _check_workspace_structure
from ai_worklog_framework.journal_validation import (
    journal_db_path,
    journal_db_valid,
    workspace_audit_state,
)
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.result import Status
from ai_worklog_framework.shared import load_shared


def _create_journal_db(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    try:
        connection.execute(
            "CREATE TABLE journal_observations (observation_id TEXT PRIMARY KEY)"
        )
        connection.commit()
    finally:
        connection.close()


@pytest.fixture
def paths(tmp_path):
    (tmp_path / "worklog").mkdir()
    return WorkspacePaths(tmp_path)


def test_journal_db_valid_requires_non_empty_sqlite_with_schema(tmp_path):
    db = tmp_path / "journal.db"
    db.write_bytes(b"")
    assert journal_db_valid(db) is False

    _create_journal_db(db)
    assert journal_db_valid(db) is True


def test_workspace_audit_state_accepts_legacy_prompt_log_only(paths):
    paths.prompt_log.touch()
    has_prompt_log, has_journal = workspace_audit_state(paths.root)
    assert has_prompt_log is True
    assert has_journal is False


def test_workspace_audit_state_accepts_journal_db_only(paths):
    _create_journal_db(journal_db_path(paths.root))
    has_prompt_log, has_journal = workspace_audit_state(paths.root)
    assert has_prompt_log is False
    assert has_journal is True


def test_preflight_workspace_ready_with_journal_db_only(paths):
    _create_journal_db(journal_db_path(paths.root))
    result = _check_workspace_structure(paths)
    assert result.status == Status.READY
    assert "journal.db" in result.message


def test_preflight_workspace_ready_with_prompt_log_only(paths):
    paths.prompt_log.touch()
    result = _check_workspace_structure(paths)
    assert result.status == Status.READY
    assert "legacy prompt.log" in result.message


def test_preflight_workspace_degraded_without_audit_source(paths):
    result = _check_workspace_structure(paths)
    assert result.status == Status.DEGRADED
    assert "prompt.log or repos/ai-memory-ingester/data/journal.db" in result.message


def test_writer_contract_shared_rules_present():
    contract = load_shared("journal-writer-contract.json", {})
    assert contract["schema_version"] == 1
    assert contract["transport"] == "json_stdin"
    assert "user_text" in contract["required_fields"]
    assert "assistant_text" in contract["required_fields"]
    assert contract["shadow_period"]["prompt_log_on_journal_failure"] is False
    assert contract["shadow_period"]["rollback_accepts_either"] is True


def test_writer_contract_matches_ai_vault_reference_when_present():
    vault_contract = Path(__file__).resolve().parents[3] / "ai-vault" / "skills" / "worklog-chat-memory" / "references" / "journal-writer-contract.json"
    if not vault_contract.is_file():
        pytest.skip("ai-vault writer contract not available")
    framework_contract = load_shared("journal-writer-contract.json", {})
    assert json.loads(vault_contract.read_text(encoding="utf-8")) == framework_contract
