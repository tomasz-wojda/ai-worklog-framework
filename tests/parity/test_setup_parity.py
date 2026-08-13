import json
import os
import re
import shutil
import stat
import subprocess
from copy import deepcopy
from pathlib import Path
from typing import Any, Callable

import pytest


ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "bin" / "ai-worklog"
FRAMEWORK_MARKER = "<FRAMEWORK>"

VAULT_MANIFEST = {
    "version": 1,
    "skills": [
        {
            "name": "developer-protocol",
            "dir": "developer-protocol",
            "required": True,
            "ides": ["cursor", "claude", "antigravity"],
        },
        {
            "name": "devops-daily-protocol",
            "dir": "devops-daily-protocol",
            "required": True,
            "ides": ["cursor"],
        },
    ],
}


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
        timeout=60,
        env=env,
        cwd=str(cwd) if cwd is not None else None,
    )


def _path_markers(home: Path, workspace: Path, vault: Path) -> dict[str, str]:
    return {
        os.path.realpath(str(home)): "<HOME>",
        os.path.realpath(str(workspace)): "<WORKSPACE>",
        os.path.realpath(str(vault)): "<VAULT>",
        os.path.realpath(str(ROOT)): FRAMEWORK_MARKER,
    }


def _normalize_string(value: str, markers: dict[str, str]) -> str:
    result = value
    for path, marker in sorted(markers.items(), key=lambda item: len(item[0]), reverse=True):
        result = result.replace(path, marker)
    result = re.sub(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\+00:00|Z)?",
        "<SYNCED_AT>",
        result,
    )
    return result


def _normalize_json_value(value: Any, markers: dict[str, str]) -> Any:
    if isinstance(value, dict):
        normalized = {key: _normalize_json_value(item, markers) for key, item in value.items()}
        if "synced_at" in normalized:
            normalized["synced_at"] = "<SYNCED_AT>"
        return normalized
    if isinstance(value, list):
        return [_normalize_json_value(item, markers) for item in value]
    if isinstance(value, str):
        return _normalize_string(value, markers)
    return value


def _normalize_setup_json(stdout: str, markers: dict[str, str]) -> Any:
    payload = json.loads(stdout.strip()) if stdout.strip() else None
    if payload is None:
        return None
    return _normalize_json_value(deepcopy(payload), markers)


def _normalize_setup_human(stdout: str, markers: dict[str, str]) -> str:
    lines = [_normalize_string(line, markers) for line in stdout.splitlines()]
    return "\n".join(lines)


def _assert_parity_json(
    python: subprocess.CompletedProcess[str],
    groovy: subprocess.CompletedProcess[str],
    markers: dict[str, str],
) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stdout: {python.stdout!r}\n"
        f"groovy stdout: {groovy.stdout!r}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    python_payload = _normalize_setup_json(python.stdout, markers)
    groovy_payload = _normalize_setup_json(groovy.stdout, markers)
    if python_payload != groovy_payload:
        pytest.fail(
            "JSON payload mismatch\n"
            f"python:\n{json.dumps(python_payload, indent=2, sort_keys=True, ensure_ascii=False)}\n"
            f"groovy:\n{json.dumps(groovy_payload, indent=2, sort_keys=True, ensure_ascii=False)}"
        )


def _assert_parity_human(
    python: subprocess.CompletedProcess[str],
    groovy: subprocess.CompletedProcess[str],
    markers: dict[str, str],
) -> None:
    assert python.returncode == groovy.returncode, (
        f"exit code mismatch: python={python.returncode} groovy={groovy.returncode}\n"
        f"python stdout: {python.stdout!r}\n"
        f"groovy stdout: {groovy.stdout!r}\n"
        f"python stderr: {python.stderr!r}\n"
        f"groovy stderr: {groovy.stderr!r}"
    )
    assert python.stderr == groovy.stderr
    python_output = _normalize_setup_human(python.stdout, markers)
    groovy_output = _normalize_setup_human(groovy.stdout, markers)
    if python_output != groovy_output:
        pytest.fail(
            "Human output mismatch\n"
            f"python:\n{python_output!r}\n"
            f"groovy:\n{groovy_output!r}"
        )


def _reset_home(home: Path) -> None:
    if home.exists():
        shutil.rmtree(home)
    home.mkdir(mode=0o700)


def _reset_workspace(workspace: Path) -> None:
    if workspace.exists():
        shutil.rmtree(workspace)
    workspace.mkdir()
    (workspace / "worklog").mkdir()
    (workspace / "prompt.log").touch()


