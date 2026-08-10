from typing import Optional

from ai_worklog_framework.adapters import jenkins as jenkins_adapter
from ai_worklog_framework.cli import (
    EXIT_BLOCKED,
    EXIT_SUCCESS,
    EXIT_SYSTEM_ERROR,
    EXIT_USER_ERROR,
)
from ai_worklog_framework.jenkins.models import JenkinsReport, utc_now
from ai_worklog_framework.paths import WorkspacePaths, resolve_workspace
from ai_worklog_framework.redaction import redact_string
from ai_worklog_framework.result import Status

_ALLOWED_REPORT_KEYS = frozenset({
    "operation",
    "fetched_at",
    "status",
    "items",
    "controller",
    "message",
    "domain",
    "required",
    "folder",
    "query",
    "view",
    "job",
    "build_selector",
})
_ALLOWED_REQUIRED_KEYS = frozenset({"requested", "missing", "inactive"})


def _report_from_payload(payload: dict) -> JenkinsReport:
    return JenkinsReport(
        operation=payload["operation"],
        fetched_at=payload["fetched_at"],
        status=payload["status"],
        controller=payload.get("controller"),
        message=payload.get("message", ""),
        items=payload.get("items") or [],
    )


def _exit_code(report: JenkinsReport) -> int:
    if report.status == Status.BLOCKED:
        return EXIT_BLOCKED
    if report.status == Status.ERROR:
        message = report.message.lower()
        if "not found" in message or "invalid" in message or "no files" in message or "missing" in message:
            return EXIT_USER_ERROR
        return EXIT_SYSTEM_ERROR
    if report.status == Status.DEGRADED:
        return EXIT_SUCCESS
    return EXIT_SUCCESS


def _error_payload(operation: str, message: str, controller: Optional[str] = None) -> dict:
    payload = {
        "operation": operation,
        "fetched_at": utc_now(),
        "status": Status.ERROR,
        "message": message,
        "items": [],
    }
    if controller:
        payload["controller"] = controller
    return payload


def _emit_error(operation: str, message: str, json_output: bool, controller: Optional[str] = None) -> None:
    if json_output:
        print(jenkins_adapter.report_to_json(_error_payload(operation, message, controller)))
    else:
        print(message)


def _render_human(report: JenkinsReport, payload: Optional[dict] = None) -> None:
    payload = payload or {}
    print(f"Jenkins {report.operation}")
    if report.controller:
        print(f"  Controller: {report.controller}")
    for key, label in (
        ("folder", "Folder"),
        ("query", "Query"),
        ("view", "View"),
        ("job", "Job"),
        ("build_selector", "Build selector"),
    ):
        if payload.get(key):
            print(f"  {label}: {payload[key]}")
    print(f"  Fetched: {report.fetched_at}")
    print(f"  Status: {report.status.value}")
    if report.message:
        print(f"  Message: {redact_string(report.message)}")
    for item in report.items:
        print(f"  - {redact_string(str(item))}")


def _require(value: Optional[str], label: str) -> str:
    if not value:
        raise ValueError(f"Missing {label}")
    return value


