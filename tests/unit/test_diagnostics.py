import json
import os
import pytest

from ai_worklog_framework.diagnostics.executor import run_pack
from ai_worklog_framework.paths import WorkspacePaths


def executable(tmp_path, name, output):
    path = tmp_path / name
    path.write_text(f"#!/bin/sh\nprintf '%s' '{output}'\n")
    path.chmod(0o755)
    return path


def test_diagnostic_executes_redacts_and_writes_evidence(tmp_path, monkeypatch):
    executable(tmp_path, "probe", "Bearer secret-value")
    monkeypatch.setenv("PATH", f"{tmp_path}:{os.environ['PATH']}")
    paths = WorkspacePaths(tmp_path)
    pack = {
        "read_only": True,
        "prerequisites": ["probe"],
        "required_parameters": ["target"],
        "steps": [{
            "id": "probe",
            "command": ["probe", "{target}"],
            "timeout_seconds": 5,
        }],
    }

    bundle, target = run_pack("test-pack", pack, {"target": "one"}, paths)

    assert bundle.status == "success"
    assert bundle.steps[0].stdout == "***REDACTED***"
    assert json.loads(target.read_text())["status"] == "success"


def test_diagnostic_blocks_missing_parameters(tmp_path):
    paths = WorkspacePaths(tmp_path)
    pack = {
        "read_only": True,
        "prerequisites": [],
        "required_parameters": ["target"],
        "steps": [],
    }
    bundle, _ = run_pack("test-pack", pack, {}, paths)
    assert bundle.status == "blocked"
    assert bundle.steps[0].id == "parameters"


def test_diagnostic_refuses_write_capable_pack(tmp_path):
    paths = WorkspacePaths(tmp_path)
    pack = {
        "read_only": False,
        "prerequisites": [],
        "required_parameters": [],
        "steps": [],
    }
    bundle, _ = run_pack("test-pack", pack, {}, paths)
    assert bundle.status == "blocked"
    assert bundle.steps[0].id == "safety"


@pytest.mark.parametrize(
    "parameters",
    [
        {"host": "-oProxyCommand=unsafe"},
        {"namespace": "test\nunsafe"},
        {"url": "file:///tmp/secret"},
    ],
)
def test_diagnostic_rejects_unsafe_parameters(tmp_path, parameters):
    with pytest.raises(ValueError):
        run_pack(
            "test-pack",
            {
                "read_only": True,
                "prerequisites": [],
                "required_parameters": [],
                "steps": [],
            },
            parameters,
            WorkspacePaths(tmp_path),
        )