def _sanitized_path_env(env: dict[str, str]) -> dict[str, str]:
    sanitized = env.copy()
    groovy_path = shutil.which("groovy", path=env.get("PATH"))
    if groovy_path:
        groovy_bin = str(Path(groovy_path).parent)
        sanitized["PATH"] = f"{groovy_bin}:/usr/bin:/bin:/usr/sbin:/sbin"
    else:
        sanitized["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
    return sanitized


def _reset_state(home: Path, workspace: Path) -> None:
    _reset_home(home)
    _reset_workspace(workspace)


def _make_vault(root: Path) -> Path:
    vault = root / "ai-vault"
    vault.mkdir(parents=True)
    scripts = vault / "scripts"
    scripts.mkdir()
    script = scripts / "validate-skills.sh"
    script.write_text("#!/bin/sh\n", encoding="utf-8")
    script.chmod(stat.S_IRWXU)
    skills = vault / "skills"
    skills.mkdir()
    (skills / "manifest.json").write_text(json.dumps(VAULT_MANIFEST), encoding="utf-8")
    for entry in VAULT_MANIFEST["skills"]:
        skill_dir = skills / entry["dir"]
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text("# Skill\n", encoding="utf-8")
    return vault


def _init_args(
    workspace: Path,
    vault: Path,
    *,
    name: str = "work",
    ides: list[str] | None = None,
    apply: bool = False,
    json_output: bool = False,
    default: bool = False,
    runtime: str | None = None,
) -> tuple[str, ...]:
    args: list[str] = ["setup", "init", name, str(workspace), "--ai-vault", str(vault)]
    for ide in ides or ["cursor"]:
        args.extend(["--ide", ide])
    if runtime:
        args.extend(["--runtime", runtime])
    if default:
        args.append("--default")
    if json_output:
        args.append("--json")
    if apply:
        args.append("--apply")
    return tuple(args)


def _workspace_args(
    workspace: Path,
    *tail: str,
    json_output: bool = False,
    apply: bool = False,
) -> tuple[str, ...]:
    args = ["--workspace", str(workspace), *tail]
    if json_output:
        args.append("--json")
    if apply:
        args.append("--apply")
    return tuple(args)


def _run_parity(
    env: dict[str, str],
    markers: dict[str, str],
    home: Path,
    workspace: Path,
    *arguments: str,
    prepare: Callable[[Path], None] | None = None,
) -> tuple[subprocess.CompletedProcess[str], subprocess.CompletedProcess[str]]:
    _reset_state(home, workspace)
    if prepare is not None:
        prepare(workspace)
    python = _run("python", env, *arguments)
    _reset_state(home, workspace)
    if prepare is not None:
        prepare(workspace)
    groovy = _run("groovy", env, *arguments)
    return python, groovy


def _seed_init(
    env: dict[str, str],
    workspace: Path,
    vault: Path,
    *,
    ides: list[str] | None = None,
    default: bool = True,
) -> None:
    result = _run(
        "python",
        env,
        *_init_args(workspace, vault, ides=ides, apply=True, default=default),
    )
    assert result.returncode == 0, result.stdout + result.stderr


def _collect_filesystem_state(workspace: Path, home: Path, vault: Path) -> dict[str, Any]:
    config_path = home / "config.json"
    config = json.loads(config_path.read_text(encoding="utf-8")) if config_path.is_file() else None
    manifest_path = workspace / ".ai-worklog" / "setup.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else None

    cursor_link = workspace / ".cursor/skills/developer-protocol"
    claude_link = workspace / ".claude/skills/developer-protocol"
    antigravity_copy = workspace / ".agents/skills/developer-protocol"

    setup_json = workspace / ".ai-worklog" / "setup.json"
    state = {
        "config_version": config.get("version") if config else None,
        "workspace_ides": config["workspaces"]["work"]["ides"] if config else None,
        "ai_vault_root": os.path.realpath(config["ai_vault_root"]) if config and config.get("ai_vault_root") else None,
        "setup_json_exists": setup_json.is_file(),
        "manifest_version": manifest.get("version") if manifest else None,
        "manifest_workspace_name": manifest.get("workspace_name") if manifest else None,
        "manifest_skill_count": len(manifest.get("skills", [])) if manifest else 0,
        "cursor_is_symlink": cursor_link.is_symlink(),
        "claude_is_symlink": claude_link.is_symlink() if claude_link.exists() else False,
        "antigravity_is_dir": antigravity_copy.is_dir(),
        "antigravity_is_symlink": antigravity_copy.is_symlink() if antigravity_copy.exists() else False,
        "cursor_target": os.path.realpath(str(cursor_link)) if cursor_link.is_symlink() else None,
        "claude_target": os.path.realpath(str(claude_link)) if claude_link.is_symlink() else None,
        "vault_target": os.path.realpath(str(vault / "skills/developer-protocol")),
    }
    return state


@pytest.fixture
def isolated_home(tmp_path: Path) -> Path:
    home = tmp_path / "home"
    home.mkdir()
    return home


@pytest.fixture
def env_home(isolated_home: Path, tmp_path: Path) -> dict[str, str]:
    user_home = tmp_path / "userhome"
    user_home.mkdir()
    env = os.environ.copy()
    env["HOME"] = str(user_home)
    env["AI_WORKLOG_HOME"] = str(isolated_home)
    env.pop("AI_WORKLOG_WORKSPACE", None)
    env.pop("AI_WORKLOG_WORKSPACE_NAME", None)
    env.pop("AI_WORKLOG_RUNTIME", None)
    env.pop("AI_WORKLOG_AI_VAULT_ROOT", None)
    return env


@pytest.fixture
def workspace(tmp_path: Path) -> Path:
    ws = tmp_path / "workspace"
    _reset_workspace(ws)
    return ws


@pytest.fixture
def vault(tmp_path: Path) -> Path:
    return _make_vault(tmp_path)


@pytest.fixture
def markers(isolated_home: Path, workspace: Path, vault: Path) -> dict[str, str]:
    return _path_markers(isolated_home, workspace, vault)


def test_setup_help_usage(env_home: dict[str, str], markers: dict[str, str]) -> None:
    python = _run("python", env_home, "setup")
    groovy = _run("groovy", env_home, "setup")
    _assert_parity_human(python, groovy, markers)
    assert python.returncode == 1
    assert "Usage: ai-worklog setup" in python.stdout


def test_setup_init_dry_run_human(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor"]),
    )
    _assert_parity_human(python, groovy, markers)
    assert python.returncode == 0
    assert "pending actions" in python.stdout
    assert "Re-run with --apply" in python.stdout


def test_setup_init_apply_human(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, default=True),
    )
    _assert_parity_human(python, groovy, markers)
    assert python.returncode == 0


