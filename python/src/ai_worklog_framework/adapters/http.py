import base64
import json
from typing import Any, Dict, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def http_request(
    url: str,
    method: str = "GET",
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 10,
    body: Optional[bytes] = None,
) -> Tuple[int, str]:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return 0, "Invalid URL"
    request = Request(url, data=body, method=method.upper())
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
        return exc.code, payload
    except URLError as exc:
        return 0, str(exc.reason)


def http_get_json(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 10,
) -> Tuple[int, Any]:
    status, body = http_request(url, headers=headers, timeout=timeout)
    if not body.strip():
        return status, None
    try:
        return status, json.loads(body)
    except json.JSONDecodeError:
        return status, None


def bearer_headers(token: str) -> Dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Accept": "application/json"}


def basic_headers(user: str, token: str) -> Dict[str, str]:
    encoded = base64.b64encode(f"{user}:{token}".encode("utf-8")).decode("ascii")
    return {"Authorization": f"Basic {encoded}", "Accept": "application/json"}
