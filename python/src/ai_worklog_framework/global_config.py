import json
import os
import re
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any

if sys.platform == "win32":
    import msvcrt
else:
    import fcntl

from ai_worklog_framework.shared import load_shared

_RULES = load_shared(
    "global-config-rules.json",
    {
        "version": 2,
        "home_environment": "AI_WORKLOG_HOME",
        "config_filename": "config.json",
        "default_runtime": "groovy",
        "supported_runtimes": ["groovy", "python"],
        "workspace_name_pattern": r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$",
        "supported_ides": ["cursor", "claude", "antigravity"],
    },
)
CANONICAL_VERSION = _RULES["version"]
SUPPORTED_READ_VERSIONS = frozenset({1, CANONICAL_VERSION})
CONFIG_FILENAME = _RULES["config_filename"]
DEFAULT_RUNTIME = _RULES["default_runtime"]
SUPPORTED_RUNTIMES = frozenset(_RULES["supported_runtimes"])
SUPPORTED_IDES = frozenset(_RULES["supported_ides"])
WORKSPACE_NAME_PATTERN = re.compile(_RULES["workspace_name_pattern"])
ALLOWED_TOP_LEVEL_KEYS = frozenset(
    {"version", "runtime", "ai_vault_root", "default_workspace", "workspaces"}
)
ALLOWED_WORKSPACE_KEYS = frozenset({"path", "ides"})


def global_home() -> Path:
    override = os.environ.get(_RULES["home_environment"])
    if override:
        return Path(expand_path(override)).resolve()
    return Path.home() / ".ai-worklog"


def config_file_path() -> Path:
    return global_home() / CONFIG_FILENAME