def test_setup_init_dry_run_json(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor"], json_output=True),
    )
    _assert_parity_json(python, groovy, markers)
    payload = _normalize_setup_json(python.stdout, markers)
    assert payload["operation"] == "init"
    assert payload["status"] == "degraded"
    assert payload["pending_actions"] > 0


def test_setup_init_apply_json_and_state(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor", "claude", "antigravity"], apply=True, json_output=True, default=True),
    )
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 0

    _reset_state(isolated_home, workspace)
    _run("python", env_home, *_init_args(workspace, vault, ides=["cursor", "claude", "antigravity"], apply=True, default=True))
    python_state = _collect_filesystem_state(workspace, isolated_home, vault)

    _reset_state(isolated_home, workspace)
    _run("groovy", env_home, *_init_args(workspace, vault, ides=["cursor", "claude", "antigravity"], apply=True, default=True))
    groovy_state = _collect_filesystem_state(workspace, isolated_home, vault)

    assert python_state == groovy_state
    assert python_state["config_version"] == 2
    assert python_state["setup_json_exists"] is True
    assert python_state["manifest_version"] == 1
    assert python_state["manifest_workspace_name"] == "work"
    assert python_state["cursor_is_symlink"] is True
    assert python_state["claude_is_symlink"] is True
    assert python_state["antigravity_is_dir"] is True
    assert python_state["antigravity_is_symlink"] is False
    assert python_state["cursor_target"] == python_state["vault_target"]
    assert python_state["claude_target"] == python_state["vault_target"]


def test_setup_show_human_and_json(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor", "claude"])

    python_human = _run("python", env_home, *_workspace_args(workspace, "setup", "show"))
    groovy_human = _run("groovy", env_home, *_workspace_args(workspace, "setup", "show"))
    _assert_parity_human(python_human, groovy_human, markers)

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor", "claude"])
    python_json = _run("python", env_home, *_workspace_args(workspace, "setup", "show", json_output=True))
    groovy_json = _run("groovy", env_home, *_workspace_args(workspace, "setup", "show", json_output=True))
    _assert_parity_json(python_json, groovy_json, markers)


def test_setup_check_json(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])

    python = _run("python", env_home, *_workspace_args(workspace, "setup", "check", json_output=True))
    groovy = _run("groovy", env_home, *_workspace_args(workspace, "setup", "check", json_output=True))
    _assert_parity_json(python, groovy, markers)
    payload = _normalize_setup_json(python.stdout, markers)
    assert payload["operation"] == "check"
    assert payload["workspace"]["registered"] is True


