import json
import stat
from pathlib import Path
from types import SimpleNamespace

import pytest

from ai_worklog_framework import global_config as gc
from ai_worklog_framework.setup.manifest import save_manifest
from ai_worklog_framework.setup.planner import plan_setup_init
from ai_worklog_framework.setup.report import build_action_report, build_check_report, build_show_report
from ai_worklog_framework.setup.vault import validate_vault_root

_REPORT_TOP_KEYS = {
    "operation",
    "status",
    "message",
    "workspace",
    "runtime",
    "ai_vault",
    "ides",
    "checks",
    "actions",
    "conflicts",
    "pending_actions",
    "manifest",
}
_REPORT_OPERATIONS = {"init", "check", "show", "repair", "revert"}
_REPORT_STATUSES = {"ready", "degraded", "blocked", "error"}
_CHECK_STATUSES = _REPORT_STATUSES


def validate_setup_report(report: dict) -> None:
    if not isinstance(report, dict):
        raise AssertionError("report must be an object")

    extra = set(report) - _REPORT_TOP_KEYS
    if extra:
        raise AssertionError(f"unexpected top-level keys: {sorted(extra)}")

    missing = {"operation", "status", "message"} - set(report)
    if missing:
        raise AssertionError(f"missing required keys: {sorted(missing)}")

    if report["operation"] not in _REPORT_OPERATIONS:
        raise AssertionError(f"invalid operation: {report['operation']}")
    if report["status"] not in _REPORT_STATUSES:
        raise AssertionError(f"invalid status: {report['status']}")
    if not isinstance(report["message"], str):
        raise AssertionError("message must be a string")

    workspace = report.get("workspace")
    if workspace is not None:
        if not isinstance(workspace, dict):
            raise AssertionError("workspace must be an object")
        allowed = {"name", "path", "default", "registered", "available"}
        unknown = set(workspace) - allowed
        if unknown:
            raise AssertionError(f"unexpected workspace keys: {sorted(unknown)}")
        if workspace.get("name") is not None and not isinstance(workspace["name"], str):
            raise AssertionError("workspace.name must be string or null")
        if "path" in workspace and not isinstance(workspace["path"], str):
            raise AssertionError("workspace.path must be a string")
        for key in ("default", "registered", "available"):
            if key in workspace and not isinstance(workspace[key], bool):
                raise AssertionError(f"workspace.{key} must be a boolean")

    runtime = report.get("runtime")
    if runtime is not None:
        if not isinstance(runtime, dict):
            raise AssertionError("runtime must be an object")
        allowed = {"value", "source", "available"}
        unknown = set(runtime) - allowed
        if unknown:
            raise AssertionError(f"unexpected runtime keys: {sorted(unknown)}")
        for key in ("value", "source"):
            if key in runtime and not isinstance(runtime[key], str):
                raise AssertionError(f"runtime.{key} must be a string")
        if "available" in runtime and not isinstance(runtime["available"], bool):
            raise AssertionError("runtime.available must be a boolean")

    ai_vault = report.get("ai_vault")
    if ai_vault is not None:
        if not isinstance(ai_vault, dict):
            raise AssertionError("ai_vault must be an object")
        allowed = {"path", "source", "valid"}
        unknown = set(ai_vault) - allowed
        if unknown:
            raise AssertionError(f"unexpected ai_vault keys: {sorted(unknown)}")
        for key in ("path", "source"):
            if key in ai_vault and ai_vault[key] is not None and not isinstance(ai_vault[key], str):
                raise AssertionError(f"ai_vault.{key} must be string or null")
        if "valid" in ai_vault and not isinstance(ai_vault["valid"], bool):
            raise AssertionError("ai_vault.valid must be a boolean")

    ides = report.get("ides")
    if ides is not None:
        if not isinstance(ides, list):
            raise AssertionError("ides must be a list")
        for item in ides:
            if not isinstance(item, dict):
                raise AssertionError("ides item must be an object")
            allowed = {"id", "destination", "materialization", "managed_count", "conflict_count"}
            unknown = set(item) - allowed
            if unknown:
                raise AssertionError(f"unexpected ides item keys: {sorted(unknown)}")
            for key in ("id", "destination", "materialization"):
                if key in item and not isinstance(item[key], str):
                    raise AssertionError(f"ides item {key} must be a string")
            for key in ("managed_count", "conflict_count"):
                if key in item and not isinstance(item[key], int):
                    raise AssertionError(f"ides item {key} must be an integer")

    checks = report.get("checks")
    if checks is not None:
        if not isinstance(checks, list):
            raise AssertionError("checks must be a list")
        for item in checks:
            if not isinstance(item, dict):
                raise AssertionError("checks item must be an object")
            allowed = {"layer", "status", "message"}
            unknown = set(item) - allowed
            if unknown:
                raise AssertionError(f"unexpected checks item keys: {sorted(unknown)}")
            missing = allowed - set(item)
            if missing:
                raise AssertionError(f"checks item missing keys: {sorted(missing)}")
            if item["status"] not in _CHECK_STATUSES:
                raise AssertionError(f"invalid check status: {item['status']}")
            if not isinstance(item["layer"], str) or not isinstance(item["message"], str):
                raise AssertionError("check layer and message must be strings")

    actions = report.get("actions")
    if actions is not None:
        if not isinstance(actions, list):
            raise AssertionError("actions must be a list")
        for item in actions:
            if not isinstance(item, dict):
                raise AssertionError("actions item must be an object")
            allowed = {"kind", "target", "source", "skip", "reason"}
            unknown = set(item) - allowed
            if unknown:
                raise AssertionError(f"unexpected actions item keys: {sorted(unknown)}")
            for key in ("kind", "target", "reason"):
                if key in item and not isinstance(item[key], str):
                    raise AssertionError(f"actions item {key} must be a string")
            if "source" in item and item["source"] is not None and not isinstance(item["source"], str):
                raise AssertionError("actions item source must be string or null")
            if "skip" in item and not isinstance(item["skip"], bool):
                raise AssertionError("actions item skip must be a boolean")

    conflicts = report.get("conflicts")
    if conflicts is not None:
        if not isinstance(conflicts, list):
            raise AssertionError("conflicts must be a list")
        for item in conflicts:
            if not isinstance(item, dict):
                raise AssertionError("conflicts item must be an object")
            allowed = {"path", "reason"}
            unknown = set(item) - allowed
            if unknown:
                raise AssertionError(f"unexpected conflicts item keys: {sorted(unknown)}")
            for key in allowed:
                if key in item and not isinstance(item[key], str):
                    raise AssertionError(f"conflicts item {key} must be a string")

    pending = report.get("pending_actions")
    if pending is not None and (not isinstance(pending, int) or pending < 0):
        raise AssertionError("pending_actions must be a non-negative integer")

    manifest = report.get("manifest")
    if manifest is not None:
        if not isinstance(manifest, dict):
            raise AssertionError("manifest must be an object")
        allowed = {"version", "synced_at", "skill_count"}
        unknown = set(manifest) - allowed
        if unknown:
            raise AssertionError(f"unexpected manifest keys: {sorted(unknown)}")
        if "version" in manifest and not isinstance(manifest["version"], int):
            raise AssertionError("manifest.version must be an integer")
        if "synced_at" in manifest and manifest["synced_at"] is not None and not isinstance(
            manifest["synced_at"], str
        ):
            raise AssertionError("manifest.synced_at must be string or null")
        if "skill_count" in manifest and not isinstance(manifest["skill_count"], int):
            raise AssertionError("manifest.skill_count must be an integer")


