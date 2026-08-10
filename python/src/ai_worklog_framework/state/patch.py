import json
from typing import Any, Dict

from ai_worklog_framework.state.validator import RULES, validate_value


def parse_value(raw: str) -> Any:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def apply_path(data: Dict[str, Any], path: str, value: Any) -> Any:
    if path in RULES.get("immutable_paths", []):
        raise ValueError(f"Immutable state path: {path}")
    errors = validate_value(path, value)
    if errors:
        raise ValueError(errors[0])
    parts = path.split(".")
    current = data
    for part in parts[:-1]:
        child = current.get(part)
        if not isinstance(child, dict):
            raise ValueError(f"State path is not an object: {part}")
        current = child
    previous = current.get(parts[-1])
    current[parts[-1]] = value
    return previous
