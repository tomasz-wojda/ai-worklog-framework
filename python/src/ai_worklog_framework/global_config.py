import json
import os
import re
import tempfile
import fcntl
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from ai_worklog_framework.shared import load_shared

_RULES = load_shared(
    "global-config-rules.json",
    {
        "version": 1,
        "home_environment": "AI_WORKLOG_HOME",
        "config_filename": "config.json",
        "default_runtime": "groovy",
        "supported_runtimes": ["groovy", "python"],
        "workspace_name_pattern": r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$",
    },
)
SUPPORTED_VERSION = _RULES["version"]
CONFIG_FILENAME = _RULES["config_filename"]
DEFAULT_RUNTIME = _RULES["default_runtime"]
SUPPORTED_RUNTIMES = frozenset(_RULES["supported_runtimes"])
WORKSPACE_NAME_PATTERN = re.compile(_RULES["workspace_name_pattern"])
ALLOWED_TOP_LEVEL_KEYS = frozenset({"version", "runtime", "default_workspace", "workspaces"})


def global_home() -> Path:
    override = os.environ.get(_RULES["home_environment"])
    if override:
        return Path(expand_path(override)).resolve()
    return Path.home() / ".ai-worklog"


def config_file_path() -> Path:
    return global_home() / CONFIG_FILENAME


def default_config() -> dict[str, Any]:
    return {
        "version": SUPPORTED_VERSION,
        "runtime": DEFAULT_RUNTIME,
        "default_workspace": None,
        "workspaces": {},
    }


def expand_path(path: str) -> str:
    if not path:
        return path
    if path == "~":
        return str(Path.home())
    if path.startswith("~/"):
        return str(Path.home()) + path[1:]
    return path


def validate_workspace_name(name: str) -> str:
    if not WORKSPACE_NAME_PATTERN.fullmatch(name) or name in (".", ".."):
        raise ValueError(f"Invalid workspace name: {name}")
    return name


def canonical_workspace_path(path: str) -> Path:
    return Path(expand_path(path)).resolve()


