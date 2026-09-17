import pytest

from ai_worklog_framework.adapters.preflight import (
    _check_artifactory,
    _check_jira,
    _check_service_directory,
    _check_service_properties,
)
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.result import ResultSet, Status


@pytest.fixture
def paths(tmp_path):
    (tmp_path / "worklog").mkdir()
    return WorkspacePaths(tmp_path)


def _only(results: ResultSet):
    assert len(results.results) == 1
    return results.results[0]


def _service(paths, name, populated=False):
    directory = paths.root / "integrations" / name
    directory.mkdir(parents=True)
    if populated:
        (directory / "config").write_text("x", encoding="utf-8")
    return directory


def test_missing_directory_is_blocked(paths):
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")

    assert _only(results).status == Status.BLOCKED


def test_empty_directory_is_not_configured(paths):
    _service(paths, "datadog")
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")

    result = _only(results)
    assert result.status == Status.NOT_CONFIGURED
    assert result.message == "Not configured"


def test_populated_directory_is_ready(paths):
    _service(paths, "datadog", populated=True)
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")

    assert _only(results).status == Status.READY


def test_not_configured_does_not_block_overall(paths):
    _service(paths, "datadog")
    _service(paths, "newrelic", populated=True)
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")
    _check_service_directory(results, paths, "newrelic")

    assert results.overall_status == Status.READY
    assert results.filter_actionable() == []


def test_all_not_configured_is_still_ready(paths):
    _service(paths, "datadog")
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")

    assert results.overall_status == Status.READY


def test_empty_directory_with_required_file_is_not_configured(paths):
    _service(paths, "jenkins")
    results = ResultSet()
    _check_service_properties(results, paths, "jenkins", "jenkins.properties")

    assert _only(results).status == Status.NOT_CONFIGURED


def test_populated_directory_missing_required_file_is_degraded(paths):
    _service(paths, "jenkins", populated=True)
    results = ResultSet()
    _check_service_properties(results, paths, "jenkins", "jenkins.properties")

    result = _only(results)
    assert result.status == Status.DEGRADED
    assert result.message == "jenkins.properties missing"


def test_required_file_present_is_ready(paths):
    directory = _service(paths, "jenkins")
    (directory / "jenkins.properties").write_text("a=b", encoding="utf-8")
    results = ResultSet()
    _check_service_properties(results, paths, "jenkins", "jenkins.properties")

    assert _only(results).status == Status.READY


def test_artifactory_missing_directory_is_blocked(paths):
    results = ResultSet()
    _check_artifactory(results, paths, {})

    assert _only(results).status == Status.BLOCKED


def test_artifactory_empty_directory_is_not_configured(paths):
    _service(paths, "artifactory")
    results = ResultSet()
    _check_artifactory(results, paths, {})

    assert _only(results).status == Status.NOT_CONFIGURED


@pytest.mark.parametrize("filename", ["artifactory.properties", "credentials", "creds"])
def test_artifactory_recognizes_credential_files_without_reading(paths, filename):
    directory = _service(paths, "artifactory")
    (directory / filename).write_text("not valid credential syntax", encoding="utf-8")
    results = ResultSet()
    _check_artifactory(results, paths, {})

    result = _only(results)
    assert result.status == Status.READY
    assert result.message == "Credential source present (file)"


def test_artifactory_requires_both_environment_values(paths):
    _service(paths, "artifactory")
    incomplete = ResultSet()
    _check_artifactory(
        incomplete,
        paths,
        {"ARTIFACTORY_URL": "https://example.invalid"},
    )
    assert _only(incomplete).status == Status.NOT_CONFIGURED

    configured = ResultSet()
    _check_artifactory(
        configured,
        paths,
        {
            "ARTIFACTORY_URL": "https://example.invalid",
            "ARTIFACTORY_TOKEN": "secret",
        },
    )
    result = _only(configured)
    assert result.status == Status.READY
    assert result.message == "Credential source present (environment)"


def test_jira_follows_the_same_four_states(paths):
    _service(paths, "jira")
    results = ResultSet()
    _check_jira(results, paths)
    assert _only(results).status == Status.NOT_CONFIGURED

    (paths.root / "integrations/jira/notes").write_text("x", encoding="utf-8")
    results = ResultSet()
    _check_jira(results, paths)
    assert _only(results).status == Status.DEGRADED

    (paths.root / "integrations/jira/jira.properties").write_text("a=b", encoding="utf-8")
    results = ResultSet()
    _check_jira(results, paths)
    assert _only(results).status == Status.READY


def test_summary_labels_not_configured(paths):
    _service(paths, "datadog")
    results = ResultSet()
    _check_service_directory(results, paths, "datadog")

    assert "[NOT CONFIGURED] datadog: Not configured" in results.summary()
