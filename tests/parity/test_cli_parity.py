import json
import platform
import re
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / "ai-worklog"
WORKSPACE = Path(__file__).parent / "fixtures" / "workspace"


def run(runtime: str | None, *arguments: str) -> subprocess.CompletedProcess[str]:
    command = [str(CLI)]
    if runtime:
        command.extend(["--runtime", runtime])
    command.extend(["--workspace", str(WORKSPACE), *arguments])
    return subprocess.run(command, capture_output=True, text=True, timeout=30)


def normalize(value: str) -> str:
    return re.sub(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}", "<TIMESTAMP>", value)


def test_runtime_versions_are_explicit() -> None:
    groovy = subprocess.run(
        [str(CLI), "--version"], capture_output=True, text=True, timeout=30
    )
    python = subprocess.run(
        [str(CLI), "--runtime", "python", "--version"],
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert groovy.returncode == 0
    assert python.returncode == 0
    assert re.fullmatch(
        r"ai-worklog 0\.1\.0 \(groovy \d+(?:\.\d+)+ / java \d+(?:\.\d+)+(?:[-+][^)]+)?\)",
        groovy.stdout.strip(),
    )
    assert (
        python.stdout.strip()
        == f"ai-worklog 0.1.0 (python {platform.python_version()})"
    )


@pytest.mark.parametrize(
    "arguments",
    [
        ("catalog", "validate"),
        ("catalog", "search", "example"),
        ("diag", "list"),
        ("diag", "run", "k8s-workload", "--namespace", "test"),
    ],
)
def test_stable_command_output_matches(arguments: tuple[str, ...]) -> None:
    python = run("python", *arguments)
    groovy = run("groovy", *arguments)
    assert groovy.returncode == python.returncode
    assert groovy.stderr == python.stderr
    assert groovy.stdout == python.stdout


def test_catalog_json_matches() -> None:
    python = run("python", "catalog", "show", "example-eks-platform")
    groovy = run("groovy", "catalog", "show", "example-eks-platform")
    assert python.returncode == groovy.returncode == 0
    assert json.loads(python.stdout) == json.loads(groovy.stdout)


@pytest.mark.parametrize(
    "arguments",
    [
        ("day", "start"),
        ("day", "end"),
        ("delivery", "status", "TEST-1"),
        ("closeout", "report", "TEST-1"),
    ],
)
def test_report_output_matches(arguments: tuple[str, ...]) -> None:
    python = run("python", *arguments)
    groovy = run("groovy", *arguments)
    assert groovy.returncode == python.returncode
    assert normalize(groovy.stdout) == normalize(python.stdout)


def test_user_error_matches() -> None:
    python = run("python", "catalog", "search", "does-not-exist")
    groovy = run("groovy", "catalog", "search", "does-not-exist")
    assert groovy.returncode == python.returncode == 1
    assert groovy.stdout == python.stdout
