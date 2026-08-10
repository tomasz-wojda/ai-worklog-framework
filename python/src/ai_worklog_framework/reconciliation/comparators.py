from typing import Any, Dict, List

from ai_worklog_framework.reconciliation.models import Contradiction, Observation
from ai_worklog_framework.result import Status
from ai_worklog_framework.shared import load_shared


DEFAULT_RULES: Dict[str, Any] = {
    "systems": ["jira", "git", "github", "jenkins", "argocd", "tempo"],
    "timeouts": {"http_seconds": 10, "process_seconds": 15},
    "repositories_root": "repos",
    "jenkins_max_builds": 5,
    "jira": {"complete_categories": ["done"], "active_categories": ["indeterminate", "new"]},
    "jenkins": {"success_results": ["SUCCESS", "success"], "failure_results": ["FAILURE", "failure"]},
    "argocd": {"synced_states": ["synced", "Synced"], "out_of_sync_states": ["OutOfSync", "out_of_sync"]},
    "tempo": {"seconds_tolerance": 0},
    "contradiction_codes": {
        "jira_summary_mismatch": {"severity": "degraded"},
        "jira_complete_impl_incomplete": {"severity": "blocked"},
        "closeout_complete_jira_active": {"severity": "blocked"},
        "repo_missing": {"severity": "blocked"},
        "uncommitted_mismatch": {"severity": "degraded"},
        "unpushed_commits": {"severity": "degraded"},
        "pr_missing_external": {"severity": "blocked"},
        "pr_state_mismatch": {"severity": "degraded"},
        "pr_discovered_not_recorded": {"severity": "degraded"},
        "pr_url_mismatch": {"severity": "degraded"},
        "build_missing": {"severity": "blocked"},
        "build_result_mismatch": {"severity": "blocked"},
        "merged_pr_no_build": {"severity": "degraded"},
        "jenkins_job_unresolved": {"severity": "degraded"},
        "sync_state_mismatch": {"severity": "blocked"},
        "revision_mismatch": {"severity": "blocked"},
        "argocd_app_mismatch": {"severity": "degraded"},
        "deployment_complete_not_synced": {"severity": "blocked"},
        "tempo_logged_zero": {"severity": "blocked"},
        "tempo_seconds_mismatch": {"severity": "degraded"},
        "tempo_unlogged_has_time": {"severity": "degraded"},
    },
}


def load_rules() -> Dict[str, Any]:
    loaded = load_shared("reconciliation-rules.json", {})
    if not loaded:
        return DEFAULT_RULES
    merged = dict(DEFAULT_RULES)
    for key, value in loaded.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            nested = dict(merged[key])
            nested.update(value)
            merged[key] = nested
        else:
            merged[key] = value
    return merged


def _severity(rules: Dict[str, Any], code: str) -> Status:
    configured = rules.get("contradiction_codes", {}).get(code, {}).get("severity", "degraded")
    try:
        return Status(configured)
    except ValueError:
        return Status.DEGRADED


def _add(contradictions: List[Contradiction], item: Contradiction) -> None:
    contradictions.append(item)


