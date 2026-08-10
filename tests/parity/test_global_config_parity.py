import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / "ai-worklog"


def _run(
    runtime: str,
    env: dict[str, str],
    *arguments: str,
    cwd: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [str(CLI), "--runtime", runtime, *arguments]
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=30,
        env=env,
        cwd=str(cwd) if cwd is not None else None,
    )


def _run_launcher(
    env: dict[str, str],
    *arguments: str,
    cwd: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(CLI), *arguments],
        capture_output=True,
        text=True,
        timeout=30,
        env=env,
        cwd=str(cwd) if cwd is not None else None,
    )


def _assert_parity_json(
    python: subprocess.CompletedProcess[str],
    groovy: subprocess.CompletedProcess[str],
) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stdout: {python.stdout!r}\n"
        f"groovy stdout: {groovy.stdout!r}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    python_payload = json.loads(python.stdout) if python.stdout.strip() else None
    groovy_payload = json.loads(groovy.stdout) if groovy.stdout.strip() else None
    if python_payload != groovy_payload:
        pytest.fail(
            "JSON payload mismatch\n"
            f"python:\n{json.dumps(python_payload, indent=2, sort_keys=True, ensure_ascii=False)}\n"
            f"groovy:\n{json.dumps(groovy_payload, indent=2, sort_keys=True, ensure_ascii=False)}"
        )


def _assert_parity_human(
    python: subprocess.CompletedProcess[str],
    groovy: subprocess.CompletedProcess[str],
) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stdout: {python.stdout!r}\n"
        f"groovy stdout: {groovy.stdout!r}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    if python.stdout != groovy.stdout:
        pytest.fail(
            "Human output mismatch\n"
            f"python:\n{python.stdout!r}\n"
            f"groovy:\n{groovy.stdout!r}"
        )


def _assert_parity_exact(
    python: subprocess.CompletedProcess[str],
    groovy: subprocess.CompletedProcess[str],
) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stdout: {python.stdout!r}\n"
        f"groovy stdout: {groovy.stdout!r}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stdout == groovy.stdout
    assert python.stderr == groovy.stderr


