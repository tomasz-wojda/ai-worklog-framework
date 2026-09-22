import re
from itertools import zip_longest
from typing import Any, Dict, List, Tuple


_QUALIFIERS = {
    "snapshot": -5,
    "alpha": -4,
    "a": -4,
    "beta": -3,
    "b": -3,
    "milestone": -2,
    "m": -2,
    "rc": -1,
    "cr": -1,
    "": 0,
    "final": 0,
    "ga": 0,
    "release": 0,
    "sp": 1,
}


def _tokens(version: str) -> List[Tuple[int, Any]]:
    result: List[Tuple[int, Any]] = []
    for value in re.findall(r"\d+|[A-Za-z]+", str(version or "").lower()):
        if value.isdigit():
            result.append((2, int(value)))
        elif value in _QUALIFIERS:
            result.append((0, _QUALIFIERS[value]))
        else:
            result.append((1, value))
    while result and result[-1] in ((2, 0), (0, 0)):
        result.pop()
    return result


def compare_versions(left: str, right: str) -> int:
    for left_token, right_token in zip_longest(_tokens(left), _tokens(right)):
        if left_token == right_token:
            continue
        if left_token is None:
            result = _compare_missing(right_token)
            if result:
                return result
            continue
        if right_token is None:
            result = -_compare_missing(left_token)
            if result:
                return result
            continue
        if left_token[0] != right_token[0]:
            return 1 if left_token[0] > right_token[0] else -1
        return 1 if left_token[1] > right_token[1] else -1
    return 0


def _compare_missing(token: Tuple[int, Any]) -> int:
    kind, value = token
    if kind == 2:
        return 0 if value == 0 else -1
    if kind == 0:
        if value < 0:
            return 1
        return 0 if value == 0 else -1
    return -1


def version_at_least(actual: str, required: str) -> bool:
    return compare_versions(actual, required) >= 0


def _python_pattern(pattern: str) -> str:
    parts: List[str] = []
    cursor = 0
    for match in re.finditer(r"\\Q(.*?)\\E", pattern):
        parts.append(pattern[cursor:match.start()])
        parts.append(re.escape(match.group(1)))
        cursor = match.end()
    parts.append(pattern[cursor:])
    return "".join(parts)


def warning_matches(warning: Dict[str, Any], version: str) -> bool:
    ranges = warning.get("versions")
    if not isinstance(ranges, list) or not ranges:
        return True
    for entry in ranges:
        if not isinstance(entry, dict):
            return True
        pattern = entry.get("pattern")
        if not pattern:
            return True
        try:
            if re.fullmatch(_python_pattern(str(pattern)), str(version or "")):
                return True
        except re.error:
            return True
    return False
