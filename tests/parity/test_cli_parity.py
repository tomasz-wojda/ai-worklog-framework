import json
import os
import platform
import re
import subprocess
from pathlib import Path

import pytest

from tests.parity.conftest import defer_parity_suite

pytestmark = defer_parity_suite()


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / ("ai-worklog.cmd" if platform.system() == "Windows" else "ai-worklog")
WORKSPACE = Path(__file__).parent / "fixtures" / "workspace"


def run(runtime: str | None, *arguments: str) -> subprocess.CompletedProcess[str]:
    command = [str(CLI)]
    if runtime:
        command.extend(["--runtime", runtime])
    command.extend(["--workspace", str(WORKSPACE), *arguments])
    return subprocess.run(command, capture_output=True, text=True, timeout=30)


def run_in_workspace(
    runtime: str,
    workspace: Path,
    *arguments: str,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [
        str(CLI),
        "--runtime",
        runtime,
        "--workspace",
        str(workspace),
        *arguments,
    ]
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=30,
        env=env,
    )


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
        r"ai-worklog 0\.7\.0 \(groovy \d+(?:\.\d+)+ / java \d+(?:\.\d+)+(?:[-+][^)]+)?\)",
        groovy.stdout.strip(),
    )
    assert (
        python.stdout.strip()
        == f"ai-worklog 0.7.0 (python {platform.python_version()})"
    )