def test_setup_repair_dry_run_and_idempotent_apply(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])

    python_dry = _run("python", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True))
    groovy_dry = _run("groovy", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True))
    _assert_parity_json(python_dry, groovy_dry, markers)

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])
    first = _run("python", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    second = _run("python", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    assert first.returncode == 0
    assert second.returncode == 0
    first_payload = _normalize_setup_json(first.stdout, markers)
    second_payload = _normalize_setup_json(second.stdout, markers)
    assert first_payload["status"] == "ready"
    assert first_payload["pending_actions"] == 0
    assert second_payload["pending_actions"] == 0

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])
    groovy_first = _run("groovy", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    groovy_second = _run("groovy", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    _assert_parity_json(groovy_first, groovy_second, markers)


def test_setup_revert_selective_and_full(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor", "claude"])

    python = _run("python", env_home, *_workspace_args(workspace, "setup", "revert", "--ide", "cursor", json_output=True))
    groovy = _run("groovy", env_home, *_workspace_args(workspace, "setup", "revert", "--ide", "cursor", json_output=True))
    _assert_parity_json(python, groovy, markers)

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor", "claude"])
    _run("python", env_home, *_workspace_args(workspace, "setup", "revert", "--ide", "cursor", apply=True))
    assert not (workspace / ".cursor/skills/developer-protocol").exists()
    assert (workspace / ".claude/skills/developer-protocol").exists()
    config = json.loads((isolated_home / "config.json").read_text(encoding="utf-8"))
    assert config["workspaces"]["work"]["ides"] == ["claude"]

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor", "claude"])
    _run("python", env_home, *_workspace_args(workspace, "setup", "revert", apply=True))
    assert not (workspace / ".cursor/skills/developer-protocol").exists()
    assert not (workspace / ".claude/skills/developer-protocol").exists()
    config = json.loads((isolated_home / "config.json").read_text(encoding="utf-8"))
    assert config["workspaces"]["work"]["ides"] == []


def test_setup_init_merges_existing_ides(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    config_seed = {
        "version": 2,
        "runtime": "python",
        "ai_vault_root": str(vault.resolve()),
        "workspaces": {
            "work": {
                "path": str(workspace.resolve()),
                "ides": ["claude"],
            }
        },
    }
    _reset_state(isolated_home, workspace)
    (isolated_home / "config.json").write_text(json.dumps(config_seed), encoding="utf-8")

    python = _run(
        "python",
        env_home,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, json_output=True),
    )
    _reset_state(isolated_home, workspace)
    (isolated_home / "config.json").write_text(json.dumps(config_seed), encoding="utf-8")
    groovy = _run(
        "groovy",
        env_home,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, json_output=True),
    )
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 0
    saved = json.loads((isolated_home / "config.json").read_text(encoding="utf-8"))
    assert saved["workspaces"]["work"]["ides"] == ["claude", "cursor"]


def test_setup_repair_after_missing_symlink(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])
    link = workspace / ".cursor/skills/developer-protocol"
    if link.is_symlink():
        link.unlink()
    elif link.exists():
        shutil.rmtree(link)

    python = _run("python", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True))
    groovy = _run("groovy", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True))
    _assert_parity_json(python, groovy, markers)
    payload = _normalize_setup_json(python.stdout, markers)
    assert payload["status"] == "degraded"
    assert payload["pending_actions"] > 0

    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])
    link = workspace / ".cursor/skills/developer-protocol"
    if link.is_symlink():
        link.unlink()
    elif link.exists():
        shutil.rmtree(link)
    python_apply = _run("python", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    _reset_state(isolated_home, workspace)
    _seed_init(env_home, workspace, vault, ides=["cursor"])
    link = workspace / ".cursor/skills/developer-protocol"
    if link.is_symlink():
        link.unlink()
    elif link.exists():
        shutil.rmtree(link)
    groovy_apply = _run("groovy", env_home, *_workspace_args(workspace, "setup", "repair", json_output=True, apply=True))
    _assert_parity_json(python_apply, groovy_apply, markers)
    assert python_apply.returncode == 0
    assert (workspace / ".cursor/skills/developer-protocol").is_symlink()
    apply_payload = _normalize_setup_json(python_apply.stdout, markers)
    assert apply_payload["pending_actions"] == 0
    assert apply_payload["applied_actions"] > 0


def test_setup_init_conflict_blocked(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    foreign = workspace / ".cursor/skills/devops-daily-protocol"
    foreign.parent.mkdir(parents=True)
    foreign.mkdir()

    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, json_output=True),
    )
    _reset_state(isolated_home, workspace)
    foreign.parent.mkdir(parents=True)
    foreign.mkdir()
    python = _run("python", env_home, *_init_args(workspace, vault, ides=["cursor"], apply=True, json_output=True))
    groovy = _run("groovy", env_home, *_init_args(workspace, vault, ides=["cursor"], apply=True, json_output=True))
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 3
    payload = _normalize_setup_json(python.stdout, markers)
    assert payload["status"] == "blocked"
    assert payload["conflicts"]


