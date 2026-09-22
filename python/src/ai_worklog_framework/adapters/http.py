import base64
import json
from typing import Any, Dict, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import (
    HTTPRedirectHandler,
    HTTPSHandler,
    Request,
    build_opener,
)

from ai_worklog_framework.adapters.internal_ssl import https_context_for


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_request(
    url: str,
    method: str = "GET",
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 10,
    body: Optional[bytes] = None,
) -> Tuple[int, str]:
    status, payload, _ = http_request_details(
        url,
        method=method,
        headers=headers,
        timeout=timeout,
        body=body,
    )
    return status, payload


def http_request_details(
    url: str,
    method: str = "GET",
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 10,
    body: Optional[bytes] = None,
) -> Tuple[int, str, Dict[str, str]]:
    current_url = url
    for _ in range(6):
        parsed = urlparse(current_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            return 0, "Invalid URL", {}
        request = Request(current_url, data=body, method=method.upper())
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        context = https_context_for(current_url)
        opener = build_opener(HTTPSHandler(context=context), _NoRedirect())
        try:
            with opener.open(request, timeout=timeout) as response:
                response_headers = {
                    key.lower(): value for key, value in response.headers.items()
                }
                return (
                    response.status,
                    response.read().decode("utf-8", errors="replace"),
                    response_headers,
                )
        except HTTPError as exc:
            response_headers = (
                {key.lower(): value for key, value in exc.headers.items()}
                if exc.headers
                else {}
            )
            if exc.code in (301, 302, 303, 307, 308) and response_headers.get("location"):
                current_url = urljoin(current_url, response_headers["location"])
                continue
            payload = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            return exc.code, payload, response_headers
        except URLError as exc:
            return 0, str(exc.reason), {}
    return 0, "Too many redirects", {}


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