def compare_state(
    state: Dict[str, Any],
    observations: List[Observation],
    rules: Dict[str, Any],
) -> List[Contradiction]:
    contradictions: List[Contradiction] = []
    by_system: Dict[str, List[Observation]] = {}
    for observation in observations:
        by_system.setdefault(observation.system, []).append(observation)

    jira = next(
        (item for item in by_system.get("jira", []) if item.status == Status.READY and item.details),
        None,
    )
    if jira:
        stored_summary = state.get("summary", "")
        observed_summary = jira.details.get("summary", "")
        if stored_summary and observed_summary and stored_summary != observed_summary:
            _add(contradictions, Contradiction(
                code="jira_summary_mismatch",
                system="jira",
                severity=_severity(rules, "jira_summary_mismatch"),
                expected=stored_summary,
                observed=observed_summary,
                source="jira",
                message="Stored summary differs from Jira",
            ))
        category = jira.details.get("status_category", "")
        impl_state = state.get("implementation", {}).get("state", "")
        complete_categories = rules.get("jira", {}).get("complete_categories", ["done"])
        if category in complete_categories and impl_state != "complete":
            _add(contradictions, Contradiction(
                code="jira_complete_impl_incomplete",
                system="jira",
                severity=_severity(rules, "jira_complete_impl_incomplete"),
                expected="implementation.state=complete",
                observed=f"implementation.state={impl_state}",
                source="jira",
                message="Jira is complete but implementation is incomplete",
            ))
        closeout = state.get("closeout", {})
        active_categories = rules.get("jira", {}).get("active_categories", ["indeterminate", "new"])
        if closeout.get("implementation_complete") and category in active_categories:
            _add(contradictions, Contradiction(
                code="closeout_complete_jira_active",
                system="jira",
                severity=_severity(rules, "closeout_complete_jira_active"),
                expected="Jira complete",
                observed=jira.details.get("status", ""),
                source="jira",
                message="Closeout marked complete while Jira remains active",
            ))

    stored_uncommitted = bool(state.get("implementation", {}).get("uncommitted"))
    for observation in by_system.get("git", []):
        if not observation.details:
            continue
        if observation.details.get("present") is False:
            _add(contradictions, Contradiction(
                code="repo_missing",
                system="git",
                severity=_severity(rules, "repo_missing"),
                expected="repository present",
                observed="missing",
                source=observation.source,
                message="Recorded repository is not cloned",
            ))
            continue
        dirty = bool(observation.details.get("dirty"))
        if dirty != stored_uncommitted:
            _add(contradictions, Contradiction(
                code="uncommitted_mismatch",
                system="git",
                severity=_severity(rules, "uncommitted_mismatch"),
                expected=str(stored_uncommitted),
                observed=str(dirty),
                source=observation.source,
                message="Uncommitted flag disagrees with working tree",
            ))
        ahead = int(observation.details.get("ahead_of_upstream") or 0)
        if ahead > 0:
            _add(contradictions, Contradiction(
                code="unpushed_commits",
                system="git",
                severity=_severity(rules, "unpushed_commits"),
                expected="0 commits ahead",
                observed=f"{ahead} commits ahead",
                source=observation.source,
                message="Local branch has unpushed commits",
            ))

    recorded_prs = state.get("pull_requests", [])
    external_prs: List[Dict[str, Any]] = []
    for observation in by_system.get("github", []):
        if observation.details:
            external_prs.extend(observation.details.get("pull_requests", []))

    for recorded in recorded_prs:
        number = recorded.get("number")
        repo = recorded.get("repo", "")
        recorded_url = recorded.get("url") or ""
        match = next(
            (
                item for item in external_prs
                if item.get("number") == number
                and (
                    (recorded_url and item.get("url") == recorded_url)
                    or (not recorded_url and (not repo or repo in (item.get("url") or "")))
                )
            ),
            None,
        )
        if match is None and number is not None:
            _add(contradictions, Contradiction(
                code="pr_missing_external",
                system="github",
                severity=_severity(rules, "pr_missing_external"),
                expected=f"PR #{number}",
                observed="missing",
                source=f"github:{repo or 'unknown'}",
                message="Recorded pull request not found in GitHub",
            ))
        elif match is not None:
            recorded_state = (recorded.get("state") or "").lower()
            observed_state = (match.get("state") or "").lower()
            if match.get("isDraft") and recorded_state != "draft":
                observed_state = "draft"
            if recorded_state and observed_state and recorded_state != observed_state:
                _add(contradictions, Contradiction(
                    code="pr_state_mismatch",
                    system="github",
                    severity=_severity(rules, "pr_state_mismatch"),
                    expected=recorded_state,
                    observed=observed_state,
                    source=f"github:{repo or match.get('url', 'unknown')}",
                    message="Recorded pull request state differs from GitHub",
                ))
            observed_url = match.get("url") or ""
            if recorded_url and observed_url and recorded_url != observed_url:
                _add(contradictions, Contradiction(
                    code="pr_url_mismatch",
                    system="github",
                    severity=_severity(rules, "pr_url_mismatch"),
                    expected=recorded_url,
                    observed=observed_url,
                    source=f"github:{repo or observed_url}",
                    message="Recorded pull request URL differs from GitHub",
                ))

    for external in external_prs:
        number = external.get("number")
        if number is None:
            continue
        external_url = external.get("url") or ""
        known = any(
            item.get("number") == number
            and (
                not item.get("url")
                or item.get("url") == external_url
            )
            for item in recorded_prs
        )
        if not known:
            _add(contradictions, Contradiction(
                code="pr_discovered_not_recorded",
                system="github",
                severity=_severity(rules, "pr_discovered_not_recorded"),
                expected="recorded in ticket state",
                observed=f"PR #{number}",
                source=f"github:{external.get('url', 'unknown')}",
                message="GitHub pull request missing from structured state",
            ))

    success_results = {value.upper() for value in rules.get("jenkins", {}).get("success_results", ["SUCCESS"])}
    for recorded in state.get("builds", []):
        job = recorded.get("job", "")
        build_number = recorded.get("number")
        observation = next(
            (
                item for item in by_system.get("jenkins", [])
                if (item.details or {}).get("job") == job
            ),
            None,
        )
        source = observation.source if observation else f"jenkins:{job}"
        if not observation or not observation.details:
            continue
        recent = observation.details.get("recent_builds") or []
        if observation.details.get("last_build"):
            recent = recent or [observation.details["last_build"]]
        match = next((item for item in recent if item.get("number") == build_number), None)
        if build_number is not None and match is None:
            _add(contradictions, Contradiction(
                code="build_missing",
                system="jenkins",
                severity=_severity(rules, "build_missing"),
                expected=f"build #{build_number}",
                observed="missing",
                source=source,
                message="Recorded build not found in Jenkins",
            ))
        elif match is not None:
            recorded_result = (recorded.get("result") or "").upper()
            observed_result = (match.get("result") or "").upper()
            if recorded_result and observed_result and recorded_result != observed_result:
                _add(contradictions, Contradiction(
                    code="build_result_mismatch",
                    system="jenkins",
                    severity=_severity(rules, "build_result_mismatch"),
                    expected=recorded_result,
                    observed=observed_result,
                    source=source,
                    message="Recorded build result differs from Jenkins",
                ))

    merged_prs = [item for item in recorded_prs if (item.get("state") or "").lower() == "merged"]
    builds = state.get("builds", [])
    if merged_prs and not builds:
        _add(contradictions, Contradiction(
            code="merged_pr_no_build",
            system="jenkins",
            severity=_severity(rules, "merged_pr_no_build"),
            expected="build recorded",
            observed="none",
            source="jenkins",
            message="Merged pull request without recorded build",
        ))

    sync_state = state.get("synchronization", {})
    stored_sync = sync_state.get("state", "")
    synced_states = set(rules.get("argocd", {}).get("synced_states", ["Synced"]))
    out_of_sync_states = set(rules.get("argocd", {}).get("out_of_sync_states", ["OutOfSync"]))
    for observation in by_system.get("argocd", []):
        if not observation.details:
            continue
        observed_sync = observation.details.get("sync_status", "")
        if stored_sync == "synced" and observed_sync in out_of_sync_states:
            _add(contradictions, Contradiction(
                code="sync_state_mismatch",
                system="argocd",
                severity=_severity(rules, "sync_state_mismatch"),
                expected=stored_sync,
                observed=observed_sync,
                source=observation.source,
                message="Stored synchronization state differs from ArgoCD",
            ))
        elif stored_sync == "out_of_sync" and observed_sync in synced_states:
            _add(contradictions, Contradiction(
                code="sync_state_mismatch",
                system="argocd",
                severity=_severity(rules, "sync_state_mismatch"),
                expected=stored_sync,
                observed=observed_sync,
                source=observation.source,
                message="Stored synchronization state differs from ArgoCD",
            ))
        expected_revision = sync_state.get("expected_revision", "")
        observed_revision = observation.details.get("revision", "")
        if expected_revision and observed_revision and expected_revision != observed_revision:
            _add(contradictions, Contradiction(
                code="revision_mismatch",
                system="argocd",
                severity=_severity(rules, "revision_mismatch"),
                expected=expected_revision,
                observed=observed_revision,
                source=observation.source,
                message="Expected revision differs from live revision",
            ))

    if state.get("closeout", {}).get("deployment_complete"):
        for observation in by_system.get("argocd", []):
            observed_sync = (observation.details or {}).get("sync_status", "")
            if observed_sync and observed_sync not in synced_states:
                _add(contradictions, Contradiction(
                    code="deployment_complete_not_synced",
                    system="argocd",
                    severity=_severity(rules, "deployment_complete_not_synced"),
                    expected="synced",
                    observed=observed_sync,
                    source=observation.source,
                    message="Deployment marked complete while ArgoCD is not synchronized",
                ))
                break

    tempo = next(
        (item for item in by_system.get("tempo", []) if item.status == Status.READY and item.details),
        None,
    )
    closeout = state.get("closeout", {})
    if tempo:
        observed_seconds = int(tempo.details.get("total_seconds") or 0)
        stored_seconds = int(closeout.get("tempo_seconds") or 0)
        tolerance = int(rules.get("tempo", {}).get("seconds_tolerance") or 0)
        if closeout.get("tempo_logged") and observed_seconds == 0:
            _add(contradictions, Contradiction(
                code="tempo_logged_zero",
                system="tempo",
                severity=_severity(rules, "tempo_logged_zero"),
                expected="time logged",
                observed="0 seconds",
                source="tempo",
                message="Tempo logged flag set but no time observed",
            ))
        elif closeout.get("tempo_logged") and abs(observed_seconds - stored_seconds) > tolerance:
            _add(contradictions, Contradiction(
                code="tempo_seconds_mismatch",
                system="tempo",
                severity=_severity(rules, "tempo_seconds_mismatch"),
                expected=str(stored_seconds),
                observed=str(observed_seconds),
                source="tempo",
                message="Stored Tempo seconds differ from observed total",
            ))
        elif not closeout.get("tempo_logged") and observed_seconds > 0:
            _add(contradictions, Contradiction(
                code="tempo_unlogged_has_time",
                system="tempo",
                severity=_severity(rules, "tempo_unlogged_has_time"),
                expected="tempo_logged=false",
                observed=f"{observed_seconds} seconds",
                source="tempo",
                message="Tempo contains time but local state says it is not logged",
            ))

    return contradictions
