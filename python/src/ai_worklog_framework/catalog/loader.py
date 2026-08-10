"""
loader.py — Catalog entry loading, parsing, and validation.

Loads service catalog entries from the framework catalog directory and optional
workspace-local overrides, validates structure, and returns merged entries.

Inputs:
  - WorkspacePaths with catalog_dir pointing to catalog JSON files.

Outputs:
  - Dictionary of service_id -> entry data.
  - Validation error lists per entry.
"""

import json
from pathlib import Path
from typing import Any, Dict, List, Optional

from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.shared import framework_root, load_shared


_CATALOG_RULES = load_shared("catalog-rules.json", {})
REQUIRED_FIELDS = _CATALOG_RULES.get("required_fields", ["id", "name", "type"])
VALID_TYPES = _CATALOG_RULES.get(
    "valid_types", ["application", "infrastructure", "library", "pipeline", "platform"]
)
FORBIDDEN_SECRET_FIELDS = _CATALOG_RULES.get(
    "forbidden_secret_fields", ["value", "password"]
)


def load_catalog(paths: WorkspacePaths) -> Dict[str, Dict[str, Any]]:
    """
    Loads all catalog entries from framework and workspace catalog directories.

    Searches for .json files in:
      1. Framework bundled catalog (if referenced in config).
      2. Workspace catalog directory (.ai-worklog/catalog/).

    Args:
        paths: Resolved workspace paths.

    Returns:
        Dictionary mapping service IDs to their catalog entries.
    """
    catalog: Dict[str, Dict[str, Any]] = {}

    configured_catalog = load_config(paths.root).catalog_path
    catalog_dirs = [configured_catalog]
    if paths.catalog_dir not in catalog_dirs:
        catalog_dirs.append(paths.catalog_dir)

    framework_catalog = framework_root() / "catalog"
    if framework_catalog.is_dir() and framework_catalog != paths.catalog_dir:
        catalog_dirs.insert(0, framework_catalog)

    for catalog_dir in catalog_dirs:
        if not catalog_dir.is_dir():
            continue
        for json_file in sorted(catalog_dir.glob("*.json")):
            try:
                with open(json_file, "r", encoding="utf-8") as f:
                    entry = json.load(f)
                if isinstance(entry, dict) and "id" in entry:
                    catalog[entry["id"]] = entry
                elif isinstance(entry, list):
                    for item in entry:
                        if isinstance(item, dict) and "id" in item:
                            catalog[item["id"]] = item
            except (json.JSONDecodeError, OSError):
                continue

    return catalog


def validate_entry(entry: Dict[str, Any]) -> List[str]:
    """
    Validates a single catalog entry against required structure.

    Args:
        entry: Dictionary representing one service catalog entry.

    Returns:
        List of error strings (empty if valid).
    """
    errors = []

    for field in REQUIRED_FIELDS:
        if field not in entry:
            errors.append(f"Missing required field: {field}")

    entry_type = entry.get("type")
    if entry_type and entry_type not in VALID_TYPES:
        errors.append(f"Invalid type '{entry_type}', must be one of: {VALID_TYPES}")

    repos = entry.get("repositories", [])
    if not isinstance(repos, list):
        errors.append("'repositories' must be an array")
    else:
        for i, repo in enumerate(repos):
            if not isinstance(repo, dict):
                errors.append(f"repositories[{i}] must be an object")
            elif "url" not in repo and "local_dir" not in repo:
                errors.append(f"repositories[{i}] must have 'url' or 'local_dir'")

    jenkins = entry.get("jenkins")
    if jenkins and not isinstance(jenkins, dict):
        errors.append("'jenkins' must be an object")

    argocd = entry.get("argocd")
    if argocd and not isinstance(argocd, dict):
        errors.append("'argocd' must be an object")

    secrets = entry.get("secrets", [])
    if not isinstance(secrets, list):
        errors.append("'secrets' must be an array")
    else:
        for i, secret in enumerate(secrets):
            if isinstance(secret, dict):
                if any(field in secret for field in FORBIDDEN_SECRET_FIELDS):
                    errors.append(f"secrets[{i}] must NOT contain actual secret values")

    return errors


def find_services_for_ticket(
    catalog: Dict[str, Dict[str, Any]],
    jira_project: Optional[str] = None,
    jira_components: Optional[List[str]] = None,
    ticket_summary: Optional[str] = None,
) -> List[str]:
    """
    Identifies catalog services that may be relevant to a JIRA ticket.

    Matches by project, component, or keyword in summary.

    Args:
        catalog: Full loaded catalog.
        jira_project: JIRA project key (e.g., 'PROJ', 'APP', 'OPS').
        jira_components: JIRA component names.
        ticket_summary: Ticket summary text for keyword matching.

    Returns:
        List of matching service IDs, sorted by relevance.
    """
    matches = []
    components_lower = [c.lower() for c in (jira_components or [])]
    summary_lower = (ticket_summary or "").lower()

    for service_id, entry in catalog.items():
        score = 0
        jira_cfg = entry.get("jira", {})

        if jira_project and jira_cfg.get("project") == jira_project:
            score += 10

        entry_components = [c.lower() for c in jira_cfg.get("components", [])]
        for comp in components_lower:
            if comp in entry_components:
                score += 5

        if summary_lower:
            name_lower = entry.get("name", "").lower()
            if name_lower and name_lower in summary_lower:
                score += 3
            if service_id.lower() in summary_lower:
                score += 3

        if score > 0:
            matches.append((score, service_id))

    matches.sort(key=lambda x: -x[0])
    return [m[1] for m in matches]
