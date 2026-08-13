import os
from pathlib import Path

from ai_worklog_framework.workspace.planner import (
    apply_plan,
    legacy_integration_status,
    plan_init,
    plan_revert,
)


def test_workspace_init_is_idempotent(tmp_path):
    (tmp_path / "jira").mkdir()
    first = plan_init(tmp_path)
    apply_plan(first["actions"])

    assert (tmp_path / ".ai-worklog/state").is_dir()
    assert (tmp_path / ".ai-worklog/evidence").is_dir()
    assert (tmp_path / "worklog/done").is_dir()
    assert (tmp_path / "integrations").is_dir()
    assert (tmp_path / ".ai-worklog/config.json").is_file()
    assert (tmp_path / ".ai-worklog/.gitignore").read_text() == "*\n!.gitignore\n"
    assert (tmp_path / "integrations/jira").is_symlink()
    assert Path(os.readlink(tmp_path / "integrations/jira")).as_posix() == "../jira"
    assert all(action["skip"] for action in plan_init(tmp_path)["actions"])


def test_workspace_revert_only_removes_managed_links(tmp_path):
    (tmp_path / "jira").mkdir()
    apply_plan(plan_init(tmp_path)["actions"])
    unmanaged = tmp_path / "integrations/custom"
    unmanaged.mkdir()

    apply_plan(plan_revert(tmp_path)["actions"])

    assert not (tmp_path / "integrations/jira").exists()
    assert unmanaged.is_dir()


def test_workspace_revert_removes_empty_managed_hubs(tmp_path):
    (tmp_path / "jira").mkdir()
    apply_plan(plan_init(tmp_path)["actions"])

    apply_plan(plan_revert(tmp_path)["actions"])

    assert not (tmp_path / "integrations").exists()
