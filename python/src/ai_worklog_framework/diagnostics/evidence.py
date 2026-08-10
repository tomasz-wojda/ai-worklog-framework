from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List


@dataclass
class EvidenceStep:
    id: str
    command: List[str]
    exit_code: int
    duration_ms: int
    stdout: str
    stderr: str


@dataclass
class EvidenceBundle:
    pack: str
    timestamp: str
    parameters: Dict[str, str]
    status: str
    steps: List[EvidenceStep] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)
