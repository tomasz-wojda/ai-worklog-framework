from typing import Any, Dict, List

from ai_worklog_framework.redaction import is_sensitive_key
from ai_worklog_framework.shared import load_shared


RULES = load_shared("ticket-state-rules.json", {})


def value_at(data: Dict[str, Any], path: str) -> Any:
    current: Any = data
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def validate_value(path: str, value: Any) -> List[str]:
    expected = RULES.get("path_types", {}).get(path)
    if not expected:
        return [f"Unknown or immutable state path: {path}"]
    if any(is_sensitive_key(part) for part in path.split(".")):
        return [f"Sensitive state path is forbidden: {path}"]
    checks = {
        "string": isinstance(value, str),
        "boolean": isinstance(value, bool),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "array": isinstance(value, list),
        "object": isinstance(value, dict),
    }
    if not checks.get(expected, False):
        return [f"{path} must be {expected}"]
    allowed = RULES.get("enums", {}).get(path)
    if allowed and value not in allowed:
        return [f"{path} must be one of: {', '.join(allowed)}"]
    return []


def validate_ticket_state(data: Dict[str, Any]) -> List[str]:
    errors: List[str] = []
    allowed = set(RULES.get("allowed_top_level", []))
    for key in data:
        if key not in allowed:
            errors.append(f"Unknown top-level field: {key}")
    for required in RULES.get("required", []):
        if required not in data:
            errors.append(f"Missing required field: {required}")
    for path in RULES.get("path_types", {}):
        value = value_at(data, path)
        if value is not None:
            errors.extend(validate_value(path, value))
    return errors
