"""
config.py — Layered configuration loading for framework, workspace, and local overrides.

Load order (later overrides earlier):
  1. Framework defaults (bundled in package).
  2. Workspace configuration (<workspace>/.ai-worklog/config.json).
  3. Local overrides (<workspace>/.ai-worklog/local.json, gitignored).

Inputs:
  - workspace_root: Path to the runtime workspace.

Outputs:
  - Config dataclass with merged settings.
"""

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Optional


FRAMEWORK_CONFIG_DIR = ".ai-worklog"
WORKSPACE_CONFIG_FILE = "config.json"
LOCAL_OVERRIDE_FILE = "local.json"


@dataclass
class Config:
    """Merged framework configuration."""
    workspace_root: Path
    catalog_path: Path = field(default_factory=lambda: Path("catalog"))
    interface_path: Optional[Path] = None
    services: Dict[str, Any] = field(default_factory=dict)
    adapters: Dict[str, Any] = field(default_factory=dict)
    preflight: Dict[str, Any] = field(default_factory=dict)
    toolchain: Dict[str, Any] = field(default_factory=dict)


def _load_json(path: Path) -> Dict[str, Any]:
    """
    Safely loads a JSON file, returning empty dict on missing or invalid files.

    Args:
        path: Absolute path to JSON file.

    Returns:
        Parsed dictionary or empty dict.
    """
    if not path.is_file():
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}


def _deep_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    """
    Recursively merges override into base. Override values win for non-dict keys.

    Args:
        base: Base dictionary.
        override: Override dictionary.

    Returns:
        Merged dictionary (new instance).
    """
    merged = dict(base)
    for key, value in override.items():
        if key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def _framework_defaults() -> Dict[str, Any]:
    """
    Returns bundled framework default configuration.

    Returns:
        Dictionary of default settings.
    """
    return {
        "catalog_path": "catalog",
        "interface_path": None,
        "services": {},
        "adapters": {},
        "preflight": {
            "required_binaries": ["python3", "git", "gh", "kubectl", "aws", "jq"],
            "optional_binaries": ["groovy", "java", "argocd", "helm"],
        },
        "toolchain": {
            "groovy": {},
        },
    }


def load_config(workspace_root: Path) -> Config:
    """
    Loads configuration by merging framework defaults, workspace config, and local overrides.

    Args:
        workspace_root: Absolute path to the runtime workspace directory.

    Returns:
        Fully resolved Config instance.
    """
    config_dir = workspace_root / FRAMEWORK_CONFIG_DIR
    defaults = _framework_defaults()
    workspace_cfg = _load_json(config_dir / WORKSPACE_CONFIG_FILE)
    local_cfg = _load_json(config_dir / LOCAL_OVERRIDE_FILE)

    merged = _deep_merge(defaults, workspace_cfg)
    merged = _deep_merge(merged, local_cfg)

    catalog_path = Path(merged.get("catalog_path", "catalog"))
    if not catalog_path.is_absolute():
        catalog_path = workspace_root / catalog_path

    return Config(
        workspace_root=workspace_root,
        catalog_path=catalog_path,
        interface_path=None,
        services=merged.get("services", {}),
        adapters=merged.get("adapters", {}),
        preflight=merged.get("preflight", {}),
        toolchain=merged.get("toolchain", {}),
    )
