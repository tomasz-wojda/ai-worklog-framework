import json
import os
import stat
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

from ai_worklog_framework import global_config as gc
from ai_worklog_framework.adapters import jenkins
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.setup import commands as setup_commands
from ai_worklog_framework.setup.manifest import load_manifest, manifest_path, save_manifest, tree_checksum
from ai_worklog_framework.setup.materialize import inspect_destination, plan_skill_materialization
from ai_worklog_framework.setup.planner import apply_init_or_repair_plan, plan_setup_init, plan_setup_revert
from ai_worklog_framework.setup.resolver import detect_ides, normalize_ide_selection, resolve_ai_vault_root
from ai_worklog_framework.setup.vault import validate_vault_root


VAULT_MANIFEST = {
    "version": 1,
    "skills": [
        {
            "name": "developer-protocol",
            "dir": "developer-protocol",
            "required": True,
            "ides": ["cursor", "claude", "antigravity"],
        },
        {
            "name": "devops-daily-protocol",
            "dir": "devops-daily-protocol",
            "required": True,
            "ides": ["cursor"],
        },
    ],
}


@pytest.fixture
def home(tmp_path, monkeypatch):
    root = tmp_path / "home"
    root.mkdir()
    monkeypatch.setenv("AI_WORKLOG_HOME", str(root))
    return root


def _make_vault(root: Path) -> Path:
    vault = root / "ai-vault"
    vault.mkdir(parents=True)
    scripts = vault / "scripts"
    scripts.mkdir()
    script = scripts / "validate-skills.sh"
    script.write_text("#!/bin/sh\n", encoding="utf-8")
    script.chmod(stat.S_IRWXU)
    skills = vault / "skills"
    skills.mkdir()
    (skills / "manifest.json").write_text(json.dumps(VAULT_MANIFEST), encoding="utf-8")
    for entry in VAULT_MANIFEST["skills"]:
        skill_dir = skills / entry["dir"]
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text("# Skill\n", encoding="utf-8")
    return vault


def _make_workspace(root: Path) -> Path:
    ws = root / "workspace"
    ws.mkdir()
    (ws / "worklog").mkdir()
    (ws / "prompt.log").touch()
    return ws


