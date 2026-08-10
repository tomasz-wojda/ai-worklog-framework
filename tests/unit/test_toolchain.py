"""
Unit tests for ai_worklog_framework.toolchain.resolver module.
"""

from pathlib import Path

import pytest

from ai_worklog_framework.toolchain.resolver import (
    GroovyRuntime,
    JavaRuntime,
    resolve_tool_environment,
    GROOVY_JAVA_COMPAT,
)


class TestResolveToolEnvironment:
    def test_jira_cli_java17_groovy3(self):
        java_rts = [JavaRuntime(major=17, home=Path("/java17"))]
        groovy_rts = [GroovyRuntime(major=3, executable=Path("/groovy3"), version_string="3.0.21")]
        env = resolve_tool_environment("jira-cli", {}, java_rts, groovy_rts)
        assert env.ready is True
        assert env.java_home == Path("/java17")
        assert env.groovy_executable == Path("/groovy3")

    def test_groovy3_rejects_java25(self):
        java_rts = [
            JavaRuntime(major=17, home=Path("/java17")),
            JavaRuntime(major=25, home=Path("/java25")),
        ]
        groovy_rts = [GroovyRuntime(major=3, executable=Path("/groovy3"), version_string="3.0.21")]
        env = resolve_tool_environment("jira-cli", {"tools": {"jira-cli": {"java": 25, "groovy": 3}}}, java_rts, groovy_rts)
        assert env.ready is False
        assert "Incompatible" in env.message

    def test_gradle_java25_no_groovy(self):
        java_rts = [
            JavaRuntime(major=17, home=Path("/java17")),
            JavaRuntime(major=25, home=Path("/java25")),
        ]
        env = resolve_tool_environment("gradle-java25", {}, java_rts, [])
        assert env.ready is True
        assert env.java_home == Path("/java25")
        assert env.groovy_executable is None

    def test_missing_java_blocked(self):
        env = resolve_tool_environment("jira-cli", {}, [], [])
        assert env.ready is False
        assert "Java 17 not found" in env.message

    def test_config_override_java25_for_gradle(self):
        java_rts = [JavaRuntime(major=25, home=Path("/java25"))]
        cfg = {"tools": {"gradle-java25": {"java": 25}}}
        env = resolve_tool_environment("gradle-java25", cfg, java_rts, [])
        assert env.ready is True


class TestGroovyJavaCompat:
    def test_groovy5_supports_java25(self):
        low, high = GROOVY_JAVA_COMPAT[5]
        assert low <= 25 <= high

    def test_groovy3_does_not_support_java25(self):
        low, high = GROOVY_JAVA_COMPAT[3]
        assert not (low <= 25 <= high)
