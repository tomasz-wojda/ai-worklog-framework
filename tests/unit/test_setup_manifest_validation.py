import json
import stat

import pytest

from ai_worklog_framework.setup.manifest import load_manifest, manifest_path, save_manifest, validate_manifest


def _valid_manifest(**overrides):
    payload = {
        "version": 1,
        "workspace_name": "work",
        "ai_vault_root": "/vault",
        "ides": ["cursor"],
        "skills": [],
        "synced_at": "2026-01-01T00:00:00+00:00",
    }
    payload.update(overrides)
    return payload


def _valid_skill(**overrides):
    payload = {
        "name": "developer-protocol",
        "ide": "cursor",
        "source": "/vault/skills/developer-protocol",
        "destination": "/ws/.cursor/skills/developer-protocol",
        "materialization": "symlink",
        "source_checksum": "abc123",
        "created_at": "2026-01-01T00:00:00+00:00",
        "synced_at": "2026-01-01T00:00:00+00:00",
    }
    payload.update(overrides)
    return payload


class TestValidateManifest:
    def test_accepts_minimal_valid_manifest(self):
        validated = validate_manifest(_valid_manifest())
        assert validated["workspace_name"] == "work"
        assert validated["ides"] == ["cursor"]

    def test_rejects_non_object(self):
        with pytest.raises(ValueError, match="Malformed setup manifest"):
            validate_manifest([])

    def test_rejects_unknown_top_level_fields(self):
        with pytest.raises(ValueError, match="unknown fields"):
            validate_manifest(_valid_manifest(extra=True))

    def test_rejects_missing_required_fields(self):
        payload = _valid_manifest()
        del payload["synced_at"]
        with pytest.raises(ValueError, match="missing fields"):
            validate_manifest(payload)

    def test_rejects_wrong_version(self):
        with pytest.raises(ValueError, match="version must be 1"):
            validate_manifest(_valid_manifest(version=2))

    def test_rejects_invalid_workspace_name(self):
        with pytest.raises(ValueError, match="workspace_name is invalid"):
            validate_manifest(_valid_manifest(workspace_name="-bad"))

    def test_rejects_empty_ai_vault_root(self):
        with pytest.raises(ValueError, match="ai_vault_root is invalid"):
            validate_manifest(_valid_manifest(ai_vault_root=""))

    def test_rejects_duplicate_ides(self):
        with pytest.raises(ValueError, match="ides must be unique"):
            validate_manifest(_valid_manifest(ides=["cursor", "cursor"]))

    def test_rejects_invalid_ide_value(self):
        with pytest.raises(ValueError, match="ides contain invalid value"):
            validate_manifest(_valid_manifest(ides=["vscode"]))

    def test_normalizes_ides_sorted_unique(self):
        validated = validate_manifest(_valid_manifest(ides=["claude", "cursor", "antigravity"]))
        assert validated["ides"] == ["antigravity", "claude", "cursor"]

    def test_rejects_unknown_skill_fields(self):
        skill = _valid_skill(unknown=True)
        with pytest.raises(ValueError, match="skill unknown fields"):
            validate_manifest(_valid_manifest(skills=[skill]))

    def test_rejects_missing_skill_fields(self):
        skill = _valid_skill()
        del skill["source_checksum"]
        with pytest.raises(ValueError, match="skill missing fields"):
            validate_manifest(_valid_manifest(skills=[skill]))

    def test_rejects_invalid_materialization(self):
        skill = _valid_skill(materialization="hardlink")
        with pytest.raises(ValueError, match="materialization is invalid"):
            validate_manifest(_valid_manifest(skills=[skill]))

    def test_rejects_empty_checksums_and_timestamps(self):
        skill = _valid_skill(source_checksum="")
        with pytest.raises(ValueError, match="source_checksum is invalid"):
            validate_manifest(_valid_manifest(skills=[skill]))

    def test_accepts_null_applied_checksum(self):
        skill = _valid_skill(applied_checksum=None)
        validated = validate_manifest(_valid_manifest(skills=[skill]))
        assert validated["skills"][0]["applied_checksum"] is None

    def test_rejects_empty_applied_checksum_string(self):
        skill = _valid_skill(applied_checksum="")
        with pytest.raises(ValueError, match="applied_checksum is invalid"):
            validate_manifest(_valid_manifest(skills=[skill]))

    def test_rejects_duplicate_ide_name_pairs(self):
        skill = _valid_skill()
        with pytest.raises(ValueError, match="unique ide:name pairs"):
            validate_manifest(_valid_manifest(skills=[skill, dict(skill)]))

    def test_load_rejects_malformed_file(self, tmp_path):
        ws = tmp_path / "ws"
        ws.mkdir()
        path = manifest_path(ws)
        path.parent.mkdir(parents=True)
        path.write_text("{bad", encoding="utf-8")
        with pytest.raises(ValueError, match="Malformed setup manifest"):
            load_manifest(ws)

    def test_save_rejects_invalid_payload(self, tmp_path):
        ws = tmp_path / "ws"
        ws.mkdir()
        with pytest.raises(ValueError, match="version must be 1"):
            save_manifest(ws, _valid_manifest(version=99))

    def test_round_trip_persists_valid_manifest(self, tmp_path):
        ws = tmp_path / "ws"
        ws.mkdir()
        save_manifest(ws, _valid_manifest())
        loaded = load_manifest(ws)
        assert loaded["workspace_name"] == "work"
        assert stat.S_IMODE(manifest_path(ws).stat().st_mode) in (0o644, 0o600, 0o666)
