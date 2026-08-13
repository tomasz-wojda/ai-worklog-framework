import json
from argparse import Namespace
from pathlib import Path

import pytest

from ai_worklog_framework.adapters import jenkins
from ai_worklog_framework.cli import (
    EXIT_BLOCKED,
    EXIT_SUCCESS,
    EXIT_SYSTEM_ERROR,
    EXIT_USER_ERROR,
)
from ai_worklog_framework.jenkins import commands as jenkins_commands
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.result import Status


def _write_properties(tmp_path, content):
    paths = WorkspacePaths(tmp_path)
    jenkins_dir = paths.service_dir("jenkins")
    jenkins_dir.mkdir(parents=True, exist_ok=True)
    (jenkins_dir / "jenkins.properties").write_text(content)


def _write_config(tmp_path, jenkins_cfg=None):
    config_dir = tmp_path / ".ai-worklog"
    config_dir.mkdir(parents=True, exist_ok=True)
    payload = {"adapters": {"jenkins": jenkins_cfg or {}}}
    (config_dir / "config.json").write_text(json.dumps(payload))


def test_encode_job_path_nested():
    assert jenkins.encode_job_path("folder/sub/job") == "job/folder/job/sub/job/job"


def test_validate_job_name_allows_leading_underscore():
    assert jenkins._validate_job_name("_seed") == "_seed"


def test_validate_job_name_allows_leading_tilde():
    assert jenkins._validate_job_name("~seed-job") == "~seed-job"


def test_controller_public_info_redacts_secrets(tmp_path):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret-token\n",
    )
    paths = WorkspacePaths(tmp_path)
    controllers = jenkins._load_controller_credentials(paths)
    public = jenkins.controller_public_info(controllers)
    assert public == [{
        "id": "primary",
        "url": "https://jenkins.example",
        "has_user": True,
        "has_token": True,
    }]
    assert "secret-token" not in json.dumps(public)
    assert "bot" not in json.dumps(public)


def test_operator_controllers_no_network(tmp_path):
    _write_properties(
        tmp_path,
        "alpha.url=https://a.example\nalpha.user=u\nalpha.token=t\n",
    )
    report = jenkins.operator_controllers(WorkspacePaths(tmp_path))
    assert report["status"] == Status.READY
    assert report["items"][0]["id"] == "alpha"
    assert "fetched_at" in report


