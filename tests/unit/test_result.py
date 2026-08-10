"""
Unit tests for ai_worklog_framework.result module.
"""

from ai_worklog_framework.result import Result, ResultSet, Status


class TestResult:
    def test_ready_is_ok(self):
        r = Result(status=Status.READY, source="test", message="all good")
        assert r.ok is True
        assert r.actionable is False

    def test_blocked_is_actionable(self):
        r = Result(status=Status.BLOCKED, source="aws", message="session expired")
        assert r.ok is False
        assert r.actionable is True

    def test_error_is_actionable(self):
        r = Result(status=Status.ERROR, source="jira", message="401")
        assert r.ok is False
        assert r.actionable is True


class TestResultSet:
    def test_empty_set(self):
        rs = ResultSet()
        assert rs.overall_status == Status.UNKNOWN

    def test_all_ready(self):
        rs = ResultSet()
        rs.add(Result(status=Status.READY, source="a", message="ok"))
        rs.add(Result(status=Status.READY, source="b", message="ok"))
        assert rs.ok is True
        assert rs.overall_status == Status.READY

    def test_worst_wins(self):
        rs = ResultSet()
        rs.add(Result(status=Status.READY, source="a", message="ok"))
        rs.add(Result(status=Status.DEGRADED, source="b", message="slow"))
        rs.add(Result(status=Status.BLOCKED, source="c", message="down"))
        assert rs.overall_status == Status.BLOCKED

    def test_filter_actionable(self):
        rs = ResultSet()
        rs.add(Result(status=Status.READY, source="a", message="ok"))
        rs.add(Result(status=Status.ERROR, source="b", message="bad"))
        actionable = rs.filter_actionable()
        assert len(actionable) == 1
        assert actionable[0].source == "b"

    def test_summary_format(self):
        rs = ResultSet()
        rs.add(Result(status=Status.READY, source="git", message="clean"))
        rs.add(Result(status=Status.BLOCKED, source="aws", message="expired"))
        summary = rs.summary()
        assert "[OK]" in summary
        assert "[BLOCKED]" in summary
        assert "git" in summary
        assert "aws" in summary
