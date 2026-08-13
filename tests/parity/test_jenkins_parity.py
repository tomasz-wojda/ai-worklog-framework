import json
import os
import platform
import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

import pytest


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / ("ai-worklog.cmd" if platform.system() == "Windows" else "ai-worklog")


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
        best_prefix = ""
        for prefix, route in _JenkinsMockState.routes.items():
            if path.startswith(prefix) or prefix in path:
                if len(prefix) > len(best_prefix):
                    best_prefix = prefix
                    status, body = route
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
    jenkins_dir = workspace / "integrations" / "jenkins"
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


def _make_script(path: Path, body_sh: str, body_cmd: str) -> Path:
    if platform.system() == "Windows":
        cmd_path = path.with_suffix(".cmd")
        cmd_path.write_text(body_cmd, encoding="utf-8")
        return cmd_path
    path.write_text(body_sh, encoding="utf-8")
    path.chmod(0o755)
    return path


def test_jenkins_syntax_check_success_json(tmp_path: Path) -> None:
    script = _make_script(
        tmp_path / "syntax_check.sh",
        "#!/bin/sh\nprintf 'SYNTAX OK\\n'\nexit 0\n",
        "@echo off\necho SYNTAX OK\nexit /b 0\n",
    )
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline { agent any; stages {} }")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 0


def test_jenkins_syntax_check_success_human(tmp_path: Path) -> None:
    script = _make_script(
        tmp_path / "syntax_check.sh",
        "#!/bin/sh\nprintf 'SYNTAX OK\\n'\nexit 0\n",
        "@echo off\necho SYNTAX OK\nexit /b 0\n",
    )
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline { agent any; stages {} }")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target))
    groovy = _run("groovy", tmp_path, "syntax-check", str(target))
    _assert_parity_human(python, groovy)


