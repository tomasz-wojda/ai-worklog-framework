import json
import os
import stat
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

from ai_worklog_framework import global_config as gc
from ai_worklog_framework import global_config_commands as config_commands
from ai_worklog_framework.workspace import commands as workspace_commands


@pytest.fixture
def home(tmp_path, monkeypatch):
    root = tmp_path / "home"
    root.mkdir()
    monkeypatch.setenv("AI_WORKLOG_HOME", str(root))
    return root


@pytest.fixture
def work_workspace(tmp_path):
    ws = tmp_path / "work"
    ws.mkdir()
    (ws / "worklog").mkdir()
    return ws


@pytest.fixture
def test_workspace(tmp_path):
    ws = tmp_path / "test"
    ws.mkdir()
    (ws / "prompt.log").touch()
    return ws


def _write_raw(home: Path, payload) -> None:
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = home / "config.json"
    path.write_text(json.dumps(payload), encoding="utf-8")


class TestGlobalConfigValidation:
    def test_default_config_when_missing(self, home):
        cfg = gc.load_global_config()
        assert cfg == gc.default_config()

    def test_rejects_unknown_keys(self, home):
        _write_raw(home, {"version": 1, "runtime": "groovy", "extra": True})
        with pytest.raises(ValueError, match="Unknown global configuration keys"):
            gc.load_global_config()

    def test_rejects_unsupported_version(self, home):
        _write_raw(home, {"version": 99, "runtime": "groovy", "workspaces": {}})
        with pytest.raises(ValueError, match="Unsupported global configuration version"):
            gc.load_global_config()

    def test_rejects_malformed_json(self, home):
        (home / "config.json").write_text("{bad", encoding="utf-8")
        with pytest.raises(ValueError, match="Malformed global configuration"):
            gc.load_global_config()

    def test_rejects_invalid_runtime(self, home):
        _write_raw(home, {"version": 1, "runtime": "ruby", "workspaces": {}})
        with pytest.raises(ValueError, match="Invalid runtime"):
            gc.load_global_config()

    def test_rejects_invalid_workspace_name(self, home):
        _write_raw(
            home,
            {"version": 1, "runtime": "groovy", "workspaces": {"bad name": "/tmp/x"}},
        )
        with pytest.raises(ValueError, match="Invalid workspace name"):
            gc.load_global_config()

    def test_rejects_default_not_registered(self, home):
        _write_raw(
            home,
            {"version": 1, "runtime": "groovy", "default_workspace": "missing", "workspaces": {}},
        )
        with pytest.raises(ValueError, match="Unknown default workspace"):
            gc.load_global_config()

    def test_canonicalizes_workspace_paths(self, home, work_workspace):
        _write_raw(
            home,
            {
                "version": 1,
                "runtime": "groovy",
                "workspaces": {"work": str(work_workspace)},
            },
        )
        cfg = gc.load_global_config()
        assert cfg["workspaces"]["work"] == str(work_workspace.resolve())