class TestAiVaultResolver:
    def test_precedence_cli_env_global_fallback(self, home, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        cli_vault = _make_vault(tmp_path / "cli")
        env_vault = _make_vault(tmp_path / "env")
        global_vault = _make_vault(tmp_path / "global")
        fallback = _make_vault(ws / "repos")

        path, source = resolve_ai_vault_root(ws, cli_override=str(cli_vault))
        assert path == cli_vault.resolve()
        assert source == "cli"

        path, source = resolve_ai_vault_root(ws, environment={"AI_WORKLOG_AI_VAULT_ROOT": str(env_vault)})
        assert path == env_vault.resolve()
        assert source == "env"

        gc.set_ai_vault_root(str(global_vault))
        path, source = resolve_ai_vault_root(ws)
        assert path == global_vault.resolve()
        assert source == "global"

        gc.set_ai_vault_root(None)
        path, source = resolve_ai_vault_root(ws)
        assert path == fallback.resolve()
        assert source == "workspace_fallback"

    def test_validate_vault(self, tmp_path):
        vault = _make_vault(tmp_path)
        valid, message, manifest = validate_vault_root(vault)
        assert valid is True
        assert message == "valid"
        assert len(manifest["skills"]) == 2


class TestIdeDetection:
    def test_detect_from_workspace_marker(self, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        (ws / ".cursor").mkdir()
        monkeypatch.setattr("shutil.which", lambda *_args, **_kwargs: None)
        assert "cursor" in detect_ides(ws)

    def test_normalize_auto_merges_existing(self, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        (ws / ".claude").mkdir()
        monkeypatch.setattr("shutil.which", lambda *_args, **_kwargs: None)
        ides = normalize_ide_selection(["auto"], ["cursor"], ws)
        assert ides == ["claude", "cursor"]

    def test_auto_cannot_mix_with_explicit(self, tmp_path):
        ws = _make_workspace(tmp_path)
        with pytest.raises(ValueError, match="cannot be combined"):
            normalize_ide_selection(["auto", "cursor"], [], ws)

    def test_no_detected_ide_blocks(self, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda *_args, **_kwargs: None)
        monkeypatch.setattr(
            "ai_worklog_framework.setup.resolver.detect_ides",
            lambda *_args, **_kwargs: [],
        )
        with pytest.raises(ValueError, match="No IDE detected"):
            normalize_ide_selection(None, [], ws)


class TestManifestAndMaterialize:
    def test_tree_checksum_changes_when_content_changes(self, tmp_path):
        target = tmp_path / "skill"
        target.mkdir()
        (target / "SKILL.md").write_text("a", encoding="utf-8")
        first = tree_checksum(target)
        (target / "SKILL.md").write_text("b", encoding="utf-8")
        assert tree_checksum(target) != first

    def test_symlink_conflict_and_adopt(self, tmp_path):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        source = vault / "skills/developer-protocol"
        destination = ws / ".cursor/skills/developer-protocol"
        destination.parent.mkdir(parents=True)
        destination.symlink_to(source.resolve())
        disposition, reason = inspect_destination(destination, source, "symlink", None, True)
        assert disposition == "adopt"
        foreign = ws / ".cursor/skills/devops-daily-protocol"
        foreign.mkdir()
        disposition, reason = inspect_destination(
            foreign,
            vault / "skills/devops-daily-protocol",
            "symlink",
            None,
            False,
        )
        assert disposition == "conflict"

    def test_copy_modified_conflict(self, tmp_path):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        source = vault / "skills/developer-protocol"
        destination = ws / ".agents/skills/developer-protocol"
        destination.parent.mkdir(parents=True)
        import shutil

        shutil.copytree(source, destination)
        applied = tree_checksum(destination)
        entry = {
            "materialization": "copy",
            "applied_checksum": applied,
            "source": str(source),
        }
        (destination / "SKILL.md").write_text("changed", encoding="utf-8")
        disposition, reason = inspect_destination(destination, source, "copy", entry, False)
        assert disposition == "conflict"
        assert reason == "modified copy"

    def test_manifest_atomic_write(self, tmp_path):
        ws = _make_workspace(tmp_path)
        payload = {
            "version": 1,
            "workspace_name": "work",
            "ai_vault_root": str(tmp_path),
            "ides": ["cursor"],
            "skills": [],
            "synced_at": "2026-01-01T00:00:00+00:00",
        }
        save_manifest(ws, payload)
        loaded = load_manifest(ws)
        assert loaded["workspace_name"] == "work"
        assert stat.S_IMODE(manifest_path(ws).stat().st_mode) in (0o644, 0o600, 0o666)


class TestPlanner:
    def test_plan_init_creates_skill_actions(self, tmp_path):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        plan = plan_setup_init(
            workspace=ws,
            vault_root=vault,
            vault_manifest=VAULT_MANIFEST,
            ides=["cursor"],
            adopt=False,
        )
        assert plan["skill_actions"]
        assert plan["workspace_actions"]

    def test_apply_init_writes_manifest_and_symlinks(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)
        plan = plan_setup_init(
            workspace=ws,
            vault_root=vault,
            vault_manifest=VAULT_MANIFEST,
            ides=["cursor"],
            adopt=True,
        )
        apply_init_or_repair_plan(
            workspace=ws,
            workspace_name="work",
            vault_root=vault,
            ides=["cursor"],
            plan=plan,
        )
        manifest = load_manifest(ws)
        assert manifest is not None
        assert manifest["workspace_name"] == "work"
        link = ws / ".cursor/skills/developer-protocol"
        assert link.is_symlink()
        assert link.resolve() == (vault / "skills/developer-protocol").resolve()


class TestSetupCommands:
    def test_init_dry_run_then_apply(self, home, tmp_path, monkeypatch, capsys):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)
        args = SimpleNamespace(
            setup_action="init",
            name="work",
            path=str(ws),
            ide=["cursor"],
            runtime=None,
            ai_vault=str(vault),
            default=True,
            json=False,
            apply=False,
        )
        assert setup_commands.run_init(args) == 0
        output = capsys.readouterr().out
        assert "pending actions" in output
        assert "Re-run with --apply" in output

        args.apply = True
        assert setup_commands.run_init(args) == 0
        cfg = gc.load_global_config()
        assert cfg["workspaces"]["work"]["ides"] == ["cursor"]
        assert cfg["ai_vault_root"] == str(vault.resolve())
        assert load_manifest(ws) is not None

    def test_init_merges_ides(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        gc.add_workspace("work", str(ws))
        gc.set_workspace_ides("work", ["claude"])
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)
        args = SimpleNamespace(
            setup_action="init",
            name="work",
            path=str(ws),
            ide=["cursor"],
            runtime=None,
            ai_vault=str(vault),
            default=False,
            json=True,
            apply=True,
        )
        setup_commands.run_init(args)
        assert gc.load_global_config()["workspaces"]["work"]["ides"] == ["claude", "cursor"]

    def test_revert_removes_cursor_only(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)
        init_args = SimpleNamespace(
            setup_action="init",
            name="work",
            path=str(ws),
            ide=["cursor", "claude"],
            runtime=None,
            ai_vault=str(vault),
            default=True,
            json=False,
            apply=True,
        )
        setup_commands.run_init(init_args)
        gc.add_workspace("work", str(ws))
        gc.set_workspace_ides("work", ["cursor", "claude"])

        revert_args = SimpleNamespace(
            setup_action="revert",
            workspace=str(ws),
            workspace_name="work",
            ide=["cursor"],
            json=False,
            apply=True,
        )
        setup_commands.run_revert(revert_args)
        assert not (ws / ".cursor/skills/developer-protocol").exists()
        assert gc.load_global_config()["workspaces"]["work"]["ides"] == ["claude"]

    def test_show_and_check_registered_workspace(self, home, tmp_path, monkeypatch, capsys):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        gc.add_workspace("work", str(ws), make_default=True)
        gc.set_workspace_ides("work", ["cursor"])
        gc.set_ai_vault_root(str(vault))
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)

        show_args = SimpleNamespace(
            setup_action="show",
            workspace=str(ws),
            workspace_name="work",
            json=True,
        )
        assert setup_commands.run_show(show_args) == 0

        check_args = SimpleNamespace(
            setup_action="check",
            workspace=str(ws),
            workspace_name="work",
            json=True,
        )
        code = setup_commands.run_check(check_args)
        assert code in (0, 1, 3)


class TestMigrationIntegration:
    def test_v1_global_config_used_by_setup(self, home, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        vault = _make_vault(tmp_path)
        home.mkdir(mode=0o700, parents=True, exist_ok=True)
        (home / "config.json").write_text(
            json.dumps(
                {
                    "version": 1,
                    "runtime": "groovy",
                    "workspaces": {"work": str(ws)},
                }
            ),
            encoding="utf-8",
        )
        cfg = gc.load_global_config()
        assert cfg["version"] == 2
        assert cfg["workspaces"]["work"]["ides"] == []
        monkeypatch.setattr(
            "shutil.which",
            lambda name: "/usr/bin/groovy" if name == "groovy" else ("/usr/bin/python3" if name == "python3" else None),
        )
        args = SimpleNamespace(
            setup_action="init",
            name="work",
            path=str(ws),
            ide=["cursor"],
            runtime="python",
            ai_vault=str(vault),
            default=False,
            json=False,
            apply=True,
        )
        setup_commands.run_init(args)
        saved = json.loads((home / "config.json").read_text(encoding="utf-8"))
        assert saved["version"] == 2
        assert saved["runtime"] == "python"
        assert saved["ai_vault_root"] == str(vault.resolve())


class TestJenkinsVaultFallback:
    def test_global_ai_vault_used_for_syntax_check(self, home, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        vault = _make_vault(tmp_path)
        gc.set_ai_vault_root(str(vault))
        monkeypatch.delenv("AI_VAULT_ROOT", raising=False)
        script = jenkins.resolve_syntax_check_script(WorkspacePaths(ws))
        expected = vault / "skills/jenkins-pipeline-architect/scripts/syntax_check.sh"
        assert script is None or script.parent.name == "scripts"

    def test_workspace_override_preserved(self, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        explicit = tmp_path / "explicit.sh"
        explicit.write_text("#!/bin/sh\n", encoding="utf-8")
        explicit.chmod(stat.S_IRWXU)
        config_dir = ws / ".ai-worklog"
        config_dir.mkdir(parents=True)
        (config_dir / "config.json").write_text(
            json.dumps({"adapters": {"jenkins": {"syntax_check_script": str(explicit)}}}),
            encoding="utf-8",
        )
        monkeypatch.delenv("AI_VAULT_ROOT", raising=False)
        assert jenkins.resolve_syntax_check_script(WorkspacePaths(ws)) == explicit.resolve()
