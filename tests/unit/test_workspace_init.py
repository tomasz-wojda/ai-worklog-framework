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
    assert os.readlink(tmp_path / "integrations/jira") == "../jira"
    assert all(action["skip"] for action in plan_init(tmp_path)["actions"])


def test_workspace_init_migrates_legacy_managed_links(tmp_path):
    (tmp_path / "jira").mkdir()
    legacy = tmp_path / "worklog/interface"
    legacy.mkdir(parents=True)
    (legacy / "jira").symlink_to(Path("../../jira"))

    apply_plan(plan_init(tmp_path)["actions"])

    assert (tmp_path / "integrations/jira").is_symlink()
    assert os.readlink(tmp_path / "integrations/jira") == "../jira"
    assert not (legacy / "jira").exists()
    assert not legacy.exists()


def test_workspace_init_preserves_unmanaged_legacy_content(tmp_path):
    (tmp_path / "jira").mkdir()
    legacy = tmp_path / "worklog/interface/custom"
    legacy.mkdir(parents=True)

    plan = plan_init(tmp_path)
    assert not plan["conflicts"]
    apply_plan(plan["actions"])
    assert legacy.is_dir()
    assert legacy_integration_status(tmp_path) == (
        "Legacy integration hub contains 1 unmanaged item; move them to integrations/"
    )


def test_workspace_init_accepts_canonical_integration_directory(tmp_path):
    (tmp_path / "jira").mkdir()
    canonical = tmp_path / "integrations" / "jira"
    canonical.parent.mkdir()
    canonical.mkdir()

    plan = plan_init(tmp_path)
    assert not plan["conflicts"]
    symlink_actions = [
        action
        for action in plan["actions"]
        if action["kind"] == "symlink" and action["target"] == canonical
    ]
    assert symlink_actions and all(action["skip"] for action in symlink_actions)
    assert symlink_actions[0]["reason"] == "integration present"


def test_workspace_init_blocks_foreign_canonical_symlink(tmp_path):
    (tmp_path / "jira").mkdir()
    canonical = tmp_path / "integrations" / "jira"
    canonical.parent.mkdir()
    canonical.symlink_to("../other")

    plan = plan_init(tmp_path)

    assert plan["conflicts"] == [{
        "path": str(canonical),
        "reason": "foreign symlink",
    }]


def test_workspace_revert_only_removes_managed_links(tmp_path):
    (tmp_path / "jira").mkdir()
    apply_plan(plan_init(tmp_path)["actions"])
    unmanaged = tmp_path / "integrations/custom"
    unmanaged.mkdir()

    apply_plan(plan_revert(tmp_path)["actions"])

    assert not (tmp_path / "integrations/jira").exists()
    assert unmanaged.is_dir()


def test_workspace_revert_removes_legacy_managed_links(tmp_path):
    (tmp_path / "jira").mkdir()
    legacy = tmp_path / "worklog/interface"
    legacy.mkdir(parents=True)
    (legacy / "jira").symlink_to(Path("../../jira"))

    apply_plan(plan_revert(tmp_path)["actions"])

    assert not (legacy / "jira").exists()
    assert (tmp_path / "jira").is_dir()


def test_workspace_revert_removes_empty_managed_hubs(tmp_path):
    (tmp_path / "jira").mkdir()
    apply_plan(plan_init(tmp_path)["actions"])

    apply_plan(plan_revert(tmp_path)["actions"])

    assert not (tmp_path / "integrations").exists()
