import os
import shutil
from pathlib import Path
from types import SimpleNamespace

import pytest

from ai_worklog_framework import global_config as gc
from ai_worklog_framework.paths import (
    WorkspaceResolution,
    find_workspace_root,
    resolve_workspace,
    resolve_workspace_detailed,
)
from ai_worklog_framework.workspace import commands as workspace_commands


@pytest.fixture
def mock_workspace(tmp_path):
    (tmp_path / "worklog").mkdir()
    (tmp_path / "prompt.log").touch()
    (tmp_path / "jira").mkdir()
    return tmp_path


@pytest.fixture
def home(tmp_path, monkeypatch):
    root = tmp_path / "home"
    root.mkdir()
    monkeypatch.setenv("AI_WORKLOG_HOME", str(root))
    return root


class TestFindWorkspaceRoot:
    def test_finds_by_worklog_marker(self, mock_workspace):
        subdir = mock_workspace / "repos" / "some-project"
        subdir.mkdir(parents=True)
        found = find_workspace_root(subdir)
        assert found == mock_workspace

    def test_finds_by_prompt_log(self, tmp_path):
        (tmp_path / "prompt.log").touch()
        found = find_workspace_root(tmp_path)
        assert found == tmp_path

    def test_returns_none_when_not_found(self, tmp_path):
        empty = tmp_path / "empty"
        empty.mkdir()
        found = find_workspace_root(empty)
        assert found is None


class TestResolveWorkspace:
    def test_explicit_path(self, mock_workspace):
        result = resolve_workspace(str(mock_workspace))
        assert result == mock_workspace.resolve()

    def test_explicit_missing_raises(self, tmp_path):
        with pytest.raises(ValueError, match="Workspace not found"):
            resolve_workspace(str(tmp_path / "nonexistent"))

    def test_explicit_name(self, home, mock_workspace):
        gc.add_workspace("work", str(mock_workspace), make_default=True)
        result = resolve_workspace(explicit_name="work")
        assert result == mock_workspace.resolve()

    def test_unknown_explicit_name(self, home):
        with pytest.raises(ValueError, match="Workspace not registered"):
            resolve_workspace(explicit_name="work")

    def test_env_path(self, mock_workspace, monkeypatch):
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE", str(mock_workspace))
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        result = resolve_workspace()
        assert result == mock_workspace.resolve()

    def test_invalid_env_path(self, monkeypatch, tmp_path):
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE", str(tmp_path / "missing"))
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        with pytest.raises(ValueError, match="Workspace not found"):
            resolve_workspace()

    def test_env_name(self, home, mock_workspace, monkeypatch):
        gc.add_workspace("work", str(mock_workspace))
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE_NAME", "work")
        result = resolve_workspace()
        assert result == mock_workspace.resolve()

    def test_unknown_env_name(self, home, monkeypatch):
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE_NAME", "work")
        with pytest.raises(ValueError, match="Workspace not registered"):
            resolve_workspace()

    def test_cwd_before_default(self, home, mock_workspace, monkeypatch):
        saved = mock_workspace.parent / "saved-default"
        saved.mkdir()
        (saved / "worklog").mkdir()
        gc.add_workspace("saved", str(saved), make_default=True)
        subdir = mock_workspace / "repos" / "proj"
        subdir.mkdir(parents=True)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        monkeypatch.chdir(subdir)
        result = resolve_workspace()
        assert result == mock_workspace.resolve()

    def test_env_path_before_cwd(self, mock_workspace, monkeypatch):
        other = mock_workspace.parent / "other"
        other.mkdir()
        (other / "worklog").mkdir()
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE", str(other))
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        monkeypatch.chdir(mock_workspace)
        result = resolve_workspace()
        assert result == other.resolve()

    def test_default_workspace(self, home, mock_workspace, monkeypatch, tmp_path):
        outside = tmp_path.parent / "outside-default"
        outside.mkdir()
        empty = outside / "empty"
        empty.mkdir()
        monkeypatch.chdir(empty)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        gc.add_workspace("work", str(mock_workspace), make_default=True)
        result = resolve_workspace()
        assert result == mock_workspace.resolve()

    def test_stale_default_path(self, home, monkeypatch, tmp_path):
        workspace = tmp_path / "registered"
        workspace.mkdir()
        (workspace / "worklog").mkdir()
        outside = tmp_path.parent / "outside-stale"
        outside.mkdir()
        empty = outside / "nowhere"
        empty.mkdir()
        monkeypatch.chdir(empty)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        gc.add_workspace("work", str(workspace), make_default=True)
        shutil.rmtree(workspace)
        with pytest.raises(ValueError, match="Registered workspace path is unavailable"):
            resolve_workspace()

    def test_no_resolution_raises(self, home, monkeypatch, tmp_path):
        outside = tmp_path.parent / "outside-none"
        outside.mkdir()
        empty = outside / "isolated"
        empty.mkdir()
        monkeypatch.chdir(empty)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        with pytest.raises(ValueError, match="Cannot locate workspace"):
            resolve_workspace()


