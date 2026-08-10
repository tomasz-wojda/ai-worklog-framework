import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple
from urllib.parse import quote

from ai_worklog_framework.adapters.http import basic_headers, http_get_json
from ai_worklog_framework.adapters.process import load_properties, run_process
from ai_worklog_framework.catalog.loader import load_catalog
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import SAFE_COMPONENT, WorkspacePaths
from ai_worklog_framework.reconciliation.models import Observation
from ai_worklog_framework.redaction import redact_dict
from ai_worklog_framework.result import Result, ResultSet, Status
from ai_worklog_framework.shared import load_shared


DEFAULT_OPERATOR_RULES: Dict[str, Any] = {
    "timeouts": {"http_seconds": 10, "process_seconds": 15},
    "max_builds": 5,
    "credential_domain": "_",
    "required_plugins": [],
    "sensitive_parameter_patterns": [
        "password",
        "secret",
        "token",
        "credential",
        "key",
        "auth",
    ],
    "seed_failure_results": ["FAILURE", "UNSTABLE", "ABORTED"],
    "api_trees": {
        "health": "mode,quietingDown,numExecutors,nodeDescription",
        "job": (
            "name,url,color,buildable,inQueue,"
            "lastBuild[number,result,timestamp,duration,building],"
            "builds[number,result,timestamp,duration,building]"
        ),
        "job_parameters": (
            "name,url,color,buildable,inQueue,"
            "actions[parameterDefinitions[name]],"
            "lastBuild[number,result,timestamp,duration,building,"
            "actions[parameters[name,value]]],"
            "builds[number,result,timestamp,duration,building]"
        ),
        "plugins": "plugins[shortName,version,active,enabled]",
        "credentials": "credentials[id,typeName,displayName,description]",
    },
}

_SENSITIVE_PARAMETER = re.compile(
    "|".join(DEFAULT_OPERATOR_RULES["sensitive_parameter_patterns"]),
    re.IGNORECASE,
)


def load_operator_rules() -> Dict[str, Any]:
    loaded = load_shared("jenkins-operator-rules.json", {})
    if not loaded:
        return dict(DEFAULT_OPERATOR_RULES)
    merged = dict(DEFAULT_OPERATOR_RULES)
    for key, value in loaded.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            nested = dict(merged[key])
            nested.update(value)
            merged[key] = nested
        else:
            merged[key] = value
    global _SENSITIVE_PARAMETER
    patterns = merged.get("sensitive_parameter_patterns") or []
    if patterns:
        _SENSITIVE_PARAMETER = re.compile("|".join(patterns), re.IGNORECASE)
    return merged


def jenkins_adapter_config(paths: WorkspacePaths) -> Dict[str, Any]:
    config = load_config(paths.root).adapters.get("jenkins", {})
    rules = load_operator_rules()
    timeouts = rules.get("timeouts", {})
    return {
        "ai_vault_root": config.get("ai_vault_root"),
        "syntax_check_script": config.get("syntax_check_script"),
        "http_timeout_seconds": int(config.get("timeout_seconds") or timeouts.get("http_seconds", 10)),
        "process_timeout_seconds": int(timeouts.get("process_seconds", 15)),
        "max_builds": int(config.get("max_builds") or rules["max_builds"]),
        "required_plugins": list(config.get("required_plugins") or rules.get("required_plugins") or []),
        "credential_domain": str(config.get("credential_domain") or rules["credential_domain"]),
    }


def _validate_controller_id(controller: str) -> str:
    if not SAFE_COMPONENT.fullmatch(controller):
        raise ValueError(f"Invalid controller: {controller}")
    return controller


def _validate_job_name(job_name: str) -> str:
    if not job_name or job_name in (".", ".."):
        raise ValueError(f"Invalid job: {job_name}")
    job_part = re.compile(r"^[A-Za-z0-9_~][A-Za-z0-9._~-]{0,127}$")
    parts = job_name.split("/")
    for part in parts:
        if not part or part in (".", "..") or not job_part.fullmatch(part):
            raise ValueError(f"Invalid job: {job_name}")
    return job_name


def _validate_domain(domain: str) -> str:
    if not domain or domain in (".", ".."):
        raise ValueError(f"Invalid domain: {domain}")
    if domain == "_":
        return domain
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", domain):
        raise ValueError(f"Invalid domain: {domain}")
    return domain


