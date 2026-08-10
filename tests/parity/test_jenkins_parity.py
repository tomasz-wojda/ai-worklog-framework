import json
import os
import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

import pytest


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / "ai-worklog"


class _JenkinsMockState:
    routes: dict[str, tuple[int, Any]] = {}
    default_status = 404
    default_body: Any = {"error": "not found"}


class _JenkinsMockHandler(BaseHTTPRequestHandler):
    def log_message(self, _format: str, *_args: Any) -> None:
        pass

    def do_GET(self) -> None:
        path = unquote(urlparse(self.path).path)
        status, body = _JenkinsMockState.default_status, _JenkinsMockState.default_body
        for prefix, route in _JenkinsMockState.routes.items():
            if path.startswith(prefix) or prefix in path:
                status, body = route
                break
        payload = json.dumps(body).encode("utf-8") if body is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)


@pytest.fixture
def jenkins_mock():
    _JenkinsMockState.routes = {}
    _JenkinsMockState.default_status = 404
    _JenkinsMockState.default_body = {"error": "not found"}
    server = HTTPServer(("127.0.0.1", 0), _JenkinsMockHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[:2]
    base_url = f"http://{host}:{port}"
    yield base_url
    server.shutdown()


def _run(runtime: str, workspace: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    command = [
        str(CLI),
        "--runtime",
        runtime,
        "--workspace",
        str(workspace),
        "jenkins",
        *arguments,
    ]
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=30,
        env=os.environ.copy(),
    )


def _write_properties(workspace: Path, content: str) -> None:
    jenkins_dir = workspace / "worklog" / "interface" / "jenkins"
    jenkins_dir.mkdir(parents=True, exist_ok=True)
    (jenkins_dir / "jenkins.properties").write_text(content)


def _write_config(workspace: Path, jenkins_cfg: dict[str, Any] | None = None) -> None:
    config_dir = workspace / ".ai-worklog"
    config_dir.mkdir(parents=True, exist_ok=True)
    payload = {"adapters": {"jenkins": jenkins_cfg or {}}}
    (config_dir / "config.json").write_text(json.dumps(payload))


def _properties_for(base_url: str) -> str:
    return (
        f"primary.url={base_url}\n"
        "primary.user=bot\n"
        "primary.token=secret-token\n"
    )


def _normalize_fetched_at_json(stdout: str) -> Any:
    payload = json.loads(stdout.strip())
    payload["fetched_at"] = "<FETCHED_AT>"
    return payload


def _normalize_fetched_at_human(stdout: str) -> str:
    return re.sub(
        r"(  Fetched: )\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z",
        r"\1<FETCHED_AT>",
        stdout,
    )


def _assert_parity_json(python: subprocess.CompletedProcess[str], groovy: subprocess.CompletedProcess[str]) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    python_payload = _normalize_fetched_at_json(python.stdout)
    groovy_payload = _normalize_fetched_at_json(groovy.stdout)
    if python_payload != groovy_payload:
        pytest.fail(
            "JSON payload mismatch after fetched_at normalization\n"
            f"python:\n{json.dumps(python_payload, indent=2, ensure_ascii=False)}\n"
            f"groovy:\n{json.dumps(groovy_payload, indent=2, ensure_ascii=False)}"
        )


def _assert_parity_human(python: subprocess.CompletedProcess[str], groovy: subprocess.CompletedProcess[str]) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    python_output = _normalize_fetched_at_human(python.stdout)
    groovy_output = _normalize_fetched_at_human(groovy.stdout)
    if python_output != groovy_output:
        pytest.fail(
            "Human output mismatch after fetched_at normalization\n"
            f"python:\n{python_output!r}\n"
            f"groovy:\n{groovy_output!r}"
        )


def _assert_no_secrets(stdout: str) -> None:
    assert "secret-token" not in stdout
    assert "secret-value" not in stdout
    assert "must-not-appear" not in stdout


def test_jenkins_controllers_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    python = _run("python", tmp_path, "controllers", "--json")
    groovy = _run("groovy", tmp_path, "controllers", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "ready"
    assert payload["items"][0]["id"] == "primary"


def test_jenkins_controllers_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    python = _run("python", tmp_path, "controllers")
    groovy = _run("groovy", tmp_path, "controllers")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)


def test_jenkins_controllers_blocked_json(tmp_path: Path) -> None:
    python = _run("python", tmp_path, "controllers", "--json")
    groovy = _run("groovy", tmp_path, "controllers", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_health_ready_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (
        200,
        {
            "mode": "NORMAL",
            "quietingDown": False,
            "numExecutors": 2,
            "nodeDescription": "controller",
        },
    )
    python = _run("python", tmp_path, "health", "primary", "--json")
    groovy = _run("groovy", tmp_path, "health", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 0


def test_jenkins_health_ready_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (
        200,
        {
            "mode": "NORMAL",
            "quietingDown": False,
            "numExecutors": 2,
            "nodeDescription": "controller",
        },
    )
    python = _run("python", tmp_path, "health", "primary")
    groovy = _run("groovy", tmp_path, "health", "primary")
    _assert_parity_human(python, groovy)


def test_jenkins_health_degraded_quieting_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (
        200,
        {
            "mode": "NORMAL",
            "quietingDown": True,
            "numExecutors": 2,
            "nodeDescription": "controller",
        },
    )
    python = _run("python", tmp_path, "health", "primary", "--json")
    groovy = _run("groovy", tmp_path, "health", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "degraded"
    assert payload["items"][0]["quieting_down"] is True


def test_jenkins_health_malformed_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, [])
    python = _run("python", tmp_path, "health", "primary", "--json")
    groovy = _run("groovy", tmp_path, "health", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 2


def test_jenkins_health_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (403, None)
    python = _run("python", tmp_path, "health", "primary", "--json")
    groovy = _run("groovy", tmp_path, "health", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_job_nested_builds_parameters_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/folder/job/sub/job/job/api/json"] = (
        200,
        {
            "name": "job",
            "url": f"{jenkins_mock}/job/folder/job/sub/job/job/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "actions": [{"parameterDefinitions": [{"name": "BRANCH"}, {"name": "API_TOKEN"}]}],
            "lastBuild": {
                "number": 3,
                "result": "SUCCESS",
                "timestamp": 1000,
                "duration": 200,
                "building": False,
                "actions": [{
                    "parameters": [
                        {"name": "BRANCH", "value": "main"},
                        {"name": "API_TOKEN", "value": "secret-value"},
                    ],
                }],
            },
            "builds": [
                {"number": 3, "result": "SUCCESS", "timestamp": 1000, "duration": 200, "building": False},
                {"number": 2, "result": "FAILURE", "timestamp": 900, "duration": 150, "building": False},
                {"number": 1, "result": "SUCCESS", "timestamp": 800, "duration": 100, "building": False},
            ],
        },
    )
    python = _run(
        "python",
        tmp_path,
        "job",
        "primary",
        "folder/sub/job",
        "--builds",
        "2",
        "--parameters",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "job",
        "primary",
        "folder/sub/job",
        "--builds",
        "2",
        "--parameters",
        "--json",
    )
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    payload = _normalize_fetched_at_json(python.stdout)
    item = payload["items"][0]
    assert item["job"] == "folder/sub/job"
    assert len(item["recent_builds"]) == 2
    assert {entry["name"] for entry in item["parameters"]} == {"BRANCH", "***REDACTED***"}
    assert all("value" not in entry for entry in item["parameters"])


def test_jenkins_job_nested_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/folder/job/sub/job/job/api/json"] = (
        200,
        {
            "name": "job",
            "url": f"{jenkins_mock}/job/folder/job/sub/job/job/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 1, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
            "builds": [
                {"number": 1, "result": "SUCCESS", "timestamp": 1, "duration": 2, "building": False},
            ],
        },
    )
    python = _run("python", tmp_path, "job", "primary", "folder/sub/job", "--builds", "1")
    groovy = _run("groovy", tmp_path, "job", "primary", "folder/sub/job", "--builds", "1")
    _assert_parity_human(python, groovy)


def test_jenkins_job_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Missing/api/json"] = (404, {"error": "not found"})
    python = _run("python", tmp_path, "job", "primary", "Missing", "--json")
    groovy = _run("groovy", tmp_path, "job", "primary", "Missing", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def test_jenkins_health_credentials_unavailable_json(tmp_path: Path) -> None:
    _write_properties(tmp_path, "primary.url=http://127.0.0.1:1\nprimary.user=bot\n")
    python = _run("python", tmp_path, "health", "primary", "--json")
    groovy = _run("groovy", tmp_path, "health", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_job_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/"] = (403, None)
    python = _run("python", tmp_path, "job", "primary", "Demo", "--json")
    groovy = _run("groovy", tmp_path, "job", "primary", "Demo", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_plugins_ready_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/pluginManager/api/json"] = (
        200,
        {
            "plugins": [
                {"shortName": "workflow-job", "version": "1.0", "active": True, "enabled": True},
            ],
        },
    )
    python = _run("python", tmp_path, "plugins", "primary", "--require", "workflow-job", "--json")
    groovy = _run("groovy", tmp_path, "plugins", "primary", "--require", "workflow-job", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 0


def test_jenkins_plugins_missing_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/pluginManager/api/json"] = (
        200,
        {
            "plugins": [
                {"shortName": "workflow-job", "version": "1.0", "active": True, "enabled": True},
            ],
        },
    )
    python = _run(
        "python",
        tmp_path,
        "plugins",
        "primary",
        "--require",
        "workflow-job",
        "--require",
        "missing-plugin",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "plugins",
        "primary",
        "--require",
        "workflow-job",
        "--require",
        "missing-plugin",
        "--json",
    )
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_plugins_missing_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/pluginManager/api/json"] = (200, {"plugins": []})
    python = _run("python", tmp_path, "plugins", "primary", "--require", "workflow-job")
    groovy = _run("groovy", tmp_path, "plugins", "primary", "--require", "workflow-job")
    _assert_parity_human(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_plugins_malformed_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/pluginManager/api/json"] = (200, [])
    python = _run("python", tmp_path, "plugins", "primary", "--json")
    groovy = _run("groovy", tmp_path, "plugins", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 2


def test_jenkins_credentials_metadata_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/domain/_/api/json"] = (
        200,
        {
            "credentials": [{
                "id": "github-token",
                "typeName": "StringCredentialsImpl",
                "displayName": "github-token",
                "description": "GitHub token",
                "secretValue": "must-not-appear",
            }],
        },
    )
    python = _run("python", tmp_path, "credentials", "primary", "--json")
    groovy = _run("groovy", tmp_path, "credentials", "primary", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    item = _normalize_fetched_at_json(python.stdout)["items"][0]
    assert set(item) == {"id", "type_name", "display_name", "description"}


def test_jenkins_credentials_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/domain/_/api/json"] = (
        200,
        {
            "credentials": [{
                "id": "github-token",
                "typeName": "StringCredentialsImpl",
                "displayName": "github-token",
                "description": "GitHub token",
                "secretValue": "must-not-appear",
            }],
        },
    )
    python = _run("python", tmp_path, "credentials", "primary")
    groovy = _run("groovy", tmp_path, "credentials", "primary")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)


def test_jenkins_credentials_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/domain/_/api/json"] = (401, None)
    python = _run("python", tmp_path, "credentials", "primary", "--json")
    groovy = _run("groovy", tmp_path, "credentials", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_seed_recent_failure_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Seed/api/json"] = (
        200,
        {
            "name": "Seed",
            "url": f"{jenkins_mock}/job/Seed/",
            "color": "red",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False},
            "builds": [{"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False}],
        },
    )
    python = _run("python", tmp_path, "seed", "primary", "Seed", "--json")
    groovy = _run("groovy", tmp_path, "seed", "primary", "Seed", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["operation"] == "seed"
    assert payload["status"] == "degraded"
    assert payload["items"][0]["recent_failure"] is True


def test_jenkins_seed_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Seed/api/json"] = (
        200,
        {
            "name": "Seed",
            "url": f"{jenkins_mock}/job/Seed/",
            "color": "red",
            "buildable": True,
            "inQueue": False,
            "lastBuild": {"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False},
            "builds": [{"number": 4, "result": "FAILURE", "timestamp": 1, "duration": 2, "building": False}],
        },
    )
    python = _run("python", tmp_path, "seed", "primary", "Seed")
    groovy = _run("groovy", tmp_path, "seed", "primary", "Seed")
    _assert_parity_human(python, groovy)


def test_jenkins_syntax_check_success_json(tmp_path: Path) -> None:
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nprintf 'SYNTAX OK\\n'\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline { agent any; stages {} }")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 0


def test_jenkins_syntax_check_success_human(tmp_path: Path) -> None:
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nprintf 'SYNTAX OK\\n'\nexit 0\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline { agent any; stages {} }")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target))
    groovy = _run("groovy", tmp_path, "syntax-check", str(target))
    _assert_parity_human(python, groovy)


def test_jenkins_syntax_check_missing_script_json(tmp_path: Path) -> None:
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_syntax_check_failure_json(tmp_path: Path) -> None:
    script = tmp_path / "syntax_check.sh"
    script.write_text("#!/bin/sh\nprintf 'SYNTAX ERROR\\n' >&2\nexit 1\n")
    script.chmod(0o755)
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 2


@pytest.mark.parametrize(
    "arguments",
    [
        ("health", "../bad", "--json"),
        ("health", "--json"),
        ("job", "primary", "../bad", "--json"),
        ("job", "--json"),
        ("syntax-check", "--json"),
    ],
)
def test_jenkins_invalid_input_json(tmp_path: Path, arguments: tuple[str, ...]) -> None:
    _write_properties(tmp_path, _properties_for("http://127.0.0.1:1"))
    python = _run("python", tmp_path, *arguments)
    groovy = _run("groovy", tmp_path, *arguments)
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


@pytest.mark.parametrize(
    "arguments",
    [
        ("health", "../bad"),
        ("health",),
        ("job", "primary", "../bad"),
    ],
)
def test_jenkins_invalid_input_human(tmp_path: Path, arguments: tuple[str, ...]) -> None:
    _write_properties(tmp_path, _properties_for("http://127.0.0.1:1"))
    python = _run("python", tmp_path, *arguments)
    groovy = _run("groovy", tmp_path, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == groovy.returncode == 1
