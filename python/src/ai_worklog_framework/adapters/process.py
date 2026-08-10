import subprocess
from pathlib import Path
from typing import Dict, List, Tuple

from ai_worklog_framework.redaction import redact_string


def load_properties(path: Path) -> Dict[str, str]:
    props: Dict[str, str] = {}
    if not path.is_file():
        return props
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" not in line:
                    continue
                key, _, value = line.partition("=")
                props[key.strip()] = value.strip()
    except OSError:
        return {}
    return props


def validate_argv(argv: List[str]) -> None:
    if not argv or argv[0].startswith("-"):
        raise ValueError("Invalid command")
    for part in argv:
        if not part:
            raise ValueError("Empty command argument")
        if any(character in part for character in ("\x00", "\n", "\r")):
            raise ValueError("Invalid control character in command argument")


def run_process(argv: List[str], timeout: int = 15) -> Tuple[int, str, str]:
    validate_argv(argv)
    try:
        result = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return (
            result.returncode,
            result.stdout or "",
            redact_string(result.stderr or ""),
        )
    except subprocess.TimeoutExpired:
        return 124, "", "Timed out"
    except OSError as exc:
        return 127, "", redact_string(str(exc))
