"""
Unit tests for ai_worklog_framework.catalog module.
"""

import json
from pathlib import Path

import pytest
from ai_worklog_framework.catalog.loader import (
    load_catalog,
    validate_entry,
    find_services_for_ticket,
)
from ai_worklog_framework.paths import WorkspacePaths


@pytest.fixture
def catalog_workspace(tmp_path):
    (tmp_path / "worklog").mkdir()
    catalog_dir = tmp_path / ".ai-worklog" / "catalog"
    catalog_dir.mkdir(parents=True)
    entries = [
        {"id": "svc-alpha", "name": "Alpha Service", "type": "application",
         "jira": {"project": "PROJ", "components": ["alpha"]},
         "repositories": [{"local_dir": "example-alpha", "url": "https://github.com/example-org/alpha"}]},
        {"id": "svc-beta", "name": "Beta Platform", "type": "infrastructure",
         "jira": {"project": "APP", "components": ["beta"]},
         "repositories": [{"local_dir": "example-beta", "url": "https://github.com/example-org/beta"}]},
    ]
    (catalog_dir / "services.json").write_text(json.dumps(entries))
    return tmp_path


class TestLoadCatalog:
    def test_loads_entries(self, catalog_workspace):
        paths = WorkspacePaths(catalog_workspace)
        catalog = load_catalog(paths)
        assert "svc-alpha" in catalog
        assert "svc-beta" in catalog
        assert catalog["svc-alpha"]["name"] == "Alpha Service"

    def test_empty_catalog_dir_no_framework(self, tmp_path):
        """Verifies that a workspace catalog with no JSON yields no entries from that dir."""
        (tmp_path / "worklog").mkdir()
        ws_catalog = tmp_path / ".ai-worklog" / "catalog"
        ws_catalog.mkdir(parents=True)
        assert list(ws_catalog.glob("*.json")) == []


class TestValidateEntry:
    def test_valid_entry(self):
        entry = {"id": "test", "name": "Test", "type": "application"}
        assert validate_entry(entry) == []

    def test_missing_required(self):
        entry = {"name": "Test"}
        errors = validate_entry(entry)
        assert any("id" in e for e in errors)
        assert any("type" in e for e in errors)

    def test_invalid_type(self):
        entry = {"id": "x", "name": "X", "type": "invalid"}
        errors = validate_entry(entry)
        assert any("Invalid type" in e for e in errors)

    def test_secret_value_rejected(self):
        entry = {"id": "x", "name": "X", "type": "application",
                 "secrets": [{"provider": "aws", "value": "hunter2"}]}
        errors = validate_entry(entry)
        assert any("NOT contain actual secret" in e for e in errors)


class TestFindServicesForTicket:
    def test_matches_by_project(self, catalog_workspace):
        paths = WorkspacePaths(catalog_workspace)
        catalog = load_catalog(paths)
        matches = find_services_for_ticket(catalog, jira_project="PROJ")
        assert "svc-alpha" in matches
        assert "svc-beta" not in matches

    def test_matches_by_component(self, catalog_workspace):
        paths = WorkspacePaths(catalog_workspace)
        catalog = load_catalog(paths)
        matches = find_services_for_ticket(catalog, jira_components=["beta"])
        assert "svc-beta" in matches

    def test_no_match(self, catalog_workspace):
        paths = WorkspacePaths(catalog_workspace)
        catalog = load_catalog(paths)
        matches = find_services_for_ticket(catalog, jira_project="NONEXISTENT")
        assert matches == []