def test_operator_health_quieting_down(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        assert path.startswith("/api/json")
        return 200, {
            "mode": "NORMAL",
            "quietingDown": True,
            "numExecutors": 2,
            "nodeDescription": "controller",
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_health(paths, "primary", timeout=5)
    assert report["status"] == Status.DEGRADED
    assert report["items"][0]["quieting_down"] is True


def test_operator_health_missing_controller(tmp_path):
    report = jenkins.operator_health(WorkspacePaths(tmp_path), "missing", timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_health_malformed_response(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_args, **_kwargs: (200, []))
    report = jenkins.operator_health(paths, "primary", timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_health_access_blocked(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_args, **_kwargs: (403, None))
    report = jenkins.operator_health(paths, "primary", timeout=5)
    assert report["status"] == Status.BLOCKED


def test_operator_job_recent_build_limit(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)
    captured = {}

    def _fake_get(_paths, _controller, path, timeout=10):
        captured["path"] = path
        return 200, {
            "name": "Demo",
            "url": "https://jenkins.example/job/Demo/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 3, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
            "builds": [
                {"number": 3, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
                {"number": 2, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False},
                {"number": 1, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
            ],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_job(
        paths,
        "primary",
        "folder/sub/job",
        builds=2,
        include_parameters=False,
        timeout=5,
    )
    assert "job/folder/job/sub/job/job" in captured["path"]
    assert len(report["items"][0]["recent_builds"]) == 2
    assert report["items"][0]["color"] == "blue"


def test_operator_job_parameters_value_present_only(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        return 200, {
            "name": "Demo",
            "url": "https://jenkins.example/job/Demo/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "actions": [{"parameterDefinitions": [{"name": "BRANCH"}, {"name": "API_TOKEN"}]}],
            "lastBuild": {
                "number": 1,
                "result": "SUCCESS",
                "actions": [{"parameters": [{"name": "BRANCH", "value": "main"}, {"name": "API_TOKEN", "value": "secret-value"}]}],
            },
            "builds": [],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_job(
        paths,
        "primary",
        "Demo",
        builds=1,
        include_parameters=True,
        timeout=5,
    )
    parameters = report["items"][0]["parameters"]
    assert all("value" not in item for item in parameters)
    assert {item["name"] for item in parameters} == {"BRANCH", "***REDACTED***"}
    assert parameters[0]["value_present"] in (True, False)
    assert json.dumps(parameters).count("secret-value") == 0


def test_operator_plugins_required_blocked(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        return 200, {
            "plugins": [
                {"shortName": "workflow-job", "version": "1.0", "active": True, "enabled": True},
            ]
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_plugins(paths, "primary", required=["workflow-job", "missing"], timeout=5)
    assert report["status"] == Status.BLOCKED
    assert report["required"]["missing"] == ["missing"]
    assert report["required"]["requested"] == ["missing", "workflow-job"]
    assert report["required"]["inactive"] == []
    rendered = jenkins.report_to_json(report)
    payload = json.loads(rendered)
    jenkins_commands.validate_report_shape(payload)
    assert set(payload["required"]) == {"requested", "missing", "inactive"}


def test_operator_credentials_projection(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        assert "/credentials/store/system/domain/_/" in path
        return 200, {
            "credentials": [{
                "id": "github-token",
                "typeName": "StringCredentialsImpl",
                "displayName": "github-token",
                "description": "GitHub token",
                "secretValue": "must-not-appear",
            }]
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_credentials(paths, "primary", domain="_", timeout=5)
    item = report["items"][0]
    assert set(item) == {"id", "type_name", "display_name", "description"}
    rendered = jenkins.report_to_json(report)
    assert "must-not-appear" not in rendered


def test_operator_seed_recent_failure(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        return 200, {
            "name": "Seed",
            "url": "https://jenkins.example/job/Seed/",
            "color": "red",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False},
            "builds": [{"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False}],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_seed(paths, "primary", "Seed", timeout=5, max_builds=3)
    assert report["operation"] == "seed"
    assert report["items"][0]["recent_failure"] is True
    assert report["status"] == Status.DEGRADED


def test_operator_syntax_check_success(tmp_path, monkeypatch):
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline { agent any; stages {} }")
    _write_config(tmp_path, {"syntax_check_script": str(script)})

    def _fake_run(argv, timeout=15):
        assert argv[0] == str(script)
        return 0, "SYNTAX OK", ""

    monkeypatch.setattr(jenkins, "run_process", _fake_run)
    report = jenkins.operator_syntax_check(WorkspacePaths(tmp_path), [str(target)], timeout=5)
    assert report["status"] == Status.READY


def test_operator_syntax_check_missing_script(tmp_path, monkeypatch):
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    monkeypatch.setattr(
        "ai_worklog_framework.setup.resolver.resolve_ai_vault_root",
        lambda _workspace: (None, None),
    )
    report = jenkins.operator_syntax_check(WorkspacePaths(tmp_path), [str(target)], timeout=5)
    assert report["status"] == Status.BLOCKED


def test_operator_syntax_check_timeout(tmp_path, monkeypatch):
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    monkeypatch.setattr(jenkins, "run_process", lambda *_args, **_kwargs: (124, "", "Timed out"))
    report = jenkins.operator_syntax_check(WorkspacePaths(tmp_path), [str(target)], timeout=1)
    assert report["status"] == Status.BLOCKED
    assert report["message"] == "Syntax check timed out"


def test_resolve_syntax_check_script_order(tmp_path, monkeypatch):
    explicit = tmp_path / "explicit.sh"
    explicit.write_text("#!/bin/sh\n")
    explicit.chmod(0o755)
    vault_root = tmp_path / "vault"
    vault_script = vault_root / "skills/jenkins-pipeline-architect/scripts/syntax_check.sh"
    vault_script.parent.mkdir(parents=True)
    vault_script.write_text("#!/bin/sh\n")
    vault_script.chmod(0o755)
    _write_config(tmp_path, {
        "syntax_check_script": str(explicit),
        "ai_vault_root": str(vault_root),
    })
    monkeypatch.delenv("AI_VAULT_ROOT", raising=False)
    assert jenkins.resolve_syntax_check_script(WorkspacePaths(tmp_path)) == explicit.resolve()


def test_cli_controllers_json(tmp_path, capsys):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="controllers",
        json=True,
        workspace=str(tmp_path),
    ))
    captured = capsys.readouterr().out
    payload = json.loads(captured)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_SUCCESS
    assert payload["operation"] == "controllers"
    assert payload["items"][0]["has_token"] is True
    assert "secret" not in captured


def test_cli_invalid_controller_json(tmp_path, capsys):
    code = jenkins_commands.run(Namespace(
        jenkins_action="health",
        controller="../bad",
        json=True,
        workspace=str(tmp_path),
    ))
    captured = capsys.readouterr().out
    payload = json.loads(captured)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_USER_ERROR
    assert payload["status"] == "error"
    assert payload["operation"] == "health"
    assert "Invalid controller" in payload["message"]


def test_cli_missing_controller_json(tmp_path, capsys):
    code = jenkins_commands.run(Namespace(
        jenkins_action="health",
        controller=None,
        json=True,
        workspace=str(tmp_path),
    ))
    payload = json.loads(capsys.readouterr().out)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_USER_ERROR
    assert payload["message"] == "Missing controller"


def test_cli_invalid_controller_exit_code(tmp_path, capsys):
    code = jenkins_commands.run(Namespace(
        jenkins_action="health",
        controller="../bad",
        json=False,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_USER_ERROR
    assert "Invalid controller" in capsys.readouterr().out


def test_human_output_redacts_embedded_secrets(capsys):
    report = jenkins_commands.JenkinsReport(
        operation="syntax-check",
        fetched_at="2026-01-01T00:00:00Z",
        status=Status.ERROR,
        message="token=message-secret",
        items=[{"stdout": "password=output-secret"}],
    )
    jenkins_commands._render_human(report)
    output = capsys.readouterr().out
    assert "message-secret" not in output
    assert "output-secret" not in output
    assert "***REDACTED***" in output


def test_cli_plugins_blocked_exit_code(tmp_path, monkeypatch, capsys):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_args, **_kwargs: (200, {"plugins": []}),
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="plugins",
        controller="primary",
        require=["workflow-job"],
        json=True,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_BLOCKED
    payload = json.loads(capsys.readouterr().out)
    jenkins_commands.validate_report_shape(payload)
    assert payload["status"] == "blocked"


def test_jenkins_adapter_config_timeouts(tmp_path):
    _write_config(tmp_path, {"timeout_seconds": 12})
    config = jenkins.jenkins_adapter_config(WorkspacePaths(tmp_path))
    assert config["http_timeout_seconds"] == 12
    assert config["process_timeout_seconds"] == 15


def test_cli_syntax_check_passes_process_timeout(tmp_path, monkeypatch):
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    _write_config(tmp_path, {"syntax_check_script": str(script), "timeout_seconds": 12})
    captured = {}

    def _fake_syntax_check(paths, files, timeout):
        captured["timeout"] = timeout
        return {
            "operation": "syntax-check",
            "fetched_at": "2026-01-01T00:00:00Z",
            "status": Status.READY,
            "items": [],
        }

    monkeypatch.setattr(jenkins, "operator_syntax_check", _fake_syntax_check)
    code = jenkins_commands.run(Namespace(
        jenkins_action="syntax-check",
        file=[str(target)],
        json=True,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_SUCCESS
    assert captured["timeout"] == 15


def test_observe_jenkins_enriched_details(tmp_path, monkeypatch):
    _write_properties(
        tmp_path,
        "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n",
    )
    paths = WorkspacePaths(tmp_path)
    state = {
        "services": [],
        "builds": [{"controller": "primary", "job": "folder/job", "number": 10, "result": "SUCCESS"}],
    }

    def _fake_get(_paths, _controller, path, timeout=10):
        assert "job/folder/job/job" in path
        return 200, {
            "color": "blue",
            "builds": [{"number": 10, "result": "SUCCESS", "timestamp": 100, "duration": 50, "building": False}],
            "lastBuild": {"number": 10, "result": "SUCCESS", "timestamp": 100, "duration": 50, "building": False},
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    observation = jenkins.observe_jenkins(paths, state)[0]
    assert observation.status == Status.READY
    assert observation.details["color"] == "blue"
    assert observation.details["fetched_at"].endswith("Z")
    assert observation.details["last_build"]["timestamp"] == 100
    assert observation.details["last_build"]["building"] is False


def test_operator_syntax_check_uses_process_timeout(tmp_path, monkeypatch):
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    captured = {}

    def _fake_run(argv, timeout=15):
        captured["timeout"] = timeout
        return 0, "SYNTAX OK", ""

    monkeypatch.setattr(jenkins, "run_process", _fake_run)
    jenkins.operator_syntax_check(WorkspacePaths(tmp_path), [str(target)], timeout=15)
    assert captured["timeout"] == 15


def test_report_json_shape_success_and_error(tmp_path):
    success = {
        "operation": "controllers",
        "fetched_at": "2026-01-01T00:00:00Z",
        "status": Status.READY,
        "items": [{"id": "primary", "url": "https://jenkins.example", "has_user": True, "has_token": True}],
    }
    success_payload = json.loads(jenkins.report_to_json(success))
    jenkins_commands.validate_report_shape(success_payload)
    assert success_payload["status"] == "ready"

    error = {
        "operation": "health",
        "fetched_at": "2026-01-01T00:00:00Z",
        "status": Status.ERROR,
        "message": "Controller 'missing' not found",
        "controller": "missing",
        "items": [],
    }
    error_payload = json.loads(jenkins.report_to_json(error))
    jenkins_commands.validate_report_shape(error_payload)
    assert error_payload["status"] == "error"


def test_report_to_json_redacts_secrets(tmp_path):
    payload = {
        "operation": "job",
        "controller": "primary",
        "fetched_at": "2026-01-01T00:00:00Z",
        "status": Status.READY,
        "items": [{"token": "abc123-secret-value"}],
    }
    rendered = jenkins.report_to_json(payload)
    assert "abc123-secret-value" not in rendered
    jenkins_commands.validate_report_shape(json.loads(rendered))


def _default_properties():
    return "primary.url=https://jenkins.example\nprimary.user=bot\nprimary.token=secret\n"


def _setup_controller(tmp_path, monkeypatch=None):
    _write_properties(tmp_path, _default_properties())
    return WorkspacePaths(tmp_path)


def test_operator_nodes_happy_path(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        assert "/computer/api/json" in path
        return 200, {
            "computer": [
                {
                    "displayName": "built-in",
                    "description": "controller",
                    "numExecutors": 2,
                    "idle": True,
                    "offline": False,
                    "temporarilyOffline": False,
                    "busyExecutors": 0,
                    "assignedLabels": [{"name": "linux"}, {"name": "docker"}],
                    "secretToken": "must-not-appear",
                },
                {
                    "displayName": "agent-a",
                    "description": "",
                    "numExecutors": 1,
                    "idle": False,
                    "offline": True,
                    "temporarilyOffline": True,
                    "busyExecutors": 1,
                    "assignedLabels": [],
                },
            ],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_nodes(paths, "primary", timeout=5)
    assert report["status"] == Status.READY
    assert [item["display_name"] for item in report["items"]] == ["agent-a", "built-in"]
    assert report["items"][1]["assigned_labels"] == ["docker", "linux"]
    rendered = jenkins.report_to_json(report)
    assert "must-not-appear" not in rendered
    jenkins_commands.validate_report_shape(json.loads(rendered))


def test_operator_nodes_empty(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (200, {"computer": []}))
    report = jenkins.operator_nodes(paths, "primary", timeout=5)
    assert report["status"] == Status.READY
    assert report["items"] == []


def test_operator_nodes_access_blocked(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (401, None))
    report = jenkins.operator_nodes(paths, "primary", timeout=5)
    assert report["status"] == Status.BLOCKED


def test_operator_nodes_malformed(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (200, []))
    report = jenkins.operator_nodes(paths, "primary", timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_queue_sort_and_limit(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        assert "/queue/api/json" in path
        return 200, {
            "items": [
                {
                    "id": 20,
                    "why": "Waiting",
                    "stuck": False,
                    "inQueueSince": 2,
                    "blocked": False,
                    "buildable": True,
                    "task": {"name": "Beta", "url": "https://jenkins.example/job/Beta/", "color": "blue"},
                    "actions": [{"causes": [{"secret": "x"}]}],
                },
                {
                    "id": 10,
                    "why": "Waiting",
                    "stuck": True,
                    "inQueueSince": 1,
                    "blocked": True,
                    "buildable": False,
                    "task": {"name": "Alpha", "url": "https://jenkins.example/job/Alpha/", "color": "red"},
                },
            ],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_queue(paths, "primary", limit=1, timeout=5)
    assert report["status"] == Status.DEGRADED
    assert len(report["items"]) == 1
    assert report["items"][0]["id"] == 10
    assert report["items"][0]["task_name"] == "Alpha"
    assert "actions" not in json.dumps(report["items"])


def test_operator_queue_empty(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (200, {"items": []}))
    report = jenkins.operator_queue(paths, "primary", limit=50, timeout=5)
    assert report["status"] == Status.READY
    assert report["items"] == []


def test_operator_queue_network_error(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (0, None))
    report = jenkins.operator_queue(paths, "primary", limit=50, timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_jobs_browse_and_query(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    responses = {
        "/api/json": {
            "jobs": [
                {
                    "name": "folder",
                    "url": "https://jenkins.example/job/folder/",
                    "color": None,
                    "buildable": False,
                    "inQueue": False,
                    "_class": "com.cloudbees.hudson.plugins.folder.Folder",
                },
                {
                    "name": "RootJob",
                    "url": "https://jenkins.example/job/RootJob/",
                    "color": "blue",
                    "buildable": True,
                    "inQueue": False,
                    "_class": "hudson.model.FreeStyleProject",
                },
            ],
        },
        "/job/folder/": {
            "jobs": [
                {
                    "name": "Nested",
                    "url": "https://jenkins.example/job/folder/job/Nested/",
                    "color": "red",
                    "buildable": True,
                    "inQueue": True,
                    "_class": "hudson.model.FreeStyleProject",
                },
            ],
        },
    }

    def _fake_get(_paths, _controller, path, timeout=10):
        if "/job/folder/api/json" in path:
            return 200, responses["/job/folder/"]
        if path.startswith("/api/json"):
            return 200, responses["/api/json"]
        return 404, None

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    report = jenkins.operator_jobs(
        paths,
        "primary",
        folder=None,
        query="root",
        limit=100,
        timeout=5,
    )
    assert report["status"] == Status.READY
    assert report["query"] == "root"
    assert [item["full_path"] for item in report["items"]] == ["RootJob"]
    assert set(report["items"][0]) >= {"name", "full_path", "url", "color", "buildable", "in_queue", "job_class"}


def test_operator_jobs_folder_not_found(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (404, {"error": "missing"}))
    report = jenkins.operator_jobs(
        paths,
        "primary",
        folder="missing",
        query=None,
        limit=100,
        timeout=5,
    )
    assert report["status"] == Status.ERROR
    assert "not found" in report["message"]


def test_operator_jobs_invalid_query(tmp_path):
    paths = _setup_controller(tmp_path)
    with pytest.raises(ValueError, match="Invalid query"):
        jenkins.operator_jobs(
            paths,
            "primary",
            folder=None,
            query="a" * 129,
            limit=100,
            timeout=5,
        )


def test_operator_jobs_truncation(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    jobs = [
        {
            "name": f"Job{i}",
            "url": f"https://jenkins.example/job/Job{i}/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "_class": "hudson.model.FreeStyleProject",
        }
        for i in range(3)
    ]
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {"jobs": jobs}),
    )
    report = jenkins.operator_jobs(
        paths,
        "primary",
        folder=None,
        query=None,
        limit=2,
        timeout=5,
    )
    assert report["status"] == Status.DEGRADED
    assert len(report["items"]) == 2


def test_operator_artifacts_numeric_and_alias(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    captured = []

    def _fake_get(_paths, _controller, path, timeout=10):
        captured.append(path)
        if "lastSuccessfulBuild" in path:
            return 200, {
                "number": 7,
                "url": "https://jenkins.example/job/Demo/7/",
                "result": "SUCCESS",
                "artifacts": [
                    {"fileName": "b.txt", "relativePath": "b.txt", "secret": "no"},
                    {"fileName": "a.txt", "relativePath": "a.txt"},
                ],
            }
        return 200, {
            "number": 3,
            "url": "https://jenkins.example/job/Demo/3/",
            "result": "FAILURE",
            "artifacts": [],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    numeric = jenkins.operator_artifacts(paths, "primary", "Demo", "3", timeout=5)
    assert numeric["build_selector"] == "3"
    assert numeric["items"][0]["resolved_build_number"] == 3
    alias = jenkins.operator_artifacts(paths, "primary", "Demo", "last-successful", timeout=5)
    assert alias["items"][0]["build_selector"] == "last-successful"
    assert [item["relative_path"] for item in alias["items"][0]["artifacts"]] == ["a.txt", "b.txt"]
    rendered = jenkins.report_to_json(alias)
    assert "no" not in rendered or "***REDACTED***" in rendered
    assert "lastSuccessfulBuild" in captured[1]


def test_operator_artifacts_invalid_selector(tmp_path):
    paths = _setup_controller(tmp_path)
    with pytest.raises(ValueError, match="Invalid build selector"):
        jenkins.operator_artifacts(paths, "primary", "Demo", "0", timeout=5)


def test_operator_artifacts_not_found(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (404, {"error": "missing"}))
    report = jenkins.operator_artifacts(paths, "primary", "Demo", "99", timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_artifacts_truncation(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    artifacts = [
        {"fileName": f"f{i}.txt", "relativePath": f"f{i}.txt"}
        for i in range(201)
    ]
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {"number": 1, "url": "u", "result": "SUCCESS", "artifacts": artifacts}),
    )
    report = jenkins.operator_artifacts(paths, "primary", "Demo", "1", timeout=5)
    assert report["status"] == Status.DEGRADED
    assert report["items"][0]["truncated"] is True
    assert len(report["items"][0]["artifacts"]) == 200


def test_operator_views_list_and_detail(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)

    def _fake_get(_paths, _controller, path, timeout=10):
        if "/view/All/api/json" in path:
            return 200, {
                "name": "All",
                "url": "https://jenkins.example/view/All/",
                "description": "default",
                "jobs": [
                    {"name": "Beta", "url": "u2", "color": "blue", "buildable": True, "inQueue": False},
                    {"name": "Alpha", "url": "u1", "color": "red", "buildable": False, "inQueue": True},
                ],
            }
        return 200, {
            "views": [
                {"name": "BetaView", "url": "u2", "description": "b"},
                {"name": "AlphaView", "url": "u1", "description": "a"},
            ],
        }

    monkeypatch.setattr(jenkins, "_jenkins_get", _fake_get)
    listed = jenkins.operator_views(paths, "primary", view_name=None, timeout=5)
    assert [item["name"] for item in listed["items"]] == ["AlphaView", "BetaView"]
    detail = jenkins.operator_views(paths, "primary", view_name="All", timeout=5)
    assert detail["view"] == "All"
    assert [job["name"] for job in detail["items"][0]["jobs"]] == ["Alpha", "Beta"]


def test_operator_views_not_found(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(jenkins, "_jenkins_get", lambda *_a, **_k: (404, {"error": "missing"}))
    report = jenkins.operator_views(paths, "primary", view_name="Missing", timeout=5)
    assert report["status"] == Status.ERROR


def test_operator_whoami_identity_only(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {
            "name": "bot",
            "authenticated": True,
            "authorities": [{"authority": "admin"}],
        }),
    )
    report = jenkins.operator_whoami(paths, "primary", timeout=5)
    item = report["items"][0]
    assert set(item) == {"name", "authenticated"}
    assert item["authenticated"] is True
    rendered = jenkins.report_to_json(report)
    assert "admin" not in rendered


def test_operator_credential_domains_metadata_only(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {
            "domains": [
                {
                    "domainName": "global",
                    "displayName": "Global",
                    "description": "default",
                    "url": "https://jenkins.example/credentials/store/system/domain/_/",
                    "credentials": [{"id": "secret-id", "secretValue": "nope"}],
                },
                {
                    "domainName": "custom",
                    "displayName": "Custom",
                    "description": "",
                    "url": "https://jenkins.example/credentials/store/system/domain/custom/",
                },
            ],
        }),
    )
    report = jenkins.operator_credential_domains(paths, "primary", timeout=5)
    assert [item["domain_name"] for item in report["items"]] == ["custom", "global"]
    assert set(report["items"][0]) == {"domain_name", "display_name", "description", "url"}
    rendered = jenkins.report_to_json(report)
    assert "secret-id" not in rendered
    assert "nope" not in rendered


def test_operator_credential_domains_map_shape(tmp_path, monkeypatch):
    paths = _setup_controller(tmp_path)
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {
            "domains": {
                "_": {
                    "_class": "com.cloudbees.plugins.credentials.CredentialsStoreAction$DomainWrapper",
                    "displayName": "Global",
                    "description": "Credentials that should be available everywhere.",
                },
            },
        }),
    )
    report = jenkins.operator_credential_domains(paths, "primary", timeout=5)
    assert len(report["items"]) == 1
    item = report["items"][0]
    assert item["domain_name"] == "_"
    assert item["display_name"] == "Global"
    assert item["description"] == "Credentials that should be available everywhere."
    assert set(item) == {"domain_name", "display_name", "description", "url"}


def test_operator_blocked_without_credentials(tmp_path):
    _write_properties(tmp_path, "primary.url=https://jenkins.example\n")
    report = jenkins.operator_whoami(WorkspacePaths(tmp_path), "primary", timeout=5)
    assert report["status"] == Status.BLOCKED


def test_cli_nodes_json(tmp_path, monkeypatch, capsys):
    _write_properties(tmp_path, _default_properties())
    monkeypatch.setattr(
        jenkins,
        "operator_nodes",
        lambda paths, controller, timeout: {
            "operation": "nodes",
            "controller": controller,
            "fetched_at": "2026-01-01T00:00:00Z",
            "status": Status.READY,
            "items": [],
        },
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="nodes",
        controller="primary",
        json=True,
        workspace=str(tmp_path),
    ))
    payload = json.loads(capsys.readouterr().out)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_SUCCESS


def test_cli_artifacts_missing_selector(tmp_path, capsys):
    code = jenkins_commands.run(Namespace(
        jenkins_action="artifacts",
        controller="primary",
        job="Demo",
        build_selector=None,
        json=True,
        workspace=str(tmp_path),
    ))
    payload = json.loads(capsys.readouterr().out)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_USER_ERROR
    assert payload["message"] == "Missing build selector"


def test_cli_jobs_invalid_folder_exit_code(tmp_path, capsys):
    code = jenkins_commands.run(Namespace(
        jenkins_action="jobs",
        controller="primary",
        folder="../bad",
        query=None,
        limit=None,
        json=True,
        workspace=str(tmp_path),
    ))
    payload = json.loads(capsys.readouterr().out)
    jenkins_commands.validate_report_shape(payload)
    assert code == EXIT_USER_ERROR


def test_cli_queue_degraded_exit_code(tmp_path, monkeypatch, capsys):
    _write_properties(tmp_path, _default_properties())
    monkeypatch.setattr(
        jenkins,
        "operator_queue",
        lambda *_a, **_k: {
            "operation": "queue",
            "controller": "primary",
            "fetched_at": "2026-01-01T00:00:00Z",
            "status": Status.DEGRADED,
            "message": "truncated",
            "items": [{"id": 1}],
        },
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="queue",
        controller="primary",
        limit=1,
        json=True,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_SUCCESS


def test_cli_artifacts_not_found_exit_code(tmp_path, monkeypatch, capsys):
    _write_properties(tmp_path, _default_properties())
    monkeypatch.setattr(
        jenkins,
        "operator_artifacts",
        lambda *_a, **_k: {
            "operation": "artifacts",
            "controller": "primary",
            "job": "Demo",
            "build_selector": "9",
            "fetched_at": "2026-01-01T00:00:00Z",
            "status": Status.ERROR,
            "message": "Build '9' not found for job 'Demo'",
            "items": [],
        },
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="artifacts",
        controller="primary",
        job="Demo",
        build_selector="9",
        json=True,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_USER_ERROR


def test_cli_artifacts_blocked_exit_code(tmp_path, monkeypatch, capsys):
    _write_properties(tmp_path, _default_properties())
    monkeypatch.setattr(
        jenkins,
        "operator_artifacts",
        lambda *_a, **_k: {
            "operation": "artifacts",
            "controller": "primary",
            "job": "Demo",
            "build_selector": "1",
            "fetched_at": "2026-01-01T00:00:00Z",
            "status": Status.BLOCKED,
            "message": "Jenkins returned HTTP 403",
            "items": [],
        },
    )
    code = jenkins_commands.run(Namespace(
        jenkins_action="artifacts",
        controller="primary",
        job="Demo",
        build_selector="1",
        json=True,
        workspace=str(tmp_path),
    ))
    assert code == EXIT_BLOCKED


def test_validate_build_selector_aliases():
    assert jenkins._validate_build_selector("last-successful") == ("last-successful", "lastSuccessfulBuild")
    assert jenkins._validate_build_selector("last-completed") == ("last-completed", "lastCompletedBuild")
    assert jenkins._validate_build_selector("42") == ("42", "42")


def test_operator_job_regression_unchanged(tmp_path, monkeypatch):
    _write_properties(tmp_path, _default_properties())
    paths = WorkspacePaths(tmp_path)
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {
            "name": "Demo",
            "url": "https://jenkins.example/job/Demo/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 1, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
            "builds": [],
        }),
    )
    report = jenkins.operator_job(
        paths,
        "primary",
        "Demo",
        builds=5,
        include_parameters=False,
        timeout=5,
    )
    assert report["status"] == Status.READY
    assert report["items"][0]["job"] == "Demo"


def test_operator_credentials_regression_unchanged(tmp_path, monkeypatch):
    _write_properties(tmp_path, _default_properties())
    paths = WorkspacePaths(tmp_path)
    monkeypatch.setattr(
        jenkins,
        "_jenkins_get",
        lambda *_a, **_k: (200, {
            "credentials": [{
                "id": "id",
                "typeName": "StringCredentialsImpl",
                "displayName": "id",
                "description": "desc",
            }],
        }),
    )
    report = jenkins.operator_credentials(paths, "primary", domain="_", timeout=5)
    assert report["status"] == Status.READY
    assert report["domain"] == "_"
    assert report["items"][0]["id"] == "id"
