"""
shared.py — Loads language-neutral business rules used by Python and Groovy.

The repository-level shared JSON files are the canonical source for constants,
default state, compatibility matrices, diagnostics, and validation rules.
"""

import json
from copy import deepcopy
from pathlib import Path
from typing import Any


def framework_root() -> Path:
    current = Path(__file__).resolve().parent
    for candidate in (current, *current.parents):
        if (candidate / "shared").is_dir() and (candidate / "pyproject.toml").is_file():
            return candidate
    return Path(__file__).resolve().parents[3]


def load_shared(name: str, default: Any = None) -> Any:
    path = framework_root() / "shared" / name
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError):
        return deepcopy(default)
