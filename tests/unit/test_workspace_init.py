from pathlib import Path

from ai_worklog_framework.workspace.planner import (
    apply_plan,
    plan_init,
    plan_revert,
)


def test_workspace_init_is_idempotent(tmp_path):
    (tmp_path / "jira").mkdir()
    first = plan_init(tmp_path)
    apply_plan(first)

    assert (tmp_path / ".ai-worklog/state").is_dir()
    assert (tmp_path / ".ai-worklog/evidence").is_dir()
    assert (tmp_path / "worklog/done").is_dir()
    assert (tmp_path / ".ai-worklog/config.json").is_file()
    assert (tmp_path / "worklog/interface/jira").is_symlink()
    assert all(action["skip"] for action in plan_init(tmp_path))


def test_workspace_revert_only_removes_managed_links(tmp_path):
    (tmp_path / "jira").mkdir()
    apply_plan(plan_init(tmp_path))
    unmanaged = tmp_path / "worklog/interface/custom"
    unmanaged.mkdir()

    apply_plan(plan_revert(tmp_path))

    assert not (tmp_path / "worklog/interface/jira").exists()
    assert unmanaged.is_dir()