def _validate_syntax_file(path: str) -> Path:
    candidate = Path(path).expanduser()
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        raise ValueError(f"File not found: {path}") from exc
    if not resolved.is_file():
        raise ValueError(f"File not found: {path}")
    return resolved


def encode_job_path(job_name: str) -> str:
    _validate_job_name(job_name)
    parts = job_name.split("/")
    encoded = [quote(part, safe="") for part in parts]
    return "job/" + "/job/".join(encoded)


def _utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _load_controller_credentials(
    paths: WorkspacePaths,
) -> Dict[str, Dict[str, str]]:
    props_file = paths.service_dir("jenkins") / "jenkins.properties"
    props = load_properties(props_file)
    controllers: Dict[str, Dict[str, str]] = {}
    for key, value in props.items():
        parts = key.split(".")
        if len(parts) < 2:
            continue
        controller_id = parts[0]
        field = ".".join(parts[1:])
        controllers.setdefault(controller_id, {})[field] = value
    return controllers


def controller_public_info(
    controllers: Dict[str, Dict[str, str]],
) -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []
    for controller_id in sorted(controllers):
        info = controllers[controller_id]
        items.append({
            "id": controller_id,
            "url": info.get("url", ""),
            "has_user": bool(info.get("user")),
            "has_token": bool(info.get("token")),
        })
    return items


def _controller_auth(
    controllers: Dict[str, Dict[str, str]],
    controller: str,
) -> Tuple[str, str, str]:
    info = controllers.get(controller, {})
    url = info.get("url", "").rstrip("/")
    user = info.get("user", "")
    token = info.get("token", "")
    return url, user, token


def _jenkins_get(
    paths: WorkspacePaths,
    controller: str,
    path: str,
    timeout: int = 10,
) -> Tuple[int, Any]:
    controllers = _load_controller_credentials(paths)
    url, user, token = _controller_auth(controllers, controller)
    if not url or not user or not token:
        return 0, None
    api_url = f"{url}{path}"
    return http_get_json(api_url, headers=basic_headers(user, token), timeout=timeout)


def _access_blocked(status_code: int) -> bool:
    return status_code in (401, 403)


