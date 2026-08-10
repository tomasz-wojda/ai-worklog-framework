from dataclasses import dataclass
from typing import Dict, List, Optional, Set

from ai_worklog_framework.catalog.loader import (
    find_services_for_ticket,
    load_catalog,
)
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.shared import load_shared
from ai_worklog_framework.state.manager import load_state


@dataclass
class PreflightScope:
    checks: Optional[Set[str]]
    service_ids: List[str]
    catalog: Dict[str, dict]


def resolve_scope(
    paths: WorkspacePaths,
    ticket: Optional[str],
    services: Optional[List[str]],
) -> PreflightScope:
    catalog = load_catalog(paths)
    if not ticket and not services:
        return PreflightScope(None, [], catalog)

    rules = load_shared("preflight-checks.json", {})
    service_ids: Set[str] = set()
    explicit_checks: Set[str] = set()
    for service in services or []:
        if service in catalog:
            service_ids.add(service)
        else:
            explicit_checks.update(rules.get("service_checks", {}).get(service, [service]))

    if ticket:
        state_file = paths.ticket_state_file(ticket)
        state_services = load_state(paths, ticket).get("services", []) if state_file.is_file() else []
        if state_services:
            service_ids.update(state_services)
        else:
            project = ticket.rsplit("-", 1)[0] if "-" in ticket else ticket
            service_ids.update(find_services_for_ticket(catalog, jira_project=project))

    checks = set(rules.get("global_checks", []))
    checks.update(explicit_checks)
    for service_id in service_ids:
        entry = catalog.get(service_id, {})
        if entry.get("jenkins"):
            checks.add("jenkins")
        if entry.get("argocd"):
            checks.add("argocd")
        monitoring = entry.get("monitoring", {})
        if monitoring.get("newrelic_entity_name"):
            checks.add("newrelic")
        if entry.get("environments"):
            checks.update(["aws", "kubectl"])
    checks.update(rules.get("derived_checks", []))
    return PreflightScope(checks, sorted(service_ids), catalog)
