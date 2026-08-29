import hashlib
import os
from pathlib import Path

from ai_worklog_framework.shared import load_shared
from ai_worklog_framework.workspace.planner import apply_plan, plan_init, plan_revert


def _services():
    return list(load_shared("workspace-init.json", {}).get("services", []))


def test_workspace_init_is_idempotent(tmp_path):
    first = plan_init(tmp_path)
    apply_plan(first["actions"])

    assert (tmp_path / ".ai-worklog/state").is_dir()
    assert (tmp_path / ".ai-worklog/evidence").is_dir()
    assert (tmp_path / "worklog/done").is_dir()
    assert (tmp_path / "integrations").is_dir()
    assert (tmp_path / "repos").is_dir()
    assert (tmp_path / "tmp").is_dir()
    assert (tmp_path / ".ai-worklog/config.json").is_file()
    assert (tmp_path / ".ai-worklog/.gitignore").read_text() == "*\n!.gitignore\n"
    assert all(action["skip"] for action in plan_init(tmp_path)["actions"])


def test_workspace_init_creates_a_directory_for_every_service(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    for service in _services():
        target = tmp_path / "integrations" / service
        assert target.is_dir()
        assert not target.is_symlink()


def test_workspace_init_leaves_an_existing_symlink_alone(tmp_path):
    integrations = tmp_path / "integrations"
    integrations.mkdir()
    (tmp_path / "jira").mkdir()
    (integrations / "jira").symlink_to(Path("..") / "jira", target_is_directory=True)

    plan = plan_init(tmp_path)
    apply_plan(plan["actions"])

    assert (integrations / "jira").is_symlink()
    assert Path(os.readlink(integrations / "jira")).as_posix() == "../jira"


def test_workspace_init_leaves_an_unknown_integration_alone(tmp_path):
    unknown = tmp_path / "integrations" / "confluence"
    unknown.mkdir(parents=True)
    sentinel = unknown / "keep.txt"
    sentinel.write_text("keep", encoding="utf-8")

    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    assert sentinel.read_text(encoding="utf-8") == "keep"


def test_workspace_revert_removes_empty_created_service_directories(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    for service in _services():
        assert not (tmp_path / "integrations" / service).exists()


def test_workspace_revert_keeps_populated_service_directories(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    populated = tmp_path / "integrations/jira"
    (populated / "credentials").write_text("secret", encoding="utf-8")

    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    assert (populated / "credentials").read_text(encoding="utf-8") == "secret"


def test_workspace_revert_keeps_a_service_directory_it_did_not_create(tmp_path):
    preexisting = tmp_path / "integrations/jira"
    preexisting.mkdir(parents=True)

    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    assert preexisting.is_dir()


def test_workspace_revert_keeps_a_preexisting_symlink(tmp_path):
    (tmp_path / "jira").mkdir()
    integrations = tmp_path / "integrations"
    integrations.mkdir()
    (integrations / "jira").symlink_to(Path("..") / "jira", target_is_directory=True)

    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    assert (integrations / "jira").is_symlink()
    assert (tmp_path / "jira").is_dir()


def test_workspace_revert_never_removes_hubs_or_state(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    assert (tmp_path / "integrations").is_dir()
    assert (tmp_path / "worklog").is_dir()
    assert (tmp_path / "repos").is_dir()
    assert (tmp_path / "tmp").is_dir()
    assert (tmp_path / ".ai-worklog").is_dir()
    assert (tmp_path / ".ai-worklog/config.json").is_file()


def test_workspace_revert_keeps_unknown_integrations(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    unmanaged = tmp_path / "integrations/custom"
    unmanaged.mkdir()
    (unmanaged / "keep.txt").write_text("keep", encoding="utf-8")

    apply_plan(plan_revert(tmp_path)["actions"], tmp_path)

    assert (unmanaged / "keep.txt").read_text(encoding="utf-8") == "keep"


def test_apply_is_byte_identical_for_preexisting_content(tmp_path):
    legacy = {
        "worklog/done/2026-01-01_TICKET.log": "worklog entry\n",
        "integrations/jira/credentials": "token\n",
        "integrations/confluence/notes.md": "# notes\n",
        "repos/checkout/README.md": "readme\n",
    }
    for relative, content in legacy.items():
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    before = _checksums(tmp_path)
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    after = _checksums(tmp_path)

    for relative, digest in before.items():
        assert after[relative] == digest


def _checksums(root):
    digests = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and not path.is_symlink():
            digests[path.relative_to(root).as_posix()] = hashlib.sha256(
                path.read_bytes()
            ).hexdigest()
    return digests