def _seed(env: dict[str, str], *arguments: str) -> None:
    result = subprocess.run(
        [str(CLI), "--runtime", "python", *arguments],
        capture_output=True,
        text=True,
        timeout=30,
        env=env,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def _clear_config(env: dict[str, str]) -> None:
    config = Path(env["AI_WORKLOG_HOME"]) / "config.json"
    if config.exists():
        config.unlink()


def _reset_registry(
    env: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
) -> None:
    home = Path(env["AI_WORKLOG_HOME"])
    config = home / "config.json"
    if config.exists():
        config.unlink()
    _seed(
        env,
        "workspace",
        "add",
        "work",
        str(work_workspace),
        "--default",
    )
    _seed(env, "workspace", "add", "test", str(test_workspace))


def _run_parity(
    env: dict[str, str],
    *arguments: str,
    cwd: Path | None = None,
    reset: tuple[Path, Path] | None = None,
) -> tuple[subprocess.CompletedProcess[str], subprocess.CompletedProcess[str]]:
    if reset is not None:
        _reset_registry(env, reset[0], reset[1])
    python = _run("python", env, *arguments, cwd=cwd)
    if reset is not None:
        _reset_registry(env, reset[0], reset[1])
    groovy = _run("groovy", env, *arguments, cwd=cwd)
    return python, groovy


@pytest.fixture
def isolated_home(tmp_path: Path) -> Path:
    home = tmp_path / "home"
    home.mkdir()
    return home


@pytest.fixture
def env_home(isolated_home: Path) -> dict[str, str]:
    env = os.environ.copy()
    env["AI_WORKLOG_HOME"] = str(isolated_home)
    env.pop("AI_WORKLOG_WORKSPACE", None)
    env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
    env.pop("AI_WORKLOG_RUNTIME", None)
    return env


@pytest.fixture
def work_workspace(tmp_path: Path) -> Path:
    workspace = tmp_path / "work"
    workspace.mkdir()
    (workspace / "jira").mkdir()
    sentinel = workspace / "sentinel.txt"
    sentinel.write_text("keep")
    return workspace


@pytest.fixture
def test_workspace(tmp_path: Path) -> Path:
    workspace = tmp_path / "test"
    workspace.mkdir()
    (workspace / "prompt.log").touch()
    sentinel = workspace / "sentinel.txt"
    sentinel.write_text("keep")
    return workspace


@pytest.fixture
def outside_dir(tmp_path: Path) -> Path:
    directory = tmp_path / "outside"
    directory.mkdir()
    return directory


@pytest.fixture
def seeded_registry(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
) -> None:
    _seed(
        env_home,
        "workspace",
        "add",
        "work",
        str(work_workspace),
        "--default",
    )
    _seed(env_home, "workspace", "add", "test", str(test_workspace))


def test_workspace_add_human(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    arguments = ("workspace", "add", "work", str(work_workspace), "--default")
    _clear_config(env_home)
    python = _run("python", env_home, *arguments)
    _clear_config(env_home)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_add_json(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    arguments = ("workspace", "add", "work", str(work_workspace), "--default", "--json")
    _clear_config(env_home)
    python = _run("python", env_home, *arguments)
    _clear_config(env_home)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_add_idempotent_human(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    _seed(env_home, "workspace", "add", "work", str(work_workspace), "--default")
    arguments = ("workspace", "add", "work", str(work_workspace))
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_add_idempotent_json(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    _seed(env_home, "workspace", "add", "work", str(work_workspace), "--default")
    arguments = ("workspace", "add", "work", str(work_workspace), "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_add_conflict(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
) -> None:
    _seed(env_home, "workspace", "add", "work", str(work_workspace))
    arguments = ("workspace", "add", "work", str(test_workspace))
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_add_missing_path(
    env_home: dict[str, str],
    tmp_path: Path,
) -> None:
    missing = tmp_path / "missing"
    arguments = ("workspace", "add", "work", str(missing))
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_add_invalid_name(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    arguments = ("workspace", "add", "../bad", str(work_workspace))
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_list_human(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "list")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_list_json(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "list", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_show_human(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "show", "test")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_show_json(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "show", "test", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_show_unknown_human(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "show", "missing")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_show_unknown_json(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "show", "missing", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)
    assert python.returncode == 1


def test_workspace_default_show_human(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "default")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_default_show_json(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("workspace", "default", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_default_show_none_human(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "default")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_default_show_none_json(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "default", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)
    assert python.returncode == 1


def test_workspace_default_set_human(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
    seeded_registry: None,
) -> None:
    python, groovy = _run_parity(
        env_home,
        "workspace",
        "default",
        "test",
        reset=(work_workspace, test_workspace),
    )
    _assert_parity_human(python, groovy)


def test_workspace_default_set_json(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
    seeded_registry: None,
) -> None:
    python, groovy = _run_parity(
        env_home,
        "workspace",
        "default",
        "test",
        "--json",
        reset=(work_workspace, test_workspace),
    )
    _assert_parity_json(python, groovy)


def test_workspace_remove_human(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
    seeded_registry: None,
) -> None:
    python, groovy = _run_parity(
        env_home,
        "workspace",
        "remove",
        "work",
        reset=(work_workspace, test_workspace),
    )
    _assert_parity_human(python, groovy)
    assert work_workspace.is_dir()
    assert (work_workspace / "sentinel.txt").read_text() == "keep"


def test_workspace_remove_json(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
    seeded_registry: None,
) -> None:
    python, groovy = _run_parity(
        env_home,
        "workspace",
        "remove",
        "test",
        "--json",
        reset=(work_workspace, test_workspace),
    )
    _assert_parity_json(python, groovy)
    assert test_workspace.is_dir()
    assert (test_workspace / "sentinel.txt").read_text() == "keep"


def test_workspace_remove_unknown(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "remove", "missing")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_workspace_remove_clears_default_not_directory(
    env_home: dict[str, str],
    work_workspace: Path,
    test_workspace: Path,
) -> None:
    python, groovy = _run_parity(
        env_home,
        "workspace",
        "remove",
        "work",
        reset=(work_workspace, test_workspace),
    )
    _assert_parity_human(python, groovy)
    _reset_registry(env_home, work_workspace, test_workspace)
    _seed(env_home, "workspace", "remove", "work")
    python_default = _run("python", env_home, "workspace", "default")
    _reset_registry(env_home, work_workspace, test_workspace)
    _seed(env_home, "workspace", "remove", "work")
    groovy_default = _run("groovy", env_home, "workspace", "default")
    _assert_parity_human(python_default, groovy_default)
    assert python_default.returncode == 1
    assert work_workspace.is_dir()


@pytest.mark.parametrize(
    "arguments",
    [
        ("-w", "test", "workspace", "current", "--json"),
        ("workspace", "current", "-w", "test", "--json"),
        ("--workspace-name", "test", "workspace", "current", "--json"),
        ("workspace", "current", "--workspace-name", "test", "--json"),
    ],
)
def test_workspace_current_name_option_placement(
    env_home: dict[str, str],
    seeded_registry: None,
    arguments: tuple[str, ...],
) -> None:
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


@pytest.mark.parametrize(
    "placement",
    ["before", "after"],
)
def test_workspace_current_path_option_placement(
    env_home: dict[str, str],
    test_workspace: Path,
    placement: str,
) -> None:
    if placement == "before":
        arguments = (
            "--workspace",
            str(test_workspace),
            "workspace",
            "current",
            "--json",
        )
    else:
        arguments = (
            "workspace",
            "current",
            "--workspace",
            str(test_workspace),
            "--json",
        )
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_workspace_current_explicit_path(
    env_home: dict[str, str],
    seeded_registry: None,
    test_workspace: Path,
    outside_dir: Path,
) -> None:
    arguments = ("workspace", "current", "--workspace", str(test_workspace), "--json")
    python = _run("python", env_home, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env_home, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_explicit_name(
    env_home: dict[str, str],
    seeded_registry: None,
    outside_dir: Path,
) -> None:
    arguments = ("workspace", "current", "-w", "test", "--json")
    python = _run("python", env_home, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env_home, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_env_path(
    env_home: dict[str, str],
    seeded_registry: None,
    test_workspace: Path,
    outside_dir: Path,
) -> None:
    env = env_home.copy()
    env["AI_WORKLOG_WORKSPACE"] = str(test_workspace)
    arguments = ("workspace", "current", "--json")
    python = _run("python", env, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_env_name(
    env_home: dict[str, str],
    seeded_registry: None,
    outside_dir: Path,
) -> None:
    env = env_home.copy()
    env["AI_WORKLOG_WORKSPACE_NAME"] = "test"
    arguments = ("workspace", "current", "--json")
    python = _run("python", env, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_cwd_marker(
    env_home: dict[str, str],
    seeded_registry: None,
    work_workspace: Path,
) -> None:
    arguments = ("workspace", "current", "--json")
    python = _run("python", env_home, *arguments, cwd=work_workspace)
    groovy = _run("groovy", env_home, *arguments, cwd=work_workspace)
    _assert_parity_json(python, groovy)


def test_workspace_current_default_workspace(
    env_home: dict[str, str],
    seeded_registry: None,
    outside_dir: Path,
) -> None:
    arguments = ("workspace", "current", "--json")
    python = _run("python", env_home, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env_home, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_resolution_precedence(
    env_home: dict[str, str],
    seeded_registry: None,
    work_workspace: Path,
    test_workspace: Path,
    outside_dir: Path,
) -> None:
    env = env_home.copy()
    env["AI_WORKLOG_WORKSPACE"] = str(test_workspace)
    env["AI_WORKLOG_WORKSPACE_NAME"] = "test"
    arguments = (
        "--workspace",
        str(work_workspace),
        "workspace",
        "current",
        "-w",
        "test",
        "--json",
    )
    python = _run("python", env, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env, *arguments, cwd=outside_dir)
    _assert_parity_json(python, groovy)


def test_workspace_current_missing_explicit_path(
    env_home: dict[str, str],
    tmp_path: Path,
) -> None:
    missing = tmp_path / "missing"
    arguments = ("workspace", "current", "--workspace", str(missing), "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)
    assert python.returncode == 1


def test_workspace_current_missing_explicit_name(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "current", "-w", "missing", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)
    assert python.returncode == 1


def test_workspace_current_stale_registered_path(
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    _seed(env_home, "workspace", "add", "work", str(work_workspace), "--default")
    shutil.rmtree(work_workspace)
    arguments = ("workspace", "show", "work", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)
    current = ("workspace", "current", "-w", "work", "--json")
    python_current = _run("python", env_home, *current)
    groovy_current = _run("groovy", env_home, *current)
    _assert_parity_json(python_current, groovy_current)
    assert python_current.returncode == groovy_current.returncode == 1


def test_config_show_human(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("config", "show")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_config_show_json(
    env_home: dict[str, str],
    seeded_registry: None,
) -> None:
    arguments = ("config", "show", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_config_runtime_show_human(
    env_home: dict[str, str],
) -> None:
    arguments = ("config", "runtime")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_config_runtime_show_json(
    env_home: dict[str, str],
) -> None:
    arguments = ("config", "runtime", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_config_runtime_set_human(
    env_home: dict[str, str],
) -> None:
    _clear_config(env_home)
    python = _run("python", env_home, "config", "runtime", "python")
    _clear_config(env_home)
    groovy = _run("groovy", env_home, "config", "runtime", "python")
    _assert_parity_human(python, groovy)


def test_config_runtime_set_json(
    env_home: dict[str, str],
) -> None:
    _clear_config(env_home)
    python = _run("python", env_home, "config", "runtime", "python", "--json")
    _clear_config(env_home)
    groovy = _run("groovy", env_home, "config", "runtime", "python", "--json")
    _assert_parity_json(python, groovy)


def test_config_runtime_invalid(
    env_home: dict[str, str],
) -> None:
    arguments = ("config", "runtime", "ruby")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1


def test_launcher_uses_persisted_runtime(
    env_home: dict[str, str],
) -> None:
    _seed(env_home, "config", "runtime", "python")
    launcher = _run_launcher(env_home, "--version")
    explicit = _run("python", env_home, "--version")
    assert launcher.returncode == explicit.returncode == 0
    assert launcher.stdout == explicit.stdout
    assert launcher.stderr == explicit.stderr


def test_launcher_explicit_runtime_override(
    env_home: dict[str, str],
) -> None:
    _seed(env_home, "config", "runtime", "python")
    launcher = _run_launcher(env_home, "--runtime", "groovy", "--version")
    explicit = _run("groovy", env_home, "--version")
    _assert_parity_exact(launcher, explicit)


def test_launcher_ai_worklog_runtime_override(
    env_home: dict[str, str],
) -> None:
    _seed(env_home, "config", "runtime", "python")
    env = env_home.copy()
    env["AI_WORKLOG_RUNTIME"] = "groovy"
    launcher = _run_launcher(env, "--version")
    explicit = _run("groovy", env, "--version")
    _assert_parity_exact(launcher, explicit)


def test_malformed_config_blocks_workspace_add(
    isolated_home: Path,
    env_home: dict[str, str],
    work_workspace: Path,
) -> None:
    isolated_home.mkdir(mode=0o700, exist_ok=True)
    config_path = isolated_home / "config.json"
    config_path.write_text("{bad", encoding="utf-8")
    original = config_path.read_text(encoding="utf-8")
    arguments = ("workspace", "add", "work", str(work_workspace))
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)
    assert python.returncode == 1
    assert config_path.read_text(encoding="utf-8") == original


def test_malformed_config_launcher_rejects(
    isolated_home: Path,
    env_home: dict[str, str],
) -> None:
    isolated_home.mkdir(mode=0o700, exist_ok=True)
    config_path = isolated_home / "config.json"
    config_path.write_text("{bad", encoding="utf-8")
    launcher = _run_launcher(env_home, "config", "runtime")
    assert launcher.returncode == 1
    assert "Malformed global configuration" in launcher.stderr


def test_workspace_current_human(
    env_home: dict[str, str],
    seeded_registry: None,
    outside_dir: Path,
) -> None:
    arguments = ("workspace", "current", "-w", "test")
    python = _run("python", env_home, *arguments, cwd=outside_dir)
    groovy = _run("groovy", env_home, *arguments, cwd=outside_dir)
    _assert_parity_human(python, groovy)


def test_workspace_list_empty_human(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "list")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_human(python, groovy)


def test_workspace_list_empty_json(
    env_home: dict[str, str],
) -> None:
    arguments = ("workspace", "list", "--json")
    python = _run("python", env_home, *arguments)
    groovy = _run("groovy", env_home, *arguments)
    _assert_parity_json(python, groovy)


def test_launcher_persisted_runtime_parity_across_backends(
    env_home: dict[str, str],
) -> None:
    python_set = _run("python", env_home, "config", "runtime", "python", "--json")
    groovy_set = _run("groovy", env_home, "config", "runtime", "python", "--json")
    _assert_parity_json(python_set, groovy_set)
    python_launcher = _run_launcher(env_home, "config", "runtime", "--json")
    groovy_launcher = _run_launcher(env_home, "config", "runtime", "--json")
    _assert_parity_json(python_launcher, groovy_launcher)