def run(args) -> int:
    json_output = bool(getattr(args, "json", False))
    if not args.jenkins_action:
        message = "Usage: ai-worklog jenkins {controllers|health|job|plugins|credentials|seed|syntax-check|nodes|queue|jobs|artifacts|views|whoami|credential-domains}"
        if json_output:
            print(jenkins_adapter.report_to_json(_error_payload("controllers", message)))
        else:
            print(message)
        return EXIT_USER_ERROR

    workspace = resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    )
    paths = WorkspacePaths(workspace)
    config = jenkins_adapter.jenkins_adapter_config(paths)
    rules = jenkins_adapter.load_operator_rules()
    limits = rules.get("limits", {})
    http_timeout = config["http_timeout_seconds"]
    process_timeout = config["process_timeout_seconds"]
    max_builds = config["max_builds"]
    operation = args.jenkins_action

    try:
        if operation == "controllers":
            payload = jenkins_adapter.operator_controllers(paths)
        elif operation == "health":
            controller = _require(getattr(args, "controller", None), "controller")
            payload = jenkins_adapter.operator_health(paths, controller, http_timeout)
        elif operation == "job":
            controller = _require(getattr(args, "controller", None), "controller")
            job = _require(getattr(args, "job", None), "job")
            builds = args.builds if args.builds is not None else max_builds
            payload = jenkins_adapter.operator_job(
                paths,
                controller,
                job,
                builds=builds,
                include_parameters=bool(args.parameters),
                timeout=http_timeout,
            )
        elif operation == "plugins":
            controller = _require(getattr(args, "controller", None), "controller")
            required = list(args.require or []) + list(config["required_plugins"])
            payload = jenkins_adapter.operator_plugins(
                paths,
                controller,
                required=sorted(set(required)),
                timeout=http_timeout,
            )
        elif operation == "credentials":
            controller = _require(getattr(args, "controller", None), "controller")
            domain = args.domain if args.domain is not None else config["credential_domain"]
            payload = jenkins_adapter.operator_credentials(
                paths,
                controller,
                domain=domain,
                timeout=http_timeout,
            )
        elif operation == "seed":
            controller = _require(getattr(args, "controller", None), "controller")
            job = _require(getattr(args, "job", None), "job")
            payload = jenkins_adapter.operator_seed(
                paths,
                controller,
                job,
                timeout=http_timeout,
                max_builds=max_builds,
            )
        elif operation == "syntax-check":
            files = list(getattr(args, "file", None) or [])
            if not files:
                raise ValueError("Missing file")
            payload = jenkins_adapter.operator_syntax_check(
                paths,
                files,
                timeout=process_timeout,
            )
        elif operation == "nodes":
            controller = _require(getattr(args, "controller", None), "controller")
            payload = jenkins_adapter.operator_nodes(paths, controller, http_timeout)
        elif operation == "queue":
            controller = _require(getattr(args, "controller", None), "controller")
            limit = args.limit if args.limit is not None else int(limits.get("queue_default", 50))
            payload = jenkins_adapter.operator_queue(
                paths,
                controller,
                limit=limit,
                timeout=http_timeout,
            )
        elif operation == "jobs":
            controller = _require(getattr(args, "controller", None), "controller")
            limit = args.limit if args.limit is not None else int(limits.get("jobs_default", 100))
            payload = jenkins_adapter.operator_jobs(
                paths,
                controller,
                folder=getattr(args, "folder", None),
                query=getattr(args, "query", None),
                limit=limit,
                timeout=http_timeout,
            )
        elif operation == "artifacts":
            controller = _require(getattr(args, "controller", None), "controller")
            job = _require(getattr(args, "job", None), "job")
            build_selector = _require(getattr(args, "build_selector", None), "build selector")
            payload = jenkins_adapter.operator_artifacts(
                paths,
                controller,
                job,
                build_selector,
                timeout=http_timeout,
            )
        elif operation == "views":
            controller = _require(getattr(args, "controller", None), "controller")
            payload = jenkins_adapter.operator_views(
                paths,
                controller,
                view_name=getattr(args, "view", None),
                timeout=http_timeout,
            )
        elif operation == "whoami":
            controller = _require(getattr(args, "controller", None), "controller")
            payload = jenkins_adapter.operator_whoami(paths, controller, http_timeout)
        elif operation == "credential-domains":
            controller = _require(getattr(args, "controller", None), "controller")
            payload = jenkins_adapter.operator_credential_domains(paths, controller, http_timeout)
        else:
            message = f"Unknown jenkins action: {operation}"
            _emit_error(operation, message, json_output)
            return EXIT_USER_ERROR
    except ValueError as exc:
        _emit_error(operation, str(exc), json_output, getattr(args, "controller", None))
        return EXIT_USER_ERROR

    report = _report_from_payload(payload)
    if json_output:
        print(jenkins_adapter.report_to_json(payload))
    else:
        _render_human(report, payload)
    return _exit_code(report)


def validate_report_shape(payload: dict) -> None:
    missing = {"operation", "fetched_at", "status", "items"} - set(payload)
    if missing:
        raise AssertionError(f"missing required keys: {sorted(missing)}")
    extra = set(payload) - _ALLOWED_REPORT_KEYS
    if extra:
        raise AssertionError(f"unexpected top-level keys: {sorted(extra)}")
    if not isinstance(payload["status"], str):
        raise AssertionError("status must be a string")
    if payload.get("required") is not None:
        required_extra = set(payload["required"]) - _ALLOWED_REQUIRED_KEYS
        if required_extra:
            raise AssertionError(f"unexpected required keys: {sorted(required_extra)}")
