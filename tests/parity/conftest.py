import os

import pytest


PARITY_SUITE_DEFERRED = True
PARITY_SUITE_REASON = (
    "Cross-runtime parity is deferred during the contract-driven Jenkins CLI pilot; "
    "set AI_WORKLOG_PARITY_TESTS=1 to run it"
)


def defer_parity_suite():
    if PARITY_SUITE_DEFERRED and os.environ.get("AI_WORKLOG_PARITY_TESTS") != "1":
        return pytest.mark.skip(reason=PARITY_SUITE_REASON)
    return []
