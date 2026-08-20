from pathlib import Path

from ai_worklog_framework.result import Status
from ai_worklog_framework.toolchain import resolver
from ai_worklog_framework.toolchain.resolver import (
    GroovyRuntime,
    JavaRuntime,
    PythonRuntime,
    check_toolchain,
)


def test_check_toolchain_reports_active_runtimes_without_named_tools(monkeypatch):
    monkeypatch.setattr(
        resolver,
        "detect_python",
        lambda: PythonRuntime(Path("/python"), "Python 3.14.6"),
    )
    monkeypatch.setattr(
        resolver,
        "detect_java_runtimes",
        lambda: [JavaRuntime(major=26, home=Path("/java"), version_string="openjdk 26")],
    )
    monkeypatch.setattr(
        resolver,
        "detect_groovy_runtimes",
        lambda config: [
            GroovyRuntime(
                major=6,
                executable=Path("/groovy"),
                version_string="6.0.0",
            )
        ],
    )

    results = check_toolchain({"groovy": {"default": "/groovy"}})
    sources = [result.source for result in results.results]

    assert sources == ["python3", "java:26", "groovy:6"]
    assert all(not source.startswith("tool:") for source in sources)
    assert results.overall_status == Status.READY


def test_check_toolchain_marks_missing_optional_runtimes_degraded(monkeypatch):
    monkeypatch.setattr(
        resolver,
        "detect_python",
        lambda: PythonRuntime(Path("/python"), "Python 3.14.6"),
    )
    monkeypatch.setattr(resolver, "detect_java_runtimes", lambda: [])
    monkeypatch.setattr(resolver, "detect_groovy_runtimes", lambda config: [])

    results = check_toolchain({})
    statuses = {result.source: result.status for result in results.results}

    assert statuses == {
        "python3": Status.READY,
        "java": Status.DEGRADED,
        "groovy": Status.DEGRADED,
    }
