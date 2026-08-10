import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from ai_worklog_framework.global_config import resolve_workspace_selection
from ai_worklog_framework.shared import load_shared

_PATH_RULES = load_shared(
    "workspace-markers.json",
    {"markers": [".ai-worklog", "worklog", "prompt.log", "jira"], "max_parent_depth": 20},
)
WORKSPACE_MARKERS = _PATH_RULES["markers"]
MAX_PARENT_DEPTH = _PATH_RULES["max_parent_depth"]
SAFE_COMPONENT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


@dataclass(frozen=True)
class WorkspaceResolution:
    path: Path
    source: str
    name: Optional[str] = None


def _validate_component(value: str, label: str) -> str:
    if not SAFE_COMPONENT.fullmatch(value) or value in (".", ".."):
        raise ValueError(f"Invalid {label}: {value}")
    return value


def find_workspace_root(start: Optional[Path] = None) -> Optional[Path]:
    current = start or Path.cwd()
    current = current.resolve()
    for _ in range(MAX_PARENT_DEPTH):
        for marker in WORKSPACE_MARKERS:
            if (current / marker).exists():
                return current
        parent = current.parent
        if parent == current:
            break
        current = parent
    return None


def resolve_workspace_detailed(
    explicit_path: Optional[str] = None,
    explicit_name: Optional[str] = None,
    environment: Optional[dict[str, str]] = None,
) -> WorkspaceResolution:
    resolved = resolve_workspace_selection(explicit_path, explicit_name, environment)
    return WorkspaceResolution(
        path=resolved["path"],
        source=resolved["source"],
        name=resolved.get("name"),
    )


def resolve_workspace(
    explicit: Optional[str] = None,
    explicit_name: Optional[str] = None,
    environment: Optional[dict[str, str]] = None,
) -> Path:
    return resolve_workspace_detailed(explicit, explicit_name, environment).path


class WorkspacePaths:
    def __init__(self, root: Path):
        self.root = root
        self.worklog = root / "worklog"
        self.worklog_done = root / "worklog" / "done"
        self.state_dir = root / ".ai-worklog" / "state"
        self.config_dir = root / ".ai-worklog"
        self.catalog_dir = root / ".ai-worklog" / "catalog"
        self.interface_dir = root / "worklog" / "interface"
        self.prompt_log = root / "prompt.log"

    def service_dir(self, service: str) -> Path:
        service = _validate_component(service, "service")
        interface = self.interface_dir / service
        if interface.exists():
            return interface
        root_svc = self.root / service
        if root_svc.exists():
            return root_svc
        return interface

    def ticket_state_file(self, ticket_key: str) -> Path:
        ticket_key = _validate_component(ticket_key, "ticket key")
        return self.state_dir / f"{ticket_key}.json"
