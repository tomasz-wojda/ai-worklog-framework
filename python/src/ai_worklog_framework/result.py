"""
result.py — Normalized operation results with standard severity levels.

Every adapter, check, and report returns a Result or list of Results.
Consumers aggregate Results to determine overall operational readiness.

Outputs:
  - Result objects with status, message, source, and optional detail.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


class Status(Enum):
    """Operational status levels."""
    READY = "ready"
    DEGRADED = "degraded"
    BLOCKED = "blocked"
    ERROR = "error"
    UNKNOWN = "unknown"


@dataclass
class Result:
    """
    Normalized result from any framework operation.

    Attributes:
        status: Operational status.
        source: Origin identifier (adapter name, check name, service).
        message: Human-readable summary.
        detail: Optional structured payload for programmatic consumption.
    """
    status: Status
    source: str
    message: str
    detail: Optional[Dict[str, Any]] = None

    @property
    def ok(self) -> bool:
        return self.status == Status.READY

    @property
    def actionable(self) -> bool:
        return self.status in (Status.DEGRADED, Status.BLOCKED, Status.ERROR)


@dataclass
class ResultSet:
    """
    Aggregated collection of Results.

    Attributes:
        results: Individual result entries.
    """
    results: List[Result] = field(default_factory=list)

    def add(self, result: Result) -> None:
        self.results.append(result)

    @property
    def overall_status(self) -> Status:
        """Returns the worst status across all results."""
        priority = [Status.ERROR, Status.BLOCKED, Status.DEGRADED, Status.UNKNOWN, Status.READY]
        for level in priority:
            if any(r.status == level for r in self.results):
                return level
        return Status.UNKNOWN

    @property
    def ok(self) -> bool:
        return self.overall_status == Status.READY

    def filter_actionable(self) -> List[Result]:
        """Returns only results requiring attention."""
        return [r for r in self.results if r.actionable]

    def summary(self) -> str:
        """Produces a compact multi-line summary of all results."""
        lines = []
        for r in self.results:
            indicator = {
                Status.READY: "[OK]",
                Status.DEGRADED: "[DEGRADED]",
                Status.BLOCKED: "[BLOCKED]",
                Status.ERROR: "[ERROR]",
                Status.UNKNOWN: "[?]",
            }.get(r.status, "[?]")
            lines.append(f"  {indicator} {r.source}: {r.message}")
        return "\n".join(lines)