def _project_build(item: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(item, dict):
        return {}
    return {
        "number": item.get("number"),
        "result": item.get("result"),
        "timestamp": item.get("timestamp"),
        "duration": item.get("duration"),
        "building": item.get("building"),
    }


def _parameter_name(name: str) -> str:
    if _SENSITIVE_PARAMETER.search(name):
        return "***REDACTED***"
    return name


def _extract_parameters(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    definitions: List[str] = []
    for action in payload.get("actions") or []:
        if not isinstance(action, dict):
            continue
        for definition in action.get("parameterDefinitions") or []:
            if isinstance(definition, dict) and definition.get("name"):
                definitions.append(str(definition["name"]))
    values_present: Dict[str, bool] = {name: False for name in definitions}
    last_build = payload.get("lastBuild") if isinstance(payload.get("lastBuild"), dict) else {}
    for action in last_build.get("actions") or []:
        if not isinstance(action, dict):
            continue
        for parameter in action.get("parameters") or []:
            if not isinstance(parameter, dict):
                continue
            name = parameter.get("name")
            if not name:
                continue
            if name not in values_present:
                values_present[name] = False
            value = parameter.get("value")
            values_present[name] = value is not None and value != ""
    return [
        {
            "name": _parameter_name(name),
            "value_present": values_present[name],
        }
        for name in sorted(values_present)
    ]


def list_controllers(paths: WorkspacePaths) -> ResultSet:
    results = ResultSet()
    controllers = _load_controller_credentials(paths)
    if not controllers:
        results.add(Result(
            status=Status.BLOCKED,
            source="jenkins",
            message="No jenkins.properties found or no controllers configured",
        ))
        return results
    for item in controller_public_info(controllers):
        results.add(Result(
            status=Status.READY,
            source=f"jenkins:{item['id']}",
            message=f"URL: {item['url'] or 'unknown'}",
            detail=item,
        ))
    return results


def query_job(
    paths: WorkspacePaths,
    controller: str,
    job_name: str,
    timeout: int = 10,
) -> Result:
    controllers = _load_controller_credentials(paths)
    if controller not in controllers:
        return Result(
            status=Status.ERROR,
            source=f"jenkins:{controller}",
            message=f"Controller '{controller}' not found in properties",
        )
    status_code, payload = _jenkins_get(
        paths,
        controller,
        f"/{encode_job_path(job_name)}/api/json?tree=name,color,lastBuild[number,result,timestamp]",
        timeout=timeout,
    )
    if status_code == 0 or payload is None:
        return Result(
            status=Status.ERROR,
            source=f"jenkins:{controller}/{job_name}",
            message="Jenkins query failed",
        )
    if status_code >= 400:
        return Result(
            status=Status.DEGRADED,
            source=f"jenkins:{controller}/{job_name}",
            message=f"Jenkins returned HTTP {status_code}",
        )
    last_build = payload.get("lastBuild") or {}
    build_num = last_build.get("number", "?")
    build_result = last_build.get("result", "unknown")
    return Result(
        status=Status.READY,
        source=f"jenkins:{controller}/{job_name}",
        message=f"Last build: #{build_num} ({build_result})",
        detail=payload,
    )


def operator_controllers(paths: WorkspacePaths) -> Dict[str, Any]:
    fetched_at = _utc_now()
    controllers = _load_controller_credentials(paths)
    if not controllers:
        return {
            "operation": "controllers",
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "No jenkins.properties found or no controllers configured",
            "items": [],
        }
    return {
        "operation": "controllers",
        "fetched_at": fetched_at,
        "status": Status.READY,
        "items": controller_public_info(controllers),
    }


def operator_health(
    paths: WorkspacePaths,
    controller: str,
    timeout: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    _validate_controller_id(controller)
    controllers = _load_controller_credentials(paths)
    if controller not in controllers:
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Controller '{controller}' not found",
            "items": [],
        }
    url, user, token = _controller_auth(controllers, controller)
    if not url or not user or not token:
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Controller credentials unavailable",
            "items": [{"id": controller, "url": url, "has_user": bool(user), "has_token": bool(token)}],
        }
    rules = load_operator_rules()
    tree = rules["api_trees"]["health"]
    status_code, payload = _jenkins_get(
        paths,
        controller,
        f"/api/json?tree={tree}",
        timeout=timeout,
    )
    if _access_blocked(status_code):
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    if status_code == 0 or payload is None:
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Jenkins query failed",
            "items": [],
        }
    if not isinstance(payload, dict):
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Malformed Jenkins response",
            "items": [],
        }
    if status_code >= 400:
        return {
            "operation": "health",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.DEGRADED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    quieting = bool(payload.get("quietingDown"))
    item = {
        "mode": payload.get("mode"),
        "quieting_down": quieting,
        "num_executors": payload.get("numExecutors"),
        "node_description": payload.get("nodeDescription"),
    }
    status = Status.DEGRADED if quieting else Status.READY
    message = "Controller is quieting down" if quieting else "Controller is reachable"
    return {
        "operation": "health",
        "controller": controller,
        "fetched_at": fetched_at,
        "status": status,
        "message": message,
        "items": [item],
    }


def operator_job(
    paths: WorkspacePaths,
    controller: str,
    job_name: str,
    builds: int,
    include_parameters: bool,
    timeout: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    _validate_controller_id(controller)
    _validate_job_name(job_name)
    controllers = _load_controller_credentials(paths)
    if controller not in controllers:
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Controller '{controller}' not found",
            "items": [],
        }
    url, user, token = _controller_auth(controllers, controller)
    if not url or not user or not token:
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Controller credentials unavailable",
            "items": [],
        }
    rules = load_operator_rules()
    tree = rules["api_trees"]["job_parameters" if include_parameters else "job"]
    status_code, payload = _jenkins_get(
        paths,
        controller,
        f"/{encode_job_path(job_name)}/api/json?tree={tree}",
        timeout=timeout,
    )
    if _access_blocked(status_code):
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    if status_code == 404:
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Job '{job_name}' not found",
            "items": [],
        }
    if status_code == 0 or payload is None:
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Jenkins query failed",
            "items": [],
        }
    if not isinstance(payload, dict):
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Malformed Jenkins response",
            "items": [],
        }
    if status_code >= 400:
        return {
            "operation": "job",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.DEGRADED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    recent = [_project_build(item) for item in (payload.get("builds") or [])[:builds]]
    item = {
        "job": job_name,
        "name": payload.get("name"),
        "url": payload.get("url"),
        "color": payload.get("color"),
        "buildable": payload.get("buildable"),
        "in_queue": payload.get("inQueue"),
        "last_build": _project_build(payload.get("lastBuild")),
        "recent_builds": recent,
    }
    if include_parameters:
        item["parameters"] = _extract_parameters(payload)
    return {
        "operation": "job",
        "controller": controller,
        "fetched_at": fetched_at,
        "status": Status.READY,
        "message": "Jenkins job fetched",
        "items": [item],
    }


def operator_plugins(
    paths: WorkspacePaths,
    controller: str,
    required: Sequence[str],
    timeout: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    _validate_controller_id(controller)
    controllers = _load_controller_credentials(paths)
    if controller not in controllers:
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Controller '{controller}' not found",
            "items": [],
        }
    url, user, token = _controller_auth(controllers, controller)
    if not url or not user or not token:
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Controller credentials unavailable",
            "items": [],
        }
    rules = load_operator_rules()
    tree = rules["api_trees"]["plugins"]
    status_code, payload = _jenkins_get(
        paths,
        controller,
        f"/pluginManager/api/json?depth=1&tree={tree}",
        timeout=timeout,
    )
    if _access_blocked(status_code):
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    if status_code == 0 or payload is None:
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Jenkins query failed",
            "items": [],
        }
    if not isinstance(payload, dict):
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Malformed Jenkins response",
            "items": [],
        }
    if status_code >= 400:
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.DEGRADED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    by_name = {
        str(item.get("shortName")): item
        for item in (payload.get("plugins") or [])
        if isinstance(item, dict) and item.get("shortName")
    }
    items = [
        {
            "short_name": name,
            "version": (by_name[name].get("version")),
            "active": by_name[name].get("active"),
            "enabled": by_name[name].get("enabled"),
        }
        for name in sorted(by_name)
    ]
    missing = []
    inactive = []
    for plugin in required:
        entry = by_name.get(plugin)
        if entry is None:
            missing.append(plugin)
        elif not entry.get("active"):
            inactive.append(plugin)
    if missing or inactive:
        parts = []
        if missing:
            parts.append(f"missing: {', '.join(sorted(missing))}")
        if inactive:
            parts.append(f"inactive: {', '.join(sorted(inactive))}")
        return {
            "operation": "plugins",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "; ".join(parts),
            "items": items,
            "required": {
                "requested": sorted(required),
                "missing": sorted(missing),
                "inactive": sorted(inactive),
            },
        }
    return {
        "operation": "plugins",
        "controller": controller,
        "fetched_at": fetched_at,
        "status": Status.READY,
        "message": "Plugins fetched",
        "items": items,
    }


