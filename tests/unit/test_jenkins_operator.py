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


def test_operator_syntax_check_missing_script(tmp_path):
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
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
