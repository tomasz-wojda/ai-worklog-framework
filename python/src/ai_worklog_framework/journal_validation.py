import sqlite3
from pathlib import Path
from typing import Any

from ai_worklog_framework.shared import load_shared


def journal_validation_rules() -> dict[str, Any]:
    return load_shared(
        "journal-validation.json",
        {
            "journal_db_subpath": "repos/ai-memory-ingester/data/journal.db",
            "legacy_journal_subpath": "prompt.log",
            "required_tables": ["journal_observations"],
            "minimum_size_bytes": 1,
        },
    )


def journal_db_path(root: Path) -> Path:
    rules = journal_validation_rules()
    return root / rules["journal_db_subpath"]


def legacy_journal_path(root: Path) -> Path:
    rules = journal_validation_rules()
    return root / rules["legacy_journal_subpath"]


def journal_db_valid(path: Path) -> bool:
    rules = journal_validation_rules()
    minimum_size = int(rules.get("minimum_size_bytes", 1))
    required_tables = list(rules.get("required_tables", ["journal_observations"]))
    if not path.is_file() or path.stat().st_size < minimum_size:
        return False
    try:
        connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            quick_check = connection.execute("PRAGMA quick_check(1)").fetchone()
            if quick_check is None or quick_check[0] != "ok":
                return False
            for table in required_tables:
                row = connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    (table,),
                ).fetchone()
                if row is None:
                    return False
            return True
        finally:
            connection.close()
    except sqlite3.Error:
        return False


def workspace_audit_state(root: Path) -> tuple[bool, bool]:
    return legacy_journal_path(root).exists(), journal_db_valid(journal_db_path(root))
