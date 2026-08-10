"""
Unit tests for ai_worklog_framework.paths module.
"""

import os
import tempfile
from pathlib import Path

import pytest
from ai_worklog_framework.paths import (
    find_workspace_root,
    resolve_workspace,
    WorkspacePaths,
)


@pytest.fixture
def mock_workspace(tmp_path):
    (tmp_path / "worklog").mkdir()
    (tmp_path / "prompt.log").touch()
    (tmp_path / "jira").mkdir()
    return tmp_path


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
        assert result == mock_workspace

    def test_explicit_missing_raises(self, tmp_path):
        with pytest.raises(SystemExit):
            resolve_workspace(str(tmp_path / "nonexistent"))

    def test_env_variable_fallback(self, mock_workspace, monkeypatch):
        monkeypatch.setenv("AI_WORKLOG_WORKSPACE", str(mock_workspace))
        monkeypatch.chdir(mock_workspace / "repos" if (mock_workspace / "repos").exists() else mock_workspace)
        result = resolve_workspace()
        assert result == mock_workspace


class TestWorkspacePaths:
    def test_standard_paths(self, mock_workspace):
        wp = WorkspacePaths(mock_workspace)
        assert wp.worklog == mock_workspace / "worklog"
        assert wp.worklog_done == mock_workspace / "worklog" / "done"
        assert wp.state_dir == mock_workspace / ".ai-worklog" / "state"
        assert wp.prompt_log == mock_workspace / "prompt.log"

    def test_service_dir_prefers_interface(self, mock_workspace):
        iface = mock_workspace / "worklog" / "interface" / "jira"
        iface.mkdir(parents=True)
        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jira") == iface

    def test_service_dir_falls_back_to_root(self, mock_workspace):
        wp = WorkspacePaths(mock_workspace)
        assert wp.service_dir("jira") == mock_workspace / "jira"

    def test_ticket_state_file(self, mock_workspace):
        wp = WorkspacePaths(mock_workspace)
        result = wp.ticket_state_file("PROJ-1234")
        assert result == mock_workspace / ".ai-worklog" / "state" / "PROJ-1234.json"
