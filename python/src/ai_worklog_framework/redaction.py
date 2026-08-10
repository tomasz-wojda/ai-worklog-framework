"""
redaction.py — Suppresses sensitive values in output and reports.

Detects and masks tokens, passwords, cookies, AWS secrets, API keys,
and authorization headers before they reach logs or generated reports.

Inputs:
  - Raw strings, dictionaries, or structured data.

Outputs:
  - Redacted versions safe for logging and display.
"""

import re
from typing import Any, Dict, List, Union

from ai_worklog_framework.shared import load_shared

_REDACTION_RULES = load_shared("redaction-patterns.json", {})
REDACTED = _REDACTION_RULES.get("redacted", "***REDACTED***")
SENSITIVE_KEY_PATTERNS = re.compile(
    _REDACTION_RULES.get(
        "sensitive_key_pattern",
        r"(token|password|secret|cookie|auth)",
    ),
    re.IGNORECASE,
)
SENSITIVE_VALUE_PATTERNS = [
    re.compile(pattern)
    for pattern in _REDACTION_RULES.get("sensitive_value_patterns", [])
]


def is_sensitive_key(key: str) -> bool:
    """
    Determines whether a key name likely holds a sensitive value.

    Args:
        key: Dictionary key or variable name.

    Returns:
        True if the key matches sensitive patterns.
    """
    return bool(SENSITIVE_KEY_PATTERNS.search(key))


def redact_value(value: str) -> str:
    """
    Masks a string that is known to be sensitive.

    Args:
        value: Raw secret string.

    Returns:
        Masked version showing only length hint.
    """
    if not value:
        return value
    length = len(value)
    if length <= 4:
        return REDACTED
    return f"{value[:2]}...{value[-2:]} ({length} chars)"


def redact_string(text: str) -> str:
    """
    Scans a string for embedded sensitive patterns and masks them.

    Args:
        text: Arbitrary text that may contain secrets.

    Returns:
        Text with sensitive patterns replaced.
    """
    result = text
    for pattern in SENSITIVE_VALUE_PATTERNS:
        result = pattern.sub(REDACTED, result)
    return result


def redact_dict(data: Dict[str, Any], depth: int = 0) -> Dict[str, Any]:
    """
    Recursively redacts sensitive values in a dictionary.

    Args:
        data: Dictionary potentially containing secrets.
        depth: Current recursion depth (safety limit at 10).

    Returns:
        New dictionary with sensitive values masked.
    """
    if depth > 10:
        return data

    redacted = {}
    for key, value in data.items():
        if is_sensitive_key(key):
            if isinstance(value, str):
                redacted[key] = redact_value(value)
            else:
                redacted[key] = REDACTED
        elif isinstance(value, dict):
            redacted[key] = redact_dict(value, depth + 1)
        elif isinstance(value, list):
            redacted[key] = redact_list(value, depth + 1)
        elif isinstance(value, str):
            redacted[key] = redact_string(value)
        else:
            redacted[key] = value
    return redacted


def redact_list(data: List[Any], depth: int = 0) -> List[Any]:
    """
    Recursively redacts sensitive values in a list.

    Args:
        data: List potentially containing secrets.
        depth: Current recursion depth.

    Returns:
        New list with sensitive values masked.
    """
    if depth > 10:
        return data

    result = []
    for item in data:
        if isinstance(item, dict):
            result.append(redact_dict(item, depth + 1))
        elif isinstance(item, str):
            result.append(redact_string(item))
        elif isinstance(item, list):
            result.append(redact_list(item, depth + 1))
        else:
            result.append(item)
    return result
