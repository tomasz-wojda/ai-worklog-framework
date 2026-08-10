from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from ai_worklog_framework.result import Status


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class JenkinsReport:
    operation: str
    fetched_at: str
    status: Status
    controller: Optional[str] = None
    message: str = ""
    items: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "operation": self.operation,
            "fetched_at": self.fetched_at,
            "status": self.status.value,
            "items": self.items,
        }
        if self.controller is not None:
            payload["controller"] = self.controller
        if self.message:
            payload["message"] = self.message
        return payload