class TestResolveWorkspaceDetailed:
    def test_source_explicit_path(self, mock_workspace):
        resolution = resolve_workspace_detailed(str(mock_workspace))
        assert resolution == WorkspaceResolution(
            path=mock_workspace.resolve(),
            source="explicit_path",
        )

    def test_source_explicit_name(self, home, mock_workspace):
        gc.add_workspace("work", str(mock_workspace))
        resolution = resolve_workspace_detailed(explicit_name="work")
        assert resolution.source == "workspace_name"
        assert resolution.name == "work"

    def test_source_env_name(self, home, mock_workspace, monkeypatch):
        gc.add_workspace("work", str(mock_workspace))
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE_NAME", "work")
        resolution = resolve_workspace_detailed()
        assert resolution.source == "env_name"
        assert resolution.name == "work"

    def test_source_default(self, home, mock_workspace, monkeypatch, tmp_path):
        outside = tmp_path.parent / "outside"
        outside.mkdir()
        empty = outside / "empty"
        empty.mkdir()
        monkeypatch.chdir(empty)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE", raising=False)
        monkeypatch.delenv("AI_WORKLOG_WORKSPACE_NAME", raising=False)
        gc.add_workspace("work", str(mock_workspace), make_default=True)
        resolution = resolve_workspace_detailed()
        assert resolution.source == "default_workspace"
        assert resolution.name == "work"


class TestWorkspaceCurrentCommand:
    def test_current_json(self, home, mock_workspace, capsys):
        gc.add_workspace("work", str(mock_workspace), make_default=True)
        args = SimpleNamespace(
            workspace_action="current",
            workspace_name="work",
            json=True,
        )
        assert workspace_commands.run(args) == 0
        import json

        payload = json.loads(capsys.readouterr().out)
        assert payload["source"] == "workspace_name"
        assert payload["name"] == "work"
        assert payload["path"] == str(mock_workspace.resolve())


class TestWorkspacePaths:
    def test_standard_paths(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        wp = WorkspacePaths(mock_workspace)
        assert wp.worklog == mock_workspace / "worklog"
        assert wp.worklog_done == mock_workspace / "worklog" / "done"
        assert wp.state_dir == mock_workspace / ".ai-worklog" / "state"
        assert wp.integrations_dir == mock_workspace / "integrations"
        assert wp.interface_dir == mock_workspace / "integrations"
        assert wp.prompt_log == mock_workspace / "prompt.log"

    def test_service_dir_prefers_integrations(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        integrations = mock_workspace / "integrations" / "jira"
        integrations.mkdir(parents=True)
        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jira") == integrations

    def test_service_dir_falls_back_to_legacy_interface(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        legacy = mock_workspace / "worklog" / "interface" / "jira"
        legacy.mkdir(parents=True)
        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jira") == legacy

    def test_service_dir_falls_back_to_root(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jira") == mock_workspace / "jira"

    def test_service_dir_missing_returns_canonical(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jenkins") == mock_workspace / "integrations" / "jenkins"

    def test_ticket_state_file(self, mock_workspace):
        from ai_worklog_framework.paths import WorkspacePaths

        wp = WorkspacePaths(mock_workspace)
        result = wp.ticket_state_file("PROJ-1234")
        assert result == mock_workspace / ".ai-worklog" / "state" / "PROJ-1234.json"
