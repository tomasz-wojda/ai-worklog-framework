import json
import sqlite3
import subprocess
from pathlib import Path

import platform
import pytest

from tests.parity.conftest import defer_parity_suite

pytestmark = defer_parity_suite()

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / ("ai-worklog.cmd" if platform.system() == "Windows" else "ai-worklog")


def _run(runtime: str, workspace: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    env = {"AI_WORKLOG_FRAMEWORK_ROOT": str(ROOT)}
    return subprocess.run(
        [str(CLI), "--runtime", runtime, "--workspace", str(workspace), *arguments],
        cwd=ROOT,
        env={**dict(**__import__("os").environ), **env},
        text=True,
        capture_output=True,
        check=False,
    )


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


def _workspace(tmp_path: Path) -> Path:
    workspace = tmp_path / "workspace"
    (workspace / "worklog").mkdir(parents=True)
    (workspace / ".ai-worklog").mkdir()
    return workspace


def test_preflight_workspace_journal_only_parity(tmp_path) -> None:
    workspace = _workspace(tmp_path)
    _create_journal_db(workspace / "repos/ai-memory-ingester/data/journal.db")

    python = _run("python", workspace, "preflight")
    groovy = _run("groovy", workspace, "preflight")

    assert python.returncode == groovy.returncode
    assert "journal.db" in python.stdout
    assert "journal.db" in groovy.stdout


def test_preflight_workspace_legacy_prompt_log_parity(tmp_path) -> None:
    workspace = _workspace(tmp_path)
    (workspace / "prompt.log").touch()

    python = _run("python", workspace, "preflight")
    groovy = _run("groovy", workspace, "preflight")

    assert python.returncode == groovy.returncode
    assert "legacy prompt.log" in python.stdout
    assert "legacy prompt.log" in groovy.stdout


def test_writer_contract_parity_between_shared_copies() -> None:
    framework_contract = json.loads(
        (ROOT / "shared/journal-writer-contract.json").read_text(encoding="utf-8")
    )
    vault_contract_path = ROOT.parent / "ai-vault" / "skills" / "worklog-chat-memory" / "references" / "journal-writer-contract.json"
    if not vault_contract_path.is_file():
        pytest.skip("ai-vault writer contract not available")
    vault_contract = json.loads(vault_contract_path.read_text(encoding="utf-8"))
    assert framework_contract == vault_contract
