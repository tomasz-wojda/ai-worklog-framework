import pytest

from ai_worklog_framework.adapters import argocd, git, github, jenkins, jira, process, tempo
from ai_worklog_framework.adapters.http import bearer_headers
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.result import Status


def test_process_rejects_unsafe_arguments():
    with pytest.raises(ValueError):
        process.run_process(["git", "unsafe\nargument"])


def test_process_timeout():
    code, stdout, stderr = process.run_process(["sleep", "2"], timeout=1)
    assert code == 124
    assert stdout == ""
    assert stderr == "Timed out"


def test_load_properties_ignores_comments(tmp_path):
    path = tmp_path / "demo.properties"
    path.write_text("# comment\njira.url=https://example.test\n")
    assert process.load_properties(path)["jira.url"] == "https://example.test"


def test_jira_observe_missing_credentials(tmp_path):
    paths = WorkspacePaths(tmp_path)
    observations = jira.observe_jira(paths, "TEST-1")
    assert observations[0].status == Status.UNKNOWN


def test_jira_observe_success(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    jira_dir = paths.service_dir("jira")
    jira_dir.mkdir(parents=True)
    (jira_dir / "jira.properties").write_text("jira.url=https://jira.example\njira.token=secret-token\n")

    def _fake_get(url, headers=None, timeout=10):
        assert headers == bearer_headers("secret-token")
        return 200, {
            "fields": {
                "summary": "Demo",
                "status": {"name": "Open", "statusCategory": {"key": "indeterminate"}},
                "assignee": {"displayName": "Operator"},
                "created": "2026-01-01T00:00:00.000+0000",
                "updated": "2026-01-02T00:00:00.000+0000",
            }
        }

    monkeypatch.setattr("ai_worklog_framework.adapters.jira.http_get_json", _fake_get)
    observation = jira.observe_jira(paths, "TEST-1")[0]
    assert observation.status == Status.READY
    assert observation.details["summary"] == "Demo"


def test_git_observe_dirty_repo(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    repo = paths.root / "repos" / "demo-repo"
    repo.mkdir(parents=True)
    state = {"repositories": ["demo-repo"], "services": []}

    def _fake_run(argv, timeout=15):
        if "status" in argv:
            return 0, " M file.txt\n", ""
        if "rev-list" in argv:
            return 0, "2\n", ""
        if "abbrev-ref" in argv:
            return 0, "feature/demo\n", ""
        if "@{upstream}" in argv[-1]:
            return 0, "abc123\n", ""
        return 0, "deadbeef\n", ""

    monkeypatch.setattr("ai_worklog_framework.adapters.git.run_process", _fake_run)
    observation = git.observe_git(paths, state)[0]
    assert observation.details["dirty"] is True
    assert observation.details["ahead_of_upstream"] == 2


def test_git_observe_rejects_repository_root_outside_workspace(tmp_path):
    paths = WorkspacePaths(tmp_path)
    state = {"repositories": ["fixture-repository"], "services": []}
    observation = git.observe_git(
        paths,
        state,
        repositories_root="../outside",
    )[0]
    assert observation.status == Status.ERROR
    assert observation.message == "Repository root outside workspace"


def test_github_observe_malformed_response(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    state = {"repositories": [{"url": "https://github.com/example-org/demo"}], "services": []}

    def _fake_which(_name):
        return "/usr/bin/gh"

    def _fake_run(argv, timeout=15):
        return 0, "not-json", ""

    monkeypatch.setattr("ai_worklog_framework.adapters.github.shutil.which", _fake_which)
    monkeypatch.setattr("ai_worklog_framework.adapters.github.run_process", _fake_run)
    observation = github.observe_github(paths, "TEST-1", state)[0]
    assert observation.status == Status.ERROR


def test_jenkins_observe_uses_in_process_http(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    jenkins_dir = paths.service_dir("jenkins")
    jenkins_dir.mkdir(parents=True)
    (jenkins_dir / "jenkins.properties").write_text(
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n"
    )
    state = {
        "services": [],
        "builds": [{"controller": "primary", "job": "Demo_Job", "number": 10, "result": "SUCCESS"}],
    }

    def _fake_get(paths, controller, path, timeout=10):
        return 200, {
            "builds": [{"number": 10, "result": "SUCCESS"}],
            "lastBuild": {"number": 10, "result": "SUCCESS"},
        }

    monkeypatch.setattr("ai_worklog_framework.adapters.jenkins._jenkins_get", _fake_get)
    observation = jenkins.observe_jenkins(paths, state)[0]
    assert observation.status == Status.READY
    assert observation.details["last_build"]["number"] == 10


def test_argocd_observe_missing_cli(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    monkeypatch.setattr("ai_worklog_framework.adapters.argocd.shutil.which", lambda _name: None)
    observation = argocd.observe_argocd(paths, {"synchronization": {"state": "unknown"}, "services": []})[0]
    assert observation.status == Status.UNKNOWN


def test_tempo_observe_filters_ticket_entries(tmp_path, monkeypatch):
    paths = WorkspacePaths(tmp_path)
    jira_dir = paths.service_dir("jira")
    jira_dir.mkdir(parents=True)
    (jira_dir / "jira.properties").write_text("jira.url=https://jira.example\njira.token=secret\n")
    state = {"created_at": "2026-01-01T00:00:00Z", "closeout": {"tempo_logged": False, "tempo_seconds": 0}}

    monkeypatch.setattr(
        "ai_worklog_framework.adapters.tempo.fetch_jira_user",
        lambda *_args, **_kwargs: {"name": "operator", "displayName": "Operator"},
    )
    monkeypatch.setattr(
        "ai_worklog_framework.adapters.tempo.observe_jira",
        lambda *_args, **_kwargs: [],
    )

    def _fake_get(url, headers=None, timeout=10):
        return 200, [
            {"issue": {"key": "TEST-2"}, "timeSpentSeconds": 100},
            {"issue": {"key": "TEST-1"}, "timeSpentSeconds": 900},
        ]

    monkeypatch.setattr("ai_worklog_framework.adapters.tempo.http_get_json", _fake_get)
    observation = tempo.observe_tempo(paths, "TEST-1", state)[0]
    assert observation.details["total_seconds"] == 900
    assert observation.details["entry_count"] == 1