def test_setup_auto_detection_from_marker(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    env = _sanitized_path_env(env_home)

    python, groovy = _run_parity(
        env,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["auto"], json_output=True),
        prepare=lambda ws: (ws / ".cursor").mkdir(),
    )
    _assert_parity_json(python, groovy, markers)
    payload = _normalize_setup_json(python.stdout, markers)
    assert any(item["id"] == "cursor" for item in payload["ides"])


def test_setup_auto_invalid_combination(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    env = _sanitized_path_env(env_home)
    python, groovy = _run_parity(
        env,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["auto", "cursor"], json_output=True),
        prepare=lambda ws: (ws / ".cursor").mkdir(),
    )
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 1


def test_setup_auto_missing_detection(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    env = _sanitized_path_env(env_home)
    python, groovy = _run_parity(
        env,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["auto"], json_output=True),
    )
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 1


def test_setup_v1_global_config_migration_on_apply(
    env_home: dict[str, str],
    isolated_home: Path,
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
) -> None:
    _reset_state(isolated_home, workspace)
    (isolated_home / "config.json").write_text(
        json.dumps(
            {
                "version": 1,
                "runtime": "groovy",
                "workspaces": {"work": str(workspace)},
            }
        ),
        encoding="utf-8",
    )

    python, groovy = _run_parity(
        env_home,
        markers,
        isolated_home,
        workspace,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, runtime="python", json_output=True),
    )
    _reset_state(isolated_home, workspace)
    (isolated_home / "config.json").write_text(
        json.dumps(
            {
                "version": 1,
                "runtime": "groovy",
                "workspaces": {"work": str(workspace)},
            }
        ),
        encoding="utf-8",
    )
    python = _run(
        "python",
        env_home,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, runtime="python", json_output=True),
    )
    _reset_state(isolated_home, workspace)
    (isolated_home / "config.json").write_text(
        json.dumps(
            {
                "version": 1,
                "runtime": "groovy",
                "workspaces": {"work": str(workspace)},
            }
        ),
        encoding="utf-8",
    )
    groovy = _run(
        "groovy",
        env_home,
        *_init_args(workspace, vault, ides=["cursor"], apply=True, runtime="python", json_output=True),
    )
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == 0
    saved = json.loads((isolated_home / "config.json").read_text(encoding="utf-8"))
    assert saved["version"] == 2
    assert saved["runtime"] == "python"
    assert saved["ai_vault_root"] == str(vault.resolve())


@pytest.mark.parametrize(
    "arguments",
    [
        ("setup", "init", "../bad", "__WORKSPACE__", "--ai-vault", "__VAULT__", "--json"),
        ("--workspace", "__MISSING__", "setup", "check", "--json"),
        ("--workspace", "__MISSING__", "setup", "repair", "--json"),
        ("--workspace", "__MISSING__", "setup", "revert", "--json"),
    ],
)
def test_setup_invalid_input_json(
    env_home: dict[str, str],
    workspace: Path,
    vault: Path,
    markers: dict[str, str],
    arguments: tuple[str, ...],
    tmp_path: Path,
) -> None:
    missing = tmp_path / "missing"
    resolved = []
    for item in arguments:
        if item == "__WORKSPACE__":
            resolved.append(str(workspace))
        elif item == "__VAULT__":
            resolved.append(str(vault))
        elif item == "__MISSING__":
            resolved.append(str(missing))
        else:
            resolved.append(item)
    python = _run("python", env_home, *resolved)
    groovy = _run("groovy", env_home, *resolved)
    _assert_parity_json(python, groovy, markers)
    assert python.returncode == groovy.returncode == 1


def test_setup_version_parity(env_home: dict[str, str]) -> None:
    python = _run("python", env_home, "--version")
    groovy = _run("groovy", env_home, "--version")
    assert python.returncode == groovy.returncode == 0
    assert python.stderr == groovy.stderr == ""
    assert python.stdout.startswith("ai-worklog ")
    assert groovy.stdout.startswith("ai-worklog ")
