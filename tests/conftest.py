"""
pytest conftest for ai-worklog-framework.
Automatically adds python/src to sys.path for test execution.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "python" / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))