class TestGlobalConfigMutations:
    def test_add_and_permissions(self, home, work_workspace):
        payload = gc.add_workspace("work", str(work_workspace), make_default=True)
        cfg_path = home / "config.json"
        assert payload["status"] == "ok"
        assert payload["default"] is True
        assert cfg_path.is_file()
        assert stat.S_IMODE(cfg_path.stat().st_mode) == 0o600
        assert stat.S_IMODE(home.stat().st_mode) == 0o700

    def test_add_idempotent(self, home, work_workspace):
        gc.add_workspace("work", str(work_workspace))
        payload = gc.add_workspace("work", str(work_workspace))
        assert payload.get("unchanged") is True

    def test_add_conflict(self, home, work_workspace, test_workspace):
        gc.add_workspace("work", str(work_workspace))
        with pytest.raises(ValueError, match="is already registered with a different path"):
            gc.add_workspace("work", str(test_workspace))

    def test_add_requires_existing_directory(self, home, tmp_path):
        missing = tmp_path / "missing"
        with pytest.raises(ValueError, match="Workspace not found"):
            gc.add_workspace("work", str(missing))

    def test_add_expands_tilde(self, home, monkeypatch, work_workspace):
        monkeypatch.setenv("HOME", str(work_workspace.parent))
        gc.add_workspace("work", "~/work")
        cfg = gc.load_global_config()
        assert cfg["workspaces"]["work"] == str(work_workspace.resolve())

    def test_remove_clears_default(self, home, work_workspace, test_workspace):
        gc.add_workspace("work", str(work_workspace), make_default=True)
        gc.add_workspace("test", str(test_workspace))
        gc.remove_workspace("work")
        cfg = gc.load_global_config()
        assert "work" not in cfg["workspaces"]
        assert cfg["default_workspace"] is None

    def test_remove_unknown(self, home):
        with pytest.raises(ValueError, match="Workspace not registered"):
            gc.remove_workspace("work")

    def test_set_default(self, home, work_workspace, test_workspace):
        gc.add_workspace("work", str(work_workspace))
        gc.add_workspace("test", str(test_workspace))
        payload = gc.set_default_workspace("test")
        assert payload["name"] == "test"
        assert gc.load_global_config()["default_workspace"] == "test"

    def test_set_runtime(self, home):
        payload = gc.set_runtime("python")
        assert payload["runtime"] == "python"
        assert gc.show_runtime()["runtime"] == "python"

    def test_malformed_config_not_overwritten_on_write(self, home, work_workspace):
        path = home / "config.json"
        path.write_text("{bad", encoding="utf-8")
        with pytest.raises(ValueError):
            gc.add_workspace("work", str(work_workspace))
        assert path.read_text(encoding="utf-8") == "{bad"

    def test_atomic_write_failure_preserves_previous(self, home, work_workspace):
        gc.add_workspace("work", str(work_workspace))
        original = (home / "config.json").read_text(encoding="utf-8")
        with mock.patch("os.replace", side_effect=OSError("replace failed")):
            with pytest.raises(OSError):
                gc.set_runtime("python")
        assert (home / "config.json").read_text(encoding="utf-8") == original


class TestGlobalConfigListing:
    def test_list_sorted_with_flags(self, home, work_workspace, test_workspace):
        gc.add_workspace("work", str(work_workspace), make_default=True)
        gc.add_workspace("test", str(test_workspace))
        missing = home / "missing"
        cfg = gc.load_global_config()
        cfg["workspaces"]["personal"] = str(missing.resolve())
        gc.save_global_config(cfg)
        payload = gc.list_workspaces()
        assert [entry["name"] for entry in payload["workspaces"]] == ["personal", "test", "work"]
        personal = next(entry for entry in payload["workspaces"] if entry["name"] == "personal")
        assert personal["available"] is False


class TestWorkspaceCommands:
    def test_list_human(self, home, work_workspace, capsys):
        gc.add_workspace("work", str(work_workspace), make_default=True)
        args = SimpleNamespace(workspace_action="list", json=False)
        assert workspace_commands.run(args) == 0
        out = capsys.readouterr().out
        assert "Registered workspaces (1):" in out
        assert "[default]" in out

    def test_show_unknown(self, home, capsys):
        args = SimpleNamespace(workspace_action="show", name="work", json=False)
        assert workspace_commands.run(args) == 1
        assert "Workspace not registered" in capsys.readouterr().out

    def test_default_none(self, home, capsys):
        args = SimpleNamespace(workspace_action="default", name=None, json=False)
        assert workspace_commands.run(args) == 1
        assert "No default workspace configured" in capsys.readouterr().out

    def test_remove_success(self, home, work_workspace, capsys):
        gc.add_workspace("work", str(work_workspace))
        args = SimpleNamespace(workspace_action="remove", name="work", json=False)
        assert workspace_commands.run(args) == 0
        assert "Removed workspace registration: work" in capsys.readouterr().out
        assert gc.load_global_config()["workspaces"] == {}


class TestConfigCommands:
    def test_show_json(self, home, work_workspace):
        gc.add_workspace("work", str(work_workspace), make_default=True)
        args = SimpleNamespace(config_action="show", json=True)
        with mock.patch("builtins.print") as printer:
            assert config_commands.run(args) == 0
        payload = json.loads(printer.call_args[0][0])
        assert payload["operation"] == "show"
        assert payload["runtime"] == "groovy"
        assert payload["default_workspace"] == "work"

    def test_runtime_show(self, home, capsys):
        args = SimpleNamespace(config_action="runtime", runtime=None, json=False)
        assert config_commands.run(args) == 0
        assert capsys.readouterr().out.strip() == "Runtime: groovy"

    def test_runtime_set(self, home, capsys):
        args = SimpleNamespace(config_action="runtime", runtime="python", json=False)
        assert config_commands.run(args) == 0
        assert gc.show_runtime()["runtime"] == "python"
        assert capsys.readouterr().out.strip() == "Runtime: python"
