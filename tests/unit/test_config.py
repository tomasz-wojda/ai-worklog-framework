"""
Unit tests for ai_worklog_framework.config module.
"""

import json
from pathlib import Path

import pytest
from ai_worklog_framework.config import load_config, _deep_merge


class TestDeepMerge:
    def test_simple_override(self):
        base = {"a": 1, "b": 2}
        override = {"b": 3, "c": 4}
        result = _deep_merge(base, override)
        assert result == {"a": 1, "b": 3, "c": 4}

    def test_nested_merge(self):
        base = {"x": {"a": 1, "b": 2}}
        override = {"x": {"b": 3, "c": 4}}
        result = _deep_merge(base, override)
        assert result == {"x": {"a": 1, "b": 3, "c": 4}}


class TestLoadConfig:
    def test_defaults_without_config_files(self, tmp_path):
        cfg = load_config(tmp_path)
        assert cfg.workspace_root == tmp_path
        assert cfg.catalog_path == tmp_path / "catalog"
        assert cfg.interface_path is None

    def test_workspace_config_applied(self, tmp_path):
        config_dir = tmp_path / ".ai-worklog"
        config_dir.mkdir()
        config_data = {"catalog_path": "my-catalog", "interface_path": "worklog/interface"}
        (config_dir / "config.json").write_text(json.dumps(config_data))

        cfg = load_config(tmp_path)
        assert cfg.catalog_path == tmp_path / "my-catalog"
        assert cfg.interface_path == tmp_path / "worklog" / "interface"

    def test_local_override_wins(self, tmp_path):
        config_dir = tmp_path / ".ai-worklog"
        config_dir.mkdir()
        (config_dir / "config.json").write_text(json.dumps({"catalog_path": "global"}))
        (config_dir / "local.json").write_text(json.dumps({"catalog_path": "local"}))

        cfg = load_config(tmp_path)
        assert cfg.catalog_path == tmp_path / "local"
