import json

from ai_worklog_framework.workspace.planner import apply_plan, plan_init
from ai_worklog_framework.workspace.provenance import (
    load_created,
    provenance_path,
    record_created,
)


def test_record_is_absent_until_something_is_created(tmp_path):
    assert load_created(tmp_path) == set()
    assert not provenance_path(tmp_path).is_file()


def test_apply_records_only_directories_it_created(tmp_path):
    preexisting = tmp_path / "worklog"
    preexisting.mkdir()

    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    created = load_created(tmp_path)

    assert "integrations/jira" in created
    assert "worklog" not in created


def test_record_is_written_with_a_version(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    data = json.loads(provenance_path(tmp_path).read_text(encoding="utf-8"))

    assert data["version"] == 1
    assert data["directories"] == sorted(data["directories"])


def test_record_merges_across_runs(tmp_path):
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)
    first = load_created(tmp_path)

    (tmp_path / "integrations/jira").rmdir()
    apply_plan(plan_init(tmp_path)["actions"], tmp_path)

    assert first <= load_created(tmp_path)


def test_malformed_record_is_ignored(tmp_path):
    path = provenance_path(tmp_path)
    path.parent.mkdir(parents=True)
    path.write_text("{not json", encoding="utf-8")

    assert load_created(tmp_path) == set()


def test_recording_nothing_leaves_no_file(tmp_path):
    (tmp_path / ".ai-worklog").mkdir()

    record_created(tmp_path, [{"kind": "mkdir", "target": tmp_path / "x", "skip": True}])

    assert not provenance_path(tmp_path).is_file()