VAULT_MANIFEST = {
    "version": 1,
    "skills": [
        {
            "name": "developer-protocol",
            "dir": "developer-protocol",
            "required": True,
            "ides": ["cursor"],
        }
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
    skill_dir = skills / "developer-protocol"
    skill_dir.mkdir()
    (skill_dir / "SKILL.md").write_text("# Skill\n", encoding="utf-8")
    return vault


def _make_workspace(root: Path) -> Path:
    ws = root / "workspace"
    ws.mkdir()
    (ws / "worklog").mkdir()
    (ws / "prompt.log").touch()
    return ws


class TestSetupReportSchema:
    def test_validator_rejects_unknown_top_level_key(self):
        with pytest.raises(AssertionError, match="unexpected top-level keys"):
            validate_setup_report(
                {"operation": "show", "status": "ready", "message": "ok", "extra": True}
            )

    def test_show_report_conforms(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        gc.add_workspace("work", str(ws), make_default=True)
        gc.set_workspace_ides("work", ["cursor"])
        gc.set_ai_vault_root(str(vault))
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)

        report = build_show_report(
            workspace=ws,
            workspace_name="work",
            registered=True,
            is_default=True,
        )
        validate_setup_report(report)

    def test_check_report_conforms(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        gc.add_workspace("work", str(ws))
        gc.set_workspace_ides("work", ["cursor"])
        gc.set_ai_vault_root(str(vault))
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)

        report = build_check_report(
            workspace=ws,
            workspace_name="work",
            registered=True,
            is_default=False,
        )
        validate_setup_report(report)

    def test_action_report_conforms(self, home, tmp_path, monkeypatch):
        vault = _make_vault(tmp_path)
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/groovy" if name == "groovy" else None)
        valid, _, vault_manifest = validate_vault_root(vault)
        assert valid
        plan = plan_setup_init(
            workspace=ws,
            vault_root=vault,
            vault_manifest=vault_manifest,
            ides=["cursor"],
            adopt=False,
        )
        report = build_action_report(
            operation="init",
            workspace=ws,
            workspace_name="work",
            plan=plan,
            runtime="groovy",
            runtime_source="explicit",
            vault_root=vault,
            vault_source="cli",
            ides=["cursor"],
            apply=False,
        )
        validate_setup_report(report)

    def test_manifest_summary_without_manifest(self, home, tmp_path, monkeypatch):
        ws = _make_workspace(tmp_path)
        monkeypatch.setattr("shutil.which", lambda *_args, **_kwargs: None)
        report = build_show_report(
            workspace=ws,
            workspace_name=None,
            registered=False,
            is_default=False,
        )
        validate_setup_report(report)
        assert report["manifest"] == {"skill_count": 0}

    def test_manifest_summary_with_manifest(self, tmp_path):
        ws = _make_workspace(tmp_path)
        save_manifest(
            ws,
            {
                "version": 1,
                "workspace_name": "work",
                "ai_vault_root": str(tmp_path),
                "ides": ["cursor"],
                "skills": [],
                "synced_at": "2026-01-01T00:00:00+00:00",
            },
        )
        report = build_check_report(
            workspace=ws,
            workspace_name="work",
            registered=False,
            is_default=False,
        )
        validate_setup_report(report)
        assert report["manifest"]["version"] == 1
        assert report["manifest"]["synced_at"] == "2026-01-01T00:00:00+00:00"
