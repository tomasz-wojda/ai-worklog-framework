import json

from ai_worklog_framework.adapters.preflight_scope import resolve_scope
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.state.manager import TicketState, save_state


def workspace(tmp_path):
    (tmp_path / "worklog").mkdir()
    catalog_dir = tmp_path / ".ai-worklog/catalog"
    catalog_dir.mkdir(parents=True)
    catalog_dir.joinpath("services.json").write_text(json.dumps([{
        "id": "service-one",
        "name": "Service One",
        "type": "application",
        "jira": {"project": "TEST"},
        "repositories": [{"local_dir": "repo-one"}],
        "jenkins": {"controller": "one"},
        "argocd": {"applications": [{"name": "one"}]},
        "monitoring": {"diagnostic_packs": ["k8s-workload"]},
        "environments": [{"name": "test"}],
    }]))
    return WorkspacePaths(tmp_path)


def test_unscoped_preflight_runs_all_checks(tmp_path):
    scope = resolve_scope(workspace(tmp_path), None, None)
    assert scope.checks is None


def test_ticket_scope_resolves_catalog_services(tmp_path):
    scope = resolve_scope(workspace(tmp_path), "TEST-1", None)
    assert scope.service_ids == ["service-one"]
    assert {
        "workspace", "git", "toolchain", "jenkins", "argocd",
        "aws", "kubectl", "repositories", "catalog_binaries",
    }.issubset(scope.checks)


def test_explicit_service_unions_with_ticket_state(tmp_path):
    paths = workspace(tmp_path)
    state = TicketState("OTHER-1")
    state.data["services"] = ["service-one"]
    save_state(paths, state)
    scope = resolve_scope(paths, "OTHER-1", ["jira"])
    assert scope.service_ids == ["service-one"]
    assert "jira" in scope.checks
    assert "jenkins" in scope.checks