def operator_credentials(
    paths: WorkspacePaths,
    controller: str,
    domain: str,
    timeout: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    _validate_controller_id(controller)
    _validate_domain(domain)
    controllers = _load_controller_credentials(paths)
    if controller not in controllers:
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Controller '{controller}' not found",
            "items": [],
        }
    url, user, token = _controller_auth(controllers, controller)
    if not url or not user or not token:
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Controller credentials unavailable",
            "items": [],
        }
    rules = load_operator_rules()
    tree = rules["api_trees"]["credentials"]
    encoded_domain = quote(domain, safe="")
    status_code, payload = _jenkins_get(
        paths,
        controller,
        f"/credentials/store/system/domain/{encoded_domain}/api/json?tree={tree}",
        timeout=timeout,
    )
    if _access_blocked(status_code):
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    if status_code == 404:
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": f"Domain '{domain}' not found",
            "items": [],
        }
    if status_code == 0 or payload is None:
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Jenkins query failed",
            "items": [],
        }
    if not isinstance(payload, dict):
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Malformed Jenkins response",
            "items": [],
        }
    if status_code >= 400:
        return {
            "operation": "credentials",
            "controller": controller,
            "fetched_at": fetched_at,
            "status": Status.DEGRADED,
            "message": f"Jenkins returned HTTP {status_code}",
            "items": [],
        }
    items = []
    for entry in payload.get("credentials") or []:
        if not isinstance(entry, dict):
            continue
        items.append({
            "id": entry.get("id"),
            "type_name": entry.get("typeName"),
            "display_name": entry.get("displayName"),
            "description": entry.get("description"),
        })
    items.sort(key=lambda item: (str(item.get("id") or ""), str(item.get("display_name") or "")))
    return {
        "operation": "credentials",
        "controller": controller,
        "fetched_at": fetched_at,
        "status": Status.READY,
        "message": "Credential metadata fetched",
        "items": items,
        "domain": domain,
    }


