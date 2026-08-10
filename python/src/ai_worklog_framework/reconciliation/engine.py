from datetime import datetime, timezone
from typing import Sequence

from ai_worklog_framework.adapters import argocd, git, github, jenkins, jira, tempo
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.reconciliation.comparators import compare_state, load_rules
from ai_worklog_framework.reconciliation.models import ReconciliationReport
from ai_worklog_framework.result import Status
from ai_worklog_framework.state.manager import load_state


SYSTEMS = ("jira", "git", "github", "jenkins", "argocd", "tempo")


def workspace_rules(paths: WorkspacePaths):
    rules = load_rules()
    override = load_config(paths.root).adapters.get("reconciliation", {})
    if override.get("enabled_systems") is not None:
        rules["systems"] = override["enabled_systems"]
    if override.get("http_timeout_seconds") is not None:
        rules.setdefault("timeouts", {})["http_seconds"] = override["http_timeout_seconds"]
    if override.get("process_timeout_seconds") is not None:
        rules.setdefault("timeouts", {})["process_seconds"] = override["process_timeout_seconds"]
    if override.get("repositories_root") is not None:
        rules["repositories_root"] = override["repositories_root"]
    if override.get("jenkins_max_builds") is not None:
        rules["jenkins_max_builds"] = override["jenkins_max_builds"]
    return rules


def _overall_status(report: ReconciliationReport) -> Status:
    priority = [Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.UNKNOWN, Status.READY]
    statuses = [item.status for item in report.observations]
    statuses.extend(item.severity for item in report.contradictions)
    for level in priority:
        if any(status == level for status in statuses):
            return level
    return Status.UNKNOWN


def reconcile_ticket(
    paths: WorkspacePaths,
    ticket_key: str,
    systems: Sequence[str],
) -> ReconciliationReport:
    rules = workspace_rules(paths)
    state_data = load_state(paths, ticket_key).data
    observations = []
    http_timeout = int(rules.get("timeouts", {}).get("http_seconds", 10))
    process_timeout = int(rules.get("timeouts", {}).get("process_seconds", 15))
    max_builds = int(rules.get("jenkins_max_builds", 5))
    repositories_root = str(rules.get("repositories_root", "repos"))

    for system in systems:
        if system == "jira":
            observations.extend(jira.observe_jira(paths, ticket_key, timeout=http_timeout))
        elif system == "git":
            observations.extend(git.observe_git(
                paths,
                state_data,
                timeout=process_timeout,
                repositories_root=repositories_root,
            ))
        elif system == "github":
            observations.extend(github.observe_github(paths, ticket_key, state_data, timeout=process_timeout))
        elif system == "jenkins":
            observations.extend(jenkins.observe_jenkins(
                paths,
                state_data,
                timeout=http_timeout,
                max_builds=max_builds,
            ))
        elif system == "argocd":
            observations.extend(argocd.observe_argocd(paths, state_data, timeout=process_timeout))
        elif system == "tempo":
            observations.extend(tempo.observe_tempo(
                paths,
                ticket_key,
                state_data,
                timeout=http_timeout,
                api_path=rules.get("tempo", {}).get(
                    "api_path",
                    "/rest/tempo-timesheets/3/worklogs",
                ),
            ))

    contradictions = compare_state(state_data, observations, rules)
    report = ReconciliationReport(
        ticket_key=ticket_key,
        timestamp=datetime.now(timezone.utc).isoformat(),
        overall_status=Status.UNKNOWN,
        observations=sorted(observations, key=lambda item: (item.system, item.source)),
        contradictions=sorted(contradictions, key=lambda item: (item.system, item.source, item.code)),
    )
    report.overall_status = _overall_status(report)
    return report