@pytest.mark.parametrize(
    "arguments",
    [
        ("catalog", "validate"),
        ("catalog", "search", "example"),
        ("diag", "list"),
        ("state", "list"),
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


def test_state_json_matches() -> None:
    python = run("python", "state", "show", "TEST-1")
    groovy = run("groovy", "state", "show", "TEST-1")
    assert python.returncode == groovy.returncode == 0
    assert json.loads(python.stdout) == json.loads(groovy.stdout)


def test_workspace_apply_dry_run_matches(tmp_path) -> None:
    def execute(runtime: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(CLI), "--runtime", runtime, "workspace", "apply", str(tmp_path), "--dry-run"],
            capture_output=True,
            text=True,
            timeout=30,
        )

    python = execute("python")
    groovy = execute("groovy")
    assert python.returncode == groovy.returncode == 0
    assert python.stdout == groovy.stdout





def _minimal_vault(root: Path) -> Path:
    vault = root / "ai-vault"
    skills = vault / "skills"
    skills.mkdir(parents=True)
    (skills / "manifest.json").write_text(
        json.dumps({"version": 1, "skills": []}), encoding="utf-8"
    )
    scripts = vault / "scripts"
    scripts.mkdir()
    script = scripts / "validate-skills.sh"
    script.write_text("#!/bin/sh\n", encoding="utf-8")
    script.chmod(0o700)
    return vault


def test_preflight_reports_an_empty_service_directory_as_not_configured(tmp_path) -> None:
    workspace = tmp_path / "candidate"
    (workspace / "worklog").mkdir(parents=True)
    (workspace / "integrations/newrelic").mkdir(parents=True)
    env = os.environ.copy()
    env["AI_WORKLOG_HOME"] = str(tmp_path / "home")
    env.pop("AI_WORKLOG_WORKSPACE", None)
    env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
    env.pop("AI_WORKLOG_RUNTIME", None)

    outputs = {}
    for runtime in ("python", "groovy"):
        result = run_in_workspace(
            runtime, workspace, "preflight", "--service", "newrelic", env=env
        )
        outputs[runtime] = result.stdout
        assert "[NOT CONFIGURED] newrelic: Not configured" in result.stdout
        assert "[BLOCKED]" not in result.stdout

    assert outputs["python"] == outputs["groovy"]


def test_artifactory_preflight_uses_metadata_only_with_parity(tmp_path) -> None:
    env = os.environ.copy()
    env["AI_WORKLOG_HOME"] = str(tmp_path / "home")
    env.pop("AI_WORKLOG_WORKSPACE", None)
    env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
    env.pop("AI_WORKLOG_RUNTIME", None)
    env.pop("ARTIFACTORY_URL", None)
    env.pop("ARTIFACTORY_TOKEN", None)
    outputs = {}
    for runtime in ("python", "groovy"):
        workspace = tmp_path / f"{runtime}-candidate"
        (workspace / "worklog").mkdir(parents=True)
        (workspace / "prompt.log").write_text("", encoding="utf-8")
        service = workspace / "integrations/artifactory"
        service.mkdir(parents=True)
        (service / "creds").write_text("not valid credential syntax", encoding="utf-8")
        result = run_in_workspace(
            runtime, workspace, "preflight", "--service", "artifactory", env=env
        )
        outputs[runtime] = result.stdout
        assert result.returncode == 0
        assert "[OK] artifactory: Credential source present (file)" in result.stdout
        assert "not valid credential syntax" not in result.stdout

    assert outputs["python"] == outputs["groovy"]


def test_workspace_apply_preserves_existing_integration_symlink_with_parity(tmp_path) -> None:
    vault = _minimal_vault(tmp_path)
    results = {}
    for runtime in ("python", "groovy"):
        workspace = tmp_path / f"{runtime}-workspace"
        (workspace / "jira").mkdir(parents=True)
        canonical = workspace / "integrations" / "jira"
        canonical.parent.mkdir()
        canonical.symlink_to("../other")
        env = os.environ.copy()
        env["AI_WORKLOG_HOME"] = str(tmp_path / f"{runtime}-home")
        env.pop("AI_WORKLOG_WORKSPACE", None)
        env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
        env.pop("AI_WORKLOG_RUNTIME", None)
        env.pop("AI_WORKLOG_AI_VAULT_ROOT", None)
        results[runtime] = subprocess.run(
            [
                str(CLI),
                "--runtime",
                runtime,
                "workspace",
                "apply",
                str(workspace),
                "--ai-vault",
                str(vault),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            env=env,
        )
        assert results[runtime].returncode == 0
        assert canonical.is_symlink()
        assert os.readlink(canonical) == "../other"

    python_output = results["python"].stdout.replace(
        str(tmp_path / "python-workspace"), "<WORKSPACE>"
    )
    groovy_output = results["groovy"].stdout.replace(
        str(tmp_path / "groovy-workspace"), "<WORKSPACE>"
    )
    assert python_output == groovy_output


def test_diagnostic_blocked_output_matches(tmp_path) -> None:
    output = tmp_path / "evidence.json"
    arguments = (
        "diag", "run", "k8s-workload", "--namespace", "test",
        "--output", str(output),
    )
    python = run("python", *arguments)
    groovy = run("groovy", *arguments)
    assert python.returncode == groovy.returncode == 3
    assert python.stdout == groovy.stdout


def test_scoped_preflight_status_matches() -> None:
    python = run("python", "preflight", "--service", "jira")
    groovy = run("groovy", "preflight", "--service", "jira")
    assert python.returncode == groovy.returncode
    python_statuses = sorted(
        line.strip().split(" ", 2)[:2]
        for line in python.stdout.splitlines()
        if line.strip().startswith("[")
    )
    groovy_statuses = sorted(
        line.strip().split(" ", 2)[:2]
        for line in groovy.stdout.splitlines()
        if line.strip().startswith("[")
    )
    assert python_statuses == groovy_statuses
    assert "tool:" not in python.stdout
    assert "tool:" not in groovy.stdout


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


def test_reconciliation_human_output_matches() -> None:
    arguments = ("reconcile", "status", "TEST-1", "--system", "git")
    python = run("python", *arguments)
    groovy = run("groovy", *arguments)
    assert python.returncode == groovy.returncode == 0
    assert normalize(python.stdout) == normalize(groovy.stdout)


def test_reconciliation_json_matches() -> None:
    arguments = ("reconcile", "status", "TEST-1", "--json")
    python = run("python", *arguments)
    groovy = run("groovy", *arguments)
    assert python.returncode == groovy.returncode == 0
    python_payload = json.loads(python.stdout)
    groovy_payload = json.loads(groovy.stdout)
    python_payload["timestamp"] = "<TIMESTAMP>"
    groovy_payload["timestamp"] = "<TIMESTAMP>"
    for payload in (python_payload, groovy_payload):
        for observation in payload["observations"]:
            if "fetched_at" in observation["details"]:
                observation["details"]["fetched_at"] = "<TIMESTAMP>"
    assert python_payload == groovy_payload


def test_reconciliation_user_errors_match() -> None:
    for arguments in (
        ("reconcile", "status", "TEST-999"),
        ("reconcile", "status", "TEST-1", "--system", "invalid"),
    ):
        python = run("python", *arguments)
        groovy = run("groovy", *arguments)
        assert python.returncode == groovy.returncode == 1
        assert python.stdout == groovy.stdout


def test_reconciliation_blocking_contradiction_matches(tmp_path) -> None:
    state_dir = tmp_path / ".ai-worklog" / "state"
    state_dir.mkdir(parents=True)
    state = json.loads(
        (WORKSPACE / ".ai-worklog" / "state" / "TEST-1.json").read_text()
    )
    state["repositories"] = ["missing-repository"]
    (state_dir / "TEST-1.json").write_text(json.dumps(state))
    arguments = (
        "reconcile", "status", "TEST-1", "--system", "git", "--json",
    )
    python = run_in_workspace("python", tmp_path, *arguments)
    groovy = run_in_workspace("groovy", tmp_path, *arguments)
    assert python.returncode == groovy.returncode == 3
    python_payload = json.loads(python.stdout)
    groovy_payload = json.loads(groovy.stdout)
    python_payload["timestamp"] = "<TIMESTAMP>"
    groovy_payload["timestamp"] = "<TIMESTAMP>"
    assert python_payload == groovy_payload
    assert python_payload["contradictions"][0]["code"] == "repo_missing"


def test_reconciliation_malformed_adapter_response_matches(tmp_path) -> None:
    state_dir = tmp_path / ".ai-worklog" / "state"
    catalog_dir = tmp_path / ".ai-worklog" / "catalog"
    fake_bin = tmp_path / "bin"
    state_dir.mkdir(parents=True)
    catalog_dir.mkdir(parents=True)
    fake_bin.mkdir()
    state = json.loads(
        (WORKSPACE / ".ai-worklog" / "state" / "TEST-1.json").read_text()
    )
    state["services"] = ["fixture-service"]
    (state_dir / "TEST-1.json").write_text(json.dumps(state))
    catalog = [{
        "id": "fixture-service",
        "name": "Fixture Service",
        "type": "application",
        "repositories": [{
            "url": "https://github.com/example-org/fixture-service",
            "local_dir": "fixture-service",
        }],
    }]
    (catalog_dir / "fixture.json").write_text(json.dumps(catalog))
    gh_name = "gh.cmd" if platform.system() == "Windows" else "gh"
    gh = fake_bin / gh_name
    if platform.system() == "Windows":
        gh.write_text("@echo off\nif \"%1\"==\"--version\" exit /b 0\necho not-json\n")
    else:
        gh.write_text(
            "#!/bin/sh\n"
            "if [ \"$1\" = \"--version\" ]; then exit 0; fi\n"
            "printf 'not-json'\n"
        )
        gh.chmod(0o755)
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
    arguments = (
        "reconcile", "status", "TEST-1", "--system", "github", "--json",
    )
    python = run_in_workspace("python", tmp_path, *arguments, env=env)
    groovy = run_in_workspace("groovy", tmp_path, *arguments, env=env)
    assert python.returncode == groovy.returncode == 2
    python_payload = json.loads(python.stdout)
    groovy_payload = json.loads(groovy.stdout)
    python_payload["timestamp"] = "<TIMESTAMP>"
    groovy_payload["timestamp"] = "<TIMESTAMP>"
    assert python_payload == groovy_payload


def test_reconciliation_redacts_adapter_payloads(tmp_path) -> None:
    state_dir = tmp_path / ".ai-worklog" / "state"
    catalog_dir = tmp_path / ".ai-worklog" / "catalog"
    fake_bin = tmp_path / "bin"
    state_dir.mkdir(parents=True)
    catalog_dir.mkdir(parents=True)
    fake_bin.mkdir()
    state = json.loads(
        (WORKSPACE / ".ai-worklog" / "state" / "TEST-1.json").read_text()
    )
    state["services"] = ["fixture-service"]
    (state_dir / "TEST-1.json").write_text(json.dumps(state))
    catalog = [{
        "id": "fixture-service",
        "name": "Fixture Service",
        "type": "application",
        "repositories": [{
            "url": "https://github.com/example-org/fixture-service",
            "local_dir": "fixture-service",
        }],
    }]
    (catalog_dir / "fixture.json").write_text(json.dumps(catalog))
    gh = fake_bin / "gh"
    gh.write_text(
        "#!/bin/sh\n"
        "if [ \"$1\" = \"--version\" ]; then exit 0; fi\n"
        "printf '[{\"number\":1,\"state\":\"OPEN\",\"isDraft\":false,"
        "\"url\":\"https://github.com/example-org/fixture-service/pull/1\","
        "\"title\":\"token=secret-value\"}]'\n"
    )
    gh.chmod(0o755)
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
    arguments = (
        "reconcile", "status", "TEST-1", "--system", "github", "--json",
    )
    python = run_in_workspace("python", tmp_path, *arguments, env=env)
    groovy = run_in_workspace("groovy", tmp_path, *arguments, env=env)
    assert python.returncode == groovy.returncode == 0
    assert "secret-value" not in python.stdout
    assert "secret-value" not in groovy.stdout


@pytest.mark.parametrize(
    ("marker", "expected_source"),
    [(".ai-worklog", "cwd_marker"), ("worklog", "cwd_legacy")],
)
def test_workspace_failure_messages_match_between_runtimes(
    tmp_path: Path, marker: str, expected_source: str
) -> None:
    workspace = tmp_path / "candidate"
    (workspace / marker).mkdir(parents=True)
    env = os.environ.copy()
    env["AI_WORKLOG_HOME"] = str(tmp_path / "home")
    env.pop("AI_WORKLOG_WORKSPACE", None)
    env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
    env.pop("AI_WORKLOG_RUNTIME", None)

    def invoke(runtime: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(CLI), "--runtime", runtime, "workspace", "ides"],
            capture_output=True,
            text=True,
            timeout=30,
            cwd=workspace,
            env=env,
        )

    python = invoke("python")
    groovy = invoke("groovy")
    assert python.stdout == groovy.stdout
    assert python.returncode == groovy.returncode
    assert f"(source: {expected_source})" in python.stdout
    assert str(workspace.resolve()) in python.stdout