def operator_seed(
    paths: WorkspacePaths,
    controller: str,
    job_name: str,
    timeout: int,
    max_builds: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    job_report = operator_job(
        paths,
        controller,
        job_name,
        builds=max_builds,
        include_parameters=False,
        timeout=timeout,
    )
    rules = load_operator_rules()
    failure_results = {value.upper() for value in rules.get("seed_failure_results", [])}
    items = []
    for item in job_report.get("items") or []:
        last_build = item.get("last_build") or {}
        recent = item.get("recent_builds") or []
        recent_failure = any(
            (build.get("result") or "").upper() in failure_results
            for build in recent
        )
        seed_item = {
            "job": item.get("job"),
            "available": True,
            "buildable": item.get("buildable"),
            "in_queue": item.get("in_queue"),
            "last_build": last_build,
            "recent_failure": recent_failure,
        }
        items.append(seed_item)
    status = job_report["status"]
    message = job_report.get("message", "")
    if items and items[0].get("recent_failure"):
        status = Status.DEGRADED
        message = "Seed job has recent failures"
    return {
        "operation": "seed",
        "controller": controller,
        "fetched_at": fetched_at,
        "status": status,
        "message": message,
        "items": items,
    }


def resolve_syntax_check_script(paths: WorkspacePaths) -> Optional[Path]:
    config = jenkins_adapter_config(paths)
    candidates = []
    explicit = config.get("syntax_check_script")
    if explicit:
        candidates.append(Path(str(explicit)).expanduser())
    env_root = os.environ.get("AI_VAULT_ROOT")
    if env_root:
        candidates.append(
            Path(env_root)
            / "skills/jenkins-pipeline-architect/scripts/syntax_check.sh"
        )
    configured_root = config.get("ai_vault_root")
    if configured_root:
        candidates.append(
            Path(str(configured_root)).expanduser()
            / "skills/jenkins-pipeline-architect/scripts/syntax_check.sh"
        )
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved.is_file():
            return resolved
    return None


def operator_syntax_check(
    paths: WorkspacePaths,
    files: Sequence[str],
    timeout: int,
) -> Dict[str, Any]:
    fetched_at = _utc_now()
    if not files:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "No files provided",
            "items": [],
        }
    script = resolve_syntax_check_script(paths)
    if script is None:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Syntax check script unavailable",
            "items": [],
        }
    resolved_files: List[Path] = []
    try:
        for file_path in files:
            resolved_files.append(_validate_syntax_file(file_path))
    except ValueError as exc:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": str(exc),
            "items": [],
        }
    argv = [str(script), *[str(path) for path in resolved_files]]
    code, stdout, stderr = run_process(argv, timeout=timeout)
    item = {
        "script": str(script),
        "files": [str(path) for path in resolved_files],
        "exit_code": code,
        "stdout": stdout.strip(),
        "stderr": stderr.strip(),
    }
    if code == 124:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Syntax check timed out",
            "items": [item],
        }
    if code == 127:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.BLOCKED,
            "message": "Syntax check runtime unavailable",
            "items": [item],
        }
    if code != 0:
        return {
            "operation": "syntax-check",
            "fetched_at": fetched_at,
            "status": Status.ERROR,
            "message": "Syntax check failed",
            "items": [item],
        }
    return {
        "operation": "syntax-check",
        "fetched_at": fetched_at,
        "status": Status.READY,
        "message": "Syntax check passed",
        "items": [item],
    }


def report_to_json(report: Dict[str, Any]) -> str:
    status = report["status"]
    if isinstance(status, Status):
        status = status.value
    payload: Dict[str, Any] = {
        "operation": report["operation"],
        "fetched_at": report["fetched_at"],
        "status": status,
        "items": report.get("items") or [],
    }
    for key in ("controller", "message", "domain"):
        value = report.get(key)
        if value not in (None, ""):
            payload[key] = value
    required = report.get("required")
    if required:
        payload["required"] = {
            "requested": list(required.get("requested") or []),
            "missing": list(required.get("missing") or []),
            "inactive": list(required.get("inactive") or []),
        }
    redacted = redact_dict(payload)
    for index, item in enumerate(payload.get("items") or []):
        if not isinstance(item, dict):
            continue
        target = redacted["items"][index]
        for safe_key in (
            "has_user",
            "has_token",
            "value_present",
            "active",
            "enabled",
            "buildable",
            "in_queue",
            "building",
            "recent_failure",
            "available",
        ):
            if safe_key in item:
                target[safe_key] = item[safe_key]
    return json.dumps(redacted, indent=2, ensure_ascii=False)