def test_jenkins_syntax_check_missing_script_json(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("AI_WORKLOG_HOME", str(home))
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_syntax_check_failure_json(tmp_path: Path) -> None:
    script = _make_script(
        tmp_path / "syntax_check.sh",
        "#!/bin/sh\nprintf 'SYNTAX ERROR\\n' >&2\nexit 1\n",
        "@echo off\necho SYNTAX ERROR>&2\nexit /b 1\n",
    )
    target = tmp_path / "Jenkinsfile.groovy"
    target.write_text("pipeline {}")
    _write_config(tmp_path, {"syntax_check_script": str(script)})
    python = _run("python", tmp_path, "syntax-check", str(target), "--json")
    groovy = _run("groovy", tmp_path, "syntax-check", str(target), "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 2


def _nodes_mock_body(jenkins_mock: str) -> dict[str, Any]:
    return {
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


def test_jenkins_nodes_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/computer/api/json"] = (200, _nodes_mock_body(jenkins_mock))
    python = _run("python", tmp_path, "nodes", "primary", "--json")
    groovy = _run("groovy", tmp_path, "nodes", "primary", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["operation"] == "nodes"
    assert payload["status"] == "ready"
    assert [item["display_name"] for item in payload["items"]] == ["agent-a", "built-in"]
    assert payload["items"][1]["assigned_labels"] == ["docker", "linux"]


def test_jenkins_nodes_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/computer/api/json"] = (200, _nodes_mock_body(jenkins_mock))
    python = _run("python", tmp_path, "nodes", "primary")
    groovy = _run("groovy", tmp_path, "nodes", "primary")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)


def test_jenkins_nodes_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/computer/api/json"] = (200, {"computer": []})
    python = _run("python", tmp_path, "nodes", "primary", "--json")
    groovy = _run("groovy", tmp_path, "nodes", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["items"] == []
    assert payload["message"] == "No nodes found"


def test_jenkins_nodes_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/computer/api/json"] = (401, None)
    python = _run("python", tmp_path, "nodes", "primary", "--json")
    groovy = _run("groovy", tmp_path, "nodes", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_nodes_malformed_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/computer/api/json"] = (200, [])
    python = _run("python", tmp_path, "nodes", "primary", "--json")
    groovy = _run("groovy", tmp_path, "nodes", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 2


def _queue_mock_body(jenkins_mock: str) -> dict[str, Any]:
    return {
        "items": [
            {
                "id": 20,
                "why": "Waiting",
                "stuck": False,
                "inQueueSince": 2,
                "blocked": False,
                "buildable": True,
                "task": {
                    "name": "Beta",
                    "url": f"{jenkins_mock}/job/Beta/",
                    "color": "blue",
                },
                "actions": [{"causes": [{"secret": "must-not-appear"}]}],
            },
            {
                "id": 10,
                "why": "Waiting",
                "stuck": True,
                "inQueueSince": 1,
                "blocked": True,
                "buildable": False,
                "task": {
                    "name": "Alpha",
                    "url": f"{jenkins_mock}/job/Alpha/",
                    "color": "red",
                },
            },
        ],
    }


def test_jenkins_queue_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/queue/api/json"] = (200, _queue_mock_body(jenkins_mock))
    python = _run("python", tmp_path, "queue", "primary", "--json")
    groovy = _run("groovy", tmp_path, "queue", "primary", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["operation"] == "queue"
    assert payload["status"] == "ready"
    assert [item["id"] for item in payload["items"]] == [10, 20]
    assert payload["items"][0]["task_name"] == "Alpha"


def test_jenkins_queue_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/queue/api/json"] = (200, _queue_mock_body(jenkins_mock))
    python = _run("python", tmp_path, "queue", "primary")
    groovy = _run("groovy", tmp_path, "queue", "primary")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)


def test_jenkins_queue_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/queue/api/json"] = (200, {"items": []})
    python = _run("python", tmp_path, "queue", "primary", "--json")
    groovy = _run("groovy", tmp_path, "queue", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["items"] == []
    assert payload["message"] == "Queue is empty"


def test_jenkins_queue_truncation_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/queue/api/json"] = (200, _queue_mock_body(jenkins_mock))
    python = _run("python", tmp_path, "queue", "primary", "--limit", "1", "--json")
    groovy = _run("groovy", tmp_path, "queue", "primary", "--limit", "1", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "degraded"
    assert len(payload["items"]) == 1
    assert payload["items"][0]["id"] == 10
    assert "truncated to 1 items" in payload["message"]


def test_jenkins_queue_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/queue/api/json"] = (403, None)
    python = _run("python", tmp_path, "queue", "primary", "--json")
    groovy = _run("groovy", tmp_path, "queue", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def _jobs_root_mock(jenkins_mock: str) -> dict[str, Any]:
    return {
        "jobs": [
            {
                "name": "folder",
                "url": f"{jenkins_mock}/job/folder/",
                "color": None,
                "buildable": False,
                "inQueue": False,
                "_class": "com.cloudbees.hudson.plugins.folder.Folder",
            },
            {
                "name": "RootJob",
                "url": f"{jenkins_mock}/job/RootJob/",
                "color": "blue",
                "buildable": True,
                "inQueue": False,
                "_class": "hudson.model.FreeStyleProject",
            },
        ],
    }


def _jobs_folder_mock(jenkins_mock: str) -> dict[str, Any]:
    return {
        "jobs": [
            {
                "name": "Nested",
                "url": f"{jenkins_mock}/job/folder/job/Nested/",
                "color": "red",
                "buildable": True,
                "inQueue": True,
                "_class": "hudson.model.FreeStyleProject",
            },
        ],
    }


def test_jenkins_jobs_nested_query_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/folder/api/json"] = (200, _jobs_folder_mock(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, _jobs_root_mock(jenkins_mock))
    python = _run("python", tmp_path, "jobs", "primary", "--query", "nested", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--query", "nested", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["operation"] == "jobs"
    assert payload["query"] == "nested"
    assert [item["full_path"] for item in payload["items"]] == ["folder/Nested"]


def test_jenkins_jobs_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, _jobs_root_mock(jenkins_mock))
    _JenkinsMockState.routes["/job/folder/api/json"] = (200, _jobs_folder_mock(jenkins_mock))
    python = _run("python", tmp_path, "jobs", "primary")
    groovy = _run("groovy", tmp_path, "jobs", "primary")
    _assert_parity_human(python, groovy)


def test_jenkins_jobs_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, {"jobs": []})
    python = _run("python", tmp_path, "jobs", "primary", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["items"] == []
    assert payload["message"] == "No jobs found"


def test_jenkins_jobs_truncation_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    jobs = [
        {
            "name": f"Job{i}",
            "url": f"{jenkins_mock}/job/Job{i}/",
            "color": "blue",
            "buildable": True,
            "inQueue": False,
            "_class": "hudson.model.FreeStyleProject",
        }
        for i in range(3)
    ]
    _JenkinsMockState.routes["/api/json"] = (200, {"jobs": jobs})
    python = _run("python", tmp_path, "jobs", "primary", "--limit", "2", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--limit", "2", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "degraded"
    assert len(payload["items"]) == 2
    assert "truncated to 2 items" in payload["message"]


def test_jenkins_jobs_folder_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Missing/api/json"] = (404, {"error": "not found"})
    python = _run("python", tmp_path, "jobs", "primary", "--folder", "Missing", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--folder", "Missing", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def test_jenkins_jobs_folder_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Secret/api/json"] = (403, None)
    python = _run("python", tmp_path, "jobs", "primary", "--folder", "Secret", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--folder", "Secret", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_jobs_root_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (403, None)
    python = _run("python", tmp_path, "jobs", "primary", "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "blocked"


def test_jenkins_jobs_invalid_query_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    python = _run("python", tmp_path, "jobs", "primary", "--query", "x" * 129, "--json")
    groovy = _run("groovy", tmp_path, "jobs", "primary", "--query", "x" * 129, "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def _artifacts_build_body(
    jenkins_mock: str,
    number: int,
    result: str,
    artifacts: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    return {
        "number": number,
        "url": f"{jenkins_mock}/job/Demo/{number}/",
        "result": result,
        "artifacts": artifacts or [],
    }


def test_jenkins_artifacts_exact_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/3/api/json"] = (
        200,
        _artifacts_build_body(
            jenkins_mock,
            3,
            "FAILURE",
            [
                {"fileName": "b.txt", "relativePath": "b.txt", "secret": "must-not-appear"},
                {"fileName": "a.txt", "relativePath": "a.txt"},
            ],
        ),
    )
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "3", "--json")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "3", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["build_selector"] == "3"
    assert payload["job"] == "Demo"
    item = payload["items"][0]
    assert item["resolved_build_number"] == 3
    assert [entry["relative_path"] for entry in item["artifacts"]] == ["a.txt", "b.txt"]


def test_jenkins_artifacts_last_successful_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/lastSuccessfulBuild/api/json"] = (
        200,
        _artifacts_build_body(jenkins_mock, 7, "SUCCESS"),
    )
    python = _run(
        "python",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-successful",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-successful",
        "--json",
    )
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["build_selector"] == "last-successful"
    assert payload["items"][0]["resolved_build_number"] == 7


def test_jenkins_artifacts_last_completed_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/lastCompletedBuild/api/json"] = (
        200,
        _artifacts_build_body(jenkins_mock, 5, "UNSTABLE"),
    )
    python = _run(
        "python",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-completed",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-completed",
        "--json",
    )
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["build_selector"] == "last-completed"
    assert payload["items"][0]["resolved_build_number"] == 5


def test_jenkins_artifacts_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/3/api/json"] = (
        200,
        _artifacts_build_body(jenkins_mock, 3, "SUCCESS"),
    )
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "3")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "3")
    _assert_parity_human(python, groovy)


def test_jenkins_artifacts_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/1/api/json"] = (
        200,
        _artifacts_build_body(jenkins_mock, 1, "SUCCESS"),
    )
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "1", "--json")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "1", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["message"] == "No artifacts found"
    assert payload["items"][0]["artifacts"] == []


def test_jenkins_artifacts_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/99/api/json"] = (404, {"error": "not found"})
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "99", "--json")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "99", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def test_jenkins_artifacts_last_successful_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/lastSuccessfulBuild/api/json"] = (404, None)
    python = _run(
        "python",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-successful",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-successful",
        "--json",
    )
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "error"
    assert "last successful build" in payload["message"]


def test_jenkins_artifacts_last_completed_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/lastCompletedBuild/api/json"] = (404, None)
    python = _run(
        "python",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-completed",
        "--json",
    )
    groovy = _run(
        "groovy",
        tmp_path,
        "artifacts",
        "primary",
        "Demo",
        "last-completed",
        "--json",
    )
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["status"] == "error"
    assert "last completed build" in payload["message"]


def test_jenkins_artifacts_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/job/Demo/1/api/json"] = (403, None)
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "1", "--json")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "1", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_artifacts_invalid_selector_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    python = _run("python", tmp_path, "artifacts", "primary", "Demo", "0", "--json")
    groovy = _run("groovy", tmp_path, "artifacts", "primary", "Demo", "0", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def _views_list_mock(jenkins_mock: str) -> dict[str, Any]:
    return {
        "views": [
            {
                "name": "BetaView",
                "url": f"{jenkins_mock}/view/BetaView/",
                "description": "beta",
            },
            {
                "name": "AlphaView",
                "url": f"{jenkins_mock}/view/AlphaView/",
                "description": "alpha",
            },
        ],
    }


def test_jenkins_views_list_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, _views_list_mock(jenkins_mock))
    python = _run("python", tmp_path, "views", "primary", "--json")
    groovy = _run("groovy", tmp_path, "views", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["operation"] == "views"
    assert [item["name"] for item in payload["items"]] == ["AlphaView", "BetaView"]


def test_jenkins_views_list_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, _views_list_mock(jenkins_mock))
    python = _run("python", tmp_path, "views", "primary")
    groovy = _run("groovy", tmp_path, "views", "primary")
    _assert_parity_human(python, groovy)


def test_jenkins_views_list_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/api/json"] = (200, {"views": []})
    python = _run("python", tmp_path, "views", "primary", "--json")
    groovy = _run("groovy", tmp_path, "views", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["items"] == []
    assert payload["message"] == "No views found"


def test_jenkins_views_detail_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/view/All/api/json"] = (
        200,
        {
            "name": "All",
            "url": f"{jenkins_mock}/view/All/",
            "description": "default",
            "jobs": [
                {
                    "name": "Beta",
                    "url": f"{jenkins_mock}/job/Beta/",
                    "color": "blue",
                    "buildable": True,
                    "inQueue": False,
                },
                {
                    "name": "Alpha",
                    "url": f"{jenkins_mock}/job/Alpha/",
                    "color": "red",
                    "buildable": False,
                    "inQueue": True,
                },
            ],
        },
    )
    python = _run("python", tmp_path, "views", "primary", "--view", "All", "--json")
    groovy = _run("groovy", tmp_path, "views", "primary", "--view", "All", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["view"] == "All"
    assert [job["name"] for job in payload["items"][0]["jobs"]] == ["Alpha", "Beta"]


def test_jenkins_views_detail_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/view/All/api/json"] = (
        200,
        {
            "name": "All",
            "url": f"{jenkins_mock}/view/All/",
            "description": "default",
            "jobs": [
                {
                    "name": "Alpha",
                    "url": f"{jenkins_mock}/job/Alpha/",
                    "color": "red",
                    "buildable": False,
                    "inQueue": True,
                },
            ],
        },
    )
    python = _run("python", tmp_path, "views", "primary", "--view", "All")
    groovy = _run("groovy", tmp_path, "views", "primary", "--view", "All")
    _assert_parity_human(python, groovy)


def test_jenkins_views_not_found_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/view/Missing/api/json"] = (404, {"error": "not found"})
    python = _run("python", tmp_path, "views", "primary", "--view", "Missing", "--json")
    groovy = _run("groovy", tmp_path, "views", "primary", "--view", "Missing", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 1


def test_jenkins_views_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/view/All/api/json"] = (401, None)
    python = _run("python", tmp_path, "views", "primary", "--view", "All", "--json")
    groovy = _run("groovy", tmp_path, "views", "primary", "--view", "All", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def test_jenkins_whoami_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/whoAmI/api/json"] = (
        200,
        {
            "name": "bot",
            "authenticated": True,
            "authorities": [{"authority": "admin"}],
        },
    )
    python = _run("python", tmp_path, "whoami", "primary", "--json")
    groovy = _run("groovy", tmp_path, "whoami", "primary", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    assert "admin" not in python.stdout
    assert "admin" not in groovy.stdout
    item = _normalize_fetched_at_json(python.stdout)["items"][0]
    assert set(item) == {"name", "authenticated"}
    assert item["name"] == "bot"


def test_jenkins_whoami_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/whoAmI/api/json"] = (
        200,
        {"name": "bot", "authenticated": True, "authorities": [{"authority": "admin"}]},
    )
    python = _run("python", tmp_path, "whoami", "primary")
    groovy = _run("groovy", tmp_path, "whoami", "primary")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    assert "admin" not in python.stdout
    assert "admin" not in groovy.stdout


def test_jenkins_whoami_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/whoAmI/api/json"] = (403, None)
    python = _run("python", tmp_path, "whoami", "primary", "--json")
    groovy = _run("groovy", tmp_path, "whoami", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


def _credential_domains_mock(jenkins_mock: str) -> dict[str, Any]:
    return {
        "domains": [
            {
                "domainName": "global",
                "displayName": "Global",
                "description": "default",
                "url": f"{jenkins_mock}/credentials/store/system/domain/_/",
                "credentials": [{"id": "secret-id", "secretValue": "must-not-appear"}],
            },
            {
                "domainName": "custom",
                "displayName": "Custom",
                "description": "",
                "url": f"{jenkins_mock}/credentials/store/system/domain/custom/",
            },
        ],
    }


def test_jenkins_credential_domains_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/api/json"] = (
        200,
        _credential_domains_mock(jenkins_mock),
    )
    python = _run("python", tmp_path, "credential-domains", "primary", "--json")
    groovy = _run("groovy", tmp_path, "credential-domains", "primary", "--json")
    _assert_parity_json(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    assert "secret-id" not in python.stdout
    assert "secret-id" not in groovy.stdout
    payload = _normalize_fetched_at_json(python.stdout)
    assert [item["domain_name"] for item in payload["items"]] == ["custom", "global"]
    assert set(payload["items"][0]) == {"domain_name", "display_name", "description", "url"}


def test_jenkins_credential_domains_human(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/api/json"] = (
        200,
        _credential_domains_mock(jenkins_mock),
    )
    python = _run("python", tmp_path, "credential-domains", "primary")
    groovy = _run("groovy", tmp_path, "credential-domains", "primary")
    _assert_parity_human(python, groovy)
    _assert_no_secrets(python.stdout)
    _assert_no_secrets(groovy.stdout)
    assert "secret-id" not in python.stdout
    assert "must-not-appear" not in groovy.stdout


def test_jenkins_credential_domains_empty_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/api/json"] = (200, {"domains": []})
    python = _run("python", tmp_path, "credential-domains", "primary", "--json")
    groovy = _run("groovy", tmp_path, "credential-domains", "primary", "--json")
    _assert_parity_json(python, groovy)
    payload = _normalize_fetched_at_json(python.stdout)
    assert payload["items"] == []
    assert payload["message"] == "No credential domains found"


def test_jenkins_credential_domains_access_blocked_json(tmp_path: Path, jenkins_mock: str) -> None:
    _write_properties(tmp_path, _properties_for(jenkins_mock))
    _JenkinsMockState.routes["/credentials/store/system/api/json"] = (401, None)
    python = _run("python", tmp_path, "credential-domains", "primary", "--json")
    groovy = _run("groovy", tmp_path, "credential-domains", "primary", "--json")
    _assert_parity_json(python, groovy)
    assert python.returncode == groovy.returncode == 3


@pytest.mark.parametrize(
    "arguments",
    [
        ("health", "../bad", "--json"),
        ("health", "--json"),
        ("job", "primary", "../bad", "--json"),
        ("job", "--json"),
        ("syntax-check", "--json"),
        ("nodes", "--json"),
        ("queue", "--json"),
        ("jobs", "--json"),
        ("artifacts", "primary", "Demo", "--json"),
        ("views", "--json"),
        ("whoami", "--json"),
        ("credential-domains", "--json"),
        ("jobs", "primary", "--folder", "../bad", "--json"),
        ("views", "primary", "--view", "../bad", "--json"),
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
        ("nodes",),
        ("artifacts", "primary", "Demo"),
        ("jobs", "primary", "--folder", "../bad"),
    ],
)
def test_jenkins_invalid_input_human(tmp_path: Path, arguments: tuple[str, ...]) -> None:
    _write_properties(tmp_path, _properties_for("http://127.0.0.1:1"))
    python = _run("python", tmp_path, *arguments)
    groovy = _run("groovy", tmp_path, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == groovy.returncode == 1
