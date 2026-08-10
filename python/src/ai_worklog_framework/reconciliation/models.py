from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from ai_worklog_framework.result import Status


@dataclass
class Observation:
    system: str
    source: str
    status: Status
    message: str
    details: Optional[Dict[str, Any]] = None


@dataclass
class Contradiction:
    code: str
    system: str
    severity: Status
    expected: str
    observed: str
    source: str
    message: str = ""


@dataclass
class ReconciliationReport:
    ticket_key: str
    timestamp: str
    overall_status: Status
    observations: List[Observation] = field(default_factory=list)
    contradictions: List[Contradiction] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "ticket_key": self.ticket_key,
            "timestamp": self.timestamp,
            "overall_status": self.overall_status.value,
            "observations": [
                {
                    "system": item.system,
                    "source": item.source,
                    "status": item.status.value,
                    "message": item.message,
                    "details": item.details or {},
                }
                for item in sorted(self.observations, key=lambda item: (item.system, item.source))
            ],
            "contradictions": [
                {
                    "code": item.code,
                    "system": item.system,
                    "severity": item.severity.value,
                    "expected": item.expected,
                    "observed": item.observed,
                    "source": item.source,
                    "message": item.message,
                }
                for item in sorted(
                    self.contradictions,
                    key=lambda item: (item.system, item.source, item.code),
                )
            ],
        }