def default_config() -> dict[str, Any]:
    return {
        "version": CANONICAL_VERSION,
        "runtime": DEFAULT_RUNTIME,
        "ai_vault_root": None,
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


def normalize_ides(ides: Any) -> list[str]:
    if not isinstance(ides, list):
        raise ValueError("Invalid ides")
    normalized: list[str] = []
    seen: set[str] = set()
    for value in ides:
        if not isinstance(value, str):
            raise ValueError("Invalid ides")
        if value not in SUPPORTED_IDES:
            raise ValueError(f"Invalid ide: {value}")
        if value not in seen:
            seen.add(value)
            normalized.append(value)
    return sorted(normalized)


def normalize_workspace_entry(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        if not value.strip():
            raise ValueError("Invalid workspace path")
        return {
            "path": str(canonical_workspace_path(value)),
            "ides": [],
        }
    if not isinstance(value, dict):
        raise ValueError("Invalid workspace entry")
    unknown = sorted(set(value.keys()) - ALLOWED_WORKSPACE_KEYS)
    if unknown:
        raise ValueError(f"Unknown workspace keys: {', '.join(unknown)}")
    if "path" not in value:
        raise ValueError("Invalid workspace path")
    path_value = value["path"]
    if not isinstance(path_value, str) or not path_value.strip():
        raise ValueError("Invalid workspace path")
    ides = normalize_ides(value.get("ides", []))
    return {
        "path": str(canonical_workspace_path(path_value)),
        "ides": ides,
    }


def workspace_entry_path(entry: Any) -> str:
    if isinstance(entry, dict):
        path_value = entry.get("path")
        if isinstance(path_value, str) and path_value.strip():
            return path_value
    if isinstance(entry, str) and entry.strip():
        return str(canonical_workspace_path(entry))
    raise ValueError("Invalid workspace path")


def validate_config(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError(f"Malformed global configuration: {config_file_path()}")
    unknown = sorted(set(data.keys()) - ALLOWED_TOP_LEVEL_KEYS)
    if unknown:
        raise ValueError(f"Unknown global configuration keys: {', '.join(unknown)}")
    if "version" not in data:
        raise ValueError("Missing global configuration field: version")
    version = data.get("version")
    if version not in SUPPORTED_READ_VERSIONS:
        raise ValueError(f"Unsupported global configuration version: {version}")
    runtime = data.get("runtime", DEFAULT_RUNTIME)
    if runtime not in SUPPORTED_RUNTIMES:
        raise ValueError(f"Invalid runtime: {runtime}")
    ai_vault_root = data.get("ai_vault_root") if "ai_vault_root" in data else None
    if version == 1 and "ai_vault_root" in data:
        raise ValueError("Unknown global configuration keys: ai_vault_root")
    if version == CANONICAL_VERSION and "ai_vault_root" not in data:
        raise ValueError("Missing global configuration field: ai_vault_root")
    if ai_vault_root is not None:
        if not isinstance(ai_vault_root, str) or not ai_vault_root.strip():
            raise ValueError("Invalid ai_vault_root")
        ai_vault_root = str(canonical_workspace_path(ai_vault_root))
    if "default_workspace" in data and data["default_workspace"] is not None:
        if not isinstance(data["default_workspace"], str):
            raise ValueError("Invalid default_workspace")
        validate_workspace_name(data["default_workspace"])
    if "workspaces" in data and not isinstance(data.get("workspaces"), dict):
        raise ValueError("Invalid workspaces")
    workspaces: dict[str, dict[str, Any]] = {}
    for name, entry_value in sorted((data.get("workspaces") or {}).items()):
        validate_workspace_name(str(name))
        try:
            workspaces[str(name)] = normalize_workspace_entry(entry_value)
        except ValueError as exc:
            message = str(exc)
            if message == "Invalid workspace path":
                raise ValueError(f"Invalid workspace path for {name}") from exc
            raise
    default_workspace = data.get("default_workspace") if "default_workspace" in data else None
    if default_workspace is not None:
        default_workspace = str(default_workspace)
    if default_workspace and default_workspace not in workspaces:
        raise ValueError(f"Unknown default workspace: {default_workspace}")
    return {
        "version": CANONICAL_VERSION,
        "runtime": runtime,
        "ai_vault_root": ai_vault_root,
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
    cloned["workspaces"] = {
        name: {
            "path": entry["path"],
            "ides": list(entry["ides"]),
        }
        for name, entry in (config.get("workspaces") or {}).items()
    }
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
        if sys.platform == "win32":
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
            try:
                yield
            finally:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
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


def workspace_entry(name: str, entry: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    path = entry["path"]
    return {
        "name": name,
        "path": path,
        "ides": list(entry["ides"]),
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
        workspaces = _clone_config(config)["workspaces"]
        existing = workspaces.get(name)
        existing_path = existing["path"] if existing else None
        existing_ides = list(existing["ides"]) if existing else []
        unchanged = existing_path == canonical
        if existing_path is not None and existing_path != canonical:
            raise ValueError(
                f"Workspace {name} is already registered with a different path: {existing_path}"
            )
        workspaces[name] = {"path": canonical, "ides": existing_ides}
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
        workspaces = _clone_config(config)["workspaces"]
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


def set_ai_vault_root(path: str | None) -> dict[str, Any]:
    with global_config_lock():
        config = load_global_config()
        if path is None:
            config["ai_vault_root"] = None
            canonical = None
        else:
            resolved = canonical_workspace_path(path)
            if not resolved.is_dir():
                raise ValueError(f"AI vault root not found: {path}")
            canonical = str(resolved)
            config["ai_vault_root"] = canonical
        save_global_config(config)
    return {
        "operation": "ai_vault_root",
        "status": "ok",
        "ai_vault_root": canonical,
    }


def set_workspace_ides(name: str, ides: list[str]) -> dict[str, Any]:
    validate_workspace_name(name)
    normalized_ides = normalize_ides(ides)
    with global_config_lock():
        config = load_global_config()
        workspaces = _clone_config(config)["workspaces"]
        if name not in workspaces:
            raise ValueError(f"Workspace not registered: {name}")
        entry = workspaces[name]
        entry["ides"] = normalized_ides
        workspaces[name] = entry
        config["workspaces"] = workspaces
        save_global_config(config)
    return {
        "operation": "ides",
        "status": "ok",
        "name": name,
        "ides": normalized_ides,
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
            workspace_entry(str(name), entry, config)
            for name, entry in sorted(config.get("workspaces", {}).items())
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
        "ai_vault_root": config.get("ai_vault_root"),
        "default_workspace": config.get("default_workspace"),
        "workspaces": [
            workspace_entry(str(name), entry, config)
            for name, entry in sorted(config.get("workspaces", {}).items())
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
    path = workspace_entry_path(config["workspaces"][name])
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