def validate_config(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError(f"Malformed global configuration: {config_file_path()}")
    unknown = sorted(set(data.keys()) - ALLOWED_TOP_LEVEL_KEYS)
    if unknown:
        raise ValueError(f"Unknown global configuration keys: {', '.join(unknown)}")
    if "version" not in data:
        raise ValueError("Missing global configuration field: version")
    version = data.get("version")
    if version != SUPPORTED_VERSION:
        raise ValueError(f"Unsupported global configuration version: {version}")
    runtime = data.get("runtime", DEFAULT_RUNTIME)
    if runtime not in SUPPORTED_RUNTIMES:
        raise ValueError(f"Invalid runtime: {runtime}")
    if "default_workspace" in data and data["default_workspace"] is not None:
        if not isinstance(data["default_workspace"], str):
            raise ValueError("Invalid default_workspace")
        validate_workspace_name(data["default_workspace"])
    if "workspaces" in data and not isinstance(data.get("workspaces"), dict):
        raise ValueError("Invalid workspaces")
    workspaces: dict[str, str] = {}
    for name, path_value in sorted((data.get("workspaces") or {}).items()):
        validate_workspace_name(str(name))
        if not isinstance(path_value, str) or not path_value.strip():
            raise ValueError(f"Invalid workspace path for {name}")
        workspaces[str(name)] = str(canonical_workspace_path(path_value))
    default_workspace = data.get("default_workspace") if "default_workspace" in data else None
    if default_workspace is not None:
        default_workspace = str(default_workspace)
    if default_workspace and default_workspace not in workspaces:
        raise ValueError(f"Unknown default workspace: {default_workspace}")
    return {
        "version": SUPPORTED_VERSION,
        "runtime": runtime,
        "default_workspace": default_workspace,
        "workspaces": workspaces,
    }


def load_global_config() -> dict[str, Any]:
    path = config_file_path()
    if not path.is_file():
        return _clone_config(default_config())
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except json.JSONDecodeError:
        raise ValueError(f"Malformed global configuration: {path}") from None
    except OSError as exc:
        raise OSError(f"Failed to read global config: {path}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"Malformed global configuration: {path}")
    return _clone_config(validate_config(data))


def _clone_config(config: dict[str, Any]) -> dict[str, Any]:
    cloned = dict(config)
    cloned["workspaces"] = dict(config.get("workspaces", {}))
    return cloned


def _ensure_home_dir() -> Path:
    home = global_home()
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(home, 0o700)
    return home


@contextmanager
def global_config_lock():
    home = _ensure_home_dir()
    lock_path = home / ".config.lock"
    with lock_path.open("a+", encoding="utf-8") as handle:
        os.chmod(lock_path, 0o600)
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def save_global_config(config: dict[str, Any]) -> dict[str, Any]:
    validated = validate_config(config)
    _ensure_home_dir()
    path = config_file_path()
    if path.is_file():
        try:
            with path.open("r", encoding="utf-8") as handle:
                existing = json.load(handle)
            if not isinstance(existing, dict):
                raise ValueError(f"Malformed global configuration: {path}")
            validate_config(existing)
        except json.JSONDecodeError:
            raise ValueError(f"Malformed global configuration: {path}") from None
        except ValueError:
            raise
        except OSError as exc:
            raise OSError(f"Failed to read global config: {path}") from exc
    fd, tmp_path = tempfile.mkstemp(prefix=".config.", suffix=".tmp", dir=global_home())
    tmp = Path(tmp_path)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(validated, handle, indent=4)
            handle.write("\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        os.chmod(path, 0o600)
    except OSError:
        if tmp.exists():
            tmp.unlink()
        raise
    return _clone_config(validated)


def workspace_available(path: str) -> bool:
    return Path(path).is_dir()


def workspace_entry(name: str, path: str, config: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": name,
        "path": path,
        "available": workspace_available(path),
        "default": config.get("default_workspace") == name,
    }


def add_workspace(name: str, path: str, make_default: bool = False) -> dict[str, Any]:
    validate_workspace_name(name)
    resolved = canonical_workspace_path(path)
    if not resolved.is_dir():
        raise ValueError(f"Workspace not found: {path}")
    canonical = str(resolved)
    with global_config_lock():
        config = load_global_config()
        workspaces = dict(config.get("workspaces", {}))
        unchanged = name in workspaces and workspaces[name] == canonical
        if name in workspaces and workspaces[name] != canonical:
            raise ValueError(
                f"Workspace {name} is already registered with a different path: {workspaces[name]}"
            )
        workspaces[name] = canonical
        config["workspaces"] = workspaces
        if make_default:
            config["default_workspace"] = name
        save_global_config(config)
    result = {
        "operation": "add",
        "status": "ok",
        "name": name,
        "path": canonical,
        "default": config.get("default_workspace") == name,
    }
    if unchanged:
        result["unchanged"] = True
    return result


def remove_workspace(name: str) -> dict[str, Any]:
    validate_workspace_name(name)
    with global_config_lock():
        config = load_global_config()
        workspaces = dict(config.get("workspaces", {}))
        if name not in workspaces:
            raise ValueError(f"Workspace not registered: {name}")
        del workspaces[name]
        config["workspaces"] = workspaces
        if config.get("default_workspace") == name:
            config["default_workspace"] = None
        save_global_config(config)
    return {
        "operation": "remove",
        "status": "ok",
        "name": name,
    }


def set_default_workspace(name: str) -> dict[str, Any]:
    validate_workspace_name(name)
    with global_config_lock():
        config = load_global_config()
        if name not in config.get("workspaces", {}):
            raise ValueError(f"Workspace not registered: {name}")
        config["default_workspace"] = name
        save_global_config(config)
    return {
        "operation": "default",
        "status": "ok",
        "name": name,
    }


def show_default_workspace() -> dict[str, Any]:
    config = load_global_config()
    if config.get("default_workspace") is None:
        raise ValueError("No default workspace configured")
    return {
        "operation": "default",
        "status": "ok",
        "name": config.get("default_workspace"),
    }


def list_workspaces() -> dict[str, Any]:
    config = load_global_config()
    return {
        "operation": "list",
        "status": "ok",
        "default_workspace": config.get("default_workspace"),
        "workspaces": [
            workspace_entry(str(name), str(path), config)
            for name, path in sorted(config.get("workspaces", {}).items())
        ],
    }


def show_workspace(name: str) -> dict[str, Any]:
    validate_workspace_name(name)
    config = load_global_config()
    if name not in config.get("workspaces", {}):
        raise ValueError(f"Workspace not registered: {name}")
    entry = workspace_entry(name, config["workspaces"][name], config)
    entry["operation"] = "show"
    entry["status"] = "ok"
    return entry


def show_configuration() -> dict[str, Any]:
    config = load_global_config()
    return {
        "operation": "show",
        "status": "ok",
        "version": config["version"],
        "runtime": config["runtime"],
        "default_workspace": config.get("default_workspace"),
        "workspaces": [
            workspace_entry(str(name), str(path), config)
            for name, path in sorted(config.get("workspaces", {}).items())
        ],
    }


def set_runtime(runtime: str) -> dict[str, Any]:
    if runtime not in SUPPORTED_RUNTIMES:
        raise ValueError(f"Invalid runtime: {runtime}")
    with global_config_lock():
        config = load_global_config()
        config["runtime"] = runtime
        save_global_config(config)
    return {
        "operation": "runtime",
        "status": "ok",
        "runtime": runtime,
    }


def show_runtime() -> dict[str, Any]:
    config = load_global_config()
    return {
        "operation": "runtime",
        "status": "ok",
        "runtime": config["runtime"],
    }


def _resolve_registered_workspace(name: str, source: str) -> dict[str, Any]:
    validate_workspace_name(name)
    config = load_global_config()
    if name not in config.get("workspaces", {}):
        raise ValueError(f"Workspace not registered: {name}")
    path = config["workspaces"][name]
    if not workspace_available(path):
        raise ValueError(f"Registered workspace path is unavailable: {name} -> {path}")
    return {
        "path": canonical_workspace_path(path),
        "source": source,
        "name": name,
    }


def resolve_workspace_selection(
    explicit_path: str | None = None,
    explicit_name: str | None = None,
    environment: dict[str, str] | None = None,
) -> dict[str, Any]:
    env = environment if environment is not None else os.environ
    if explicit_path:
        selected = canonical_workspace_path(explicit_path)
        if not selected.is_dir():
            raise ValueError(f"Workspace not found: {explicit_path}")
        return {
            "path": selected,
            "source": "explicit_path",
            "name": None,
        }
    if explicit_name:
        return _resolve_registered_workspace(explicit_name, "workspace_name")
    env_path = env.get("AI_WORKLOG_WORKSPACE")
    if env_path:
        selected = canonical_workspace_path(env_path)
        if not selected.is_dir():
            raise ValueError(f"Workspace not found: {env_path}")
        return {
            "path": selected,
            "source": "env_path",
            "name": None,
        }
    env_name = env.get("AI_WORKLOG_WORKSPACE_NAME")
    if env_name:
        return _resolve_registered_workspace(env_name, "env_name")
    discovered = _discover_workspace_from_cwd()
    if discovered is not None:
        return {
            "path": discovered,
            "source": "cwd_marker",
            "name": None,
        }
    config = load_global_config()
    if config.get("default_workspace"):
        return _resolve_registered_workspace(config["default_workspace"], "default_workspace")
    raise ValueError(
        "Cannot locate workspace. Use --workspace, -w/--workspace-name, AI_WORKLOG_WORKSPACE, "
        "AI_WORKLOG_WORKSPACE_NAME, run from within a workspace directory, or set a default workspace."
    )


def current_workspace(
    explicit_path: str | None = None,
    explicit_name: str | None = None,
    environment: dict[str, str] | None = None,
) -> dict[str, Any]:
    resolved = resolve_workspace_selection(explicit_path, explicit_name, environment)
    return {
        "operation": "current",
        "status": "ok",
        "path": str(resolved["path"]),
        "source": resolved["source"],
        "name": resolved["name"],
        "available": True,
    }


def print_json(value: dict[str, Any]) -> None:
    print(json.dumps(value, indent=4, ensure_ascii=False))


def _discover_workspace_from_cwd() -> Path | None:
    rules = load_shared(
        "workspace-markers.json",
        {"markers": [".ai-worklog", "worklog", "prompt.log", "jira"], "max_parent_depth": 20},
    )
    current = Path.cwd().resolve()
    depth = int(rules.get("max_parent_depth", 20))
    for _ in range(depth):
        for marker in rules.get("markers", []):
            if (current / marker).exists():
                return current
        parent = current.parent
        if parent == current:
            break
        current = parent
    return None