def _job_targets(
    state: Dict[str, Any],
    catalog: Dict[str, Dict[str, Any]],
    default_controller: str = "",
) -> List[Dict[str, str]]:
    targets: List[Dict[str, str]] = []
    seen = set()
    for build in state.get("builds", []):
        controller = build.get("controller", default_controller)
        job = build.get("job", "")
        if controller and job:
            key = f"{controller}:{job}"
            if key not in seen:
                seen.add(key)
                targets.append({"controller": controller, "job": job})
    for service_id in state.get("services", []):
        jenkins_cfg = catalog.get(service_id, {}).get("jenkins", {})
        controller = jenkins_cfg.get("controller", "")
        for job in jenkins_cfg.get("jobs", []):
            name = job.get("name", "")
            if controller and name:
                key = f"{controller}:{name}"
                if key not in seen:
                    seen.add(key)
                    targets.append({"controller": controller, "job": name})
    return sorted(targets, key=lambda item: (item["controller"], item["job"]))


def observe_jenkins(
    paths: WorkspacePaths,
    state: Dict[str, Any],
    timeout: int = 10,
    max_builds: int = 5,
) -> List[Observation]:
    fetched_at = _utc_now()
    controllers = _load_controller_credentials(paths)
    if not controllers:
        return [Observation(
            system="jenkins",
            source="jenkins",
            status=Status.UNKNOWN,
            message="Jenkins credentials unavailable",
            details={"fetched_at": fetched_at},
        )]

    catalog = load_catalog(paths)
    default_controller = next(iter(controllers)) if len(controllers) == 1 else ""
    targets = _job_targets(state, catalog, default_controller)
    if not targets:
        if state.get("builds"):
            return [Observation(
                system="jenkins",
                source="jenkins",
                status=Status.UNKNOWN,
                message="Builds recorded but no resolvable Jenkins jobs",
                details={"fetched_at": fetched_at},
            )]
        return [Observation(
            system="jenkins",
            source="jenkins",
            status=Status.UNKNOWN,
            message="No Jenkins jobs configured",
            details={"fetched_at": fetched_at},
        )]

    observations: List[Observation] = []
    tree = (
        "name,color,lastBuild[number,result,timestamp,duration,building],"
        "builds[number,result,timestamp,duration,building]"
    )
    for target in targets:
        controller = target["controller"]
        job_name = target["job"]
        source = f"jenkins:{controller}/{job_name}"
        if controller not in controllers:
            observations.append(Observation(
                system="jenkins",
                source=source,
                status=Status.DEGRADED,
                message="Controller not configured",
                details={
                    "controller": controller,
                    "job": job_name,
                    "fetched_at": fetched_at,
                },
            ))
            continue
        status_code, payload = _jenkins_get(
            paths,
            controller,
            f"/{encode_job_path(job_name)}/api/json?tree={tree}",
            timeout=timeout,
        )
        if status_code == 0 or not isinstance(payload, dict):
            observations.append(Observation(
                system="jenkins",
                source=source,
                status=Status.ERROR if status_code != 0 else Status.DEGRADED,
                message="Jenkins query failed" if status_code == 0 else "Malformed Jenkins response",
                details={
                    "controller": controller,
                    "job": job_name,
                    "fetched_at": fetched_at,
                },
            ))
            continue
        if status_code >= 400:
            observations.append(Observation(
                system="jenkins",
                source=source,
                status=Status.DEGRADED,
                message=f"Jenkins returned HTTP {status_code}",
                details={
                    "controller": controller,
                    "job": job_name,
                    "fetched_at": fetched_at,
                },
            ))
            continue
        builds = (payload.get("builds") or [])[:max_builds]
        last_build = payload.get("lastBuild") or {}
        observations.append(Observation(
            system="jenkins",
            source=source,
            status=Status.READY,
            message="Jenkins job fetched",
            details={
                "controller": controller,
                "job": job_name,
                "color": payload.get("color"),
                "fetched_at": fetched_at,
                "last_build": _project_build(last_build),
                "recent_builds": [_project_build(item) for item in builds],
            },
        ))
    return observations
