from ai_worklog_framework.adapters.http import http_request
from ai_worklog_framework.adapters.internal_ssl import https_context_for


def test_https_context_for_http_url():
    assert https_context_for("http://example.test/path") is None


def test_https_context_for_https_url(monkeypatch):
    captured = {}

    def fake_ssl_context_for(host, port):
        captured["host"] = host
        captured["port"] = port
        return object()

    monkeypatch.setattr(
        "ai_worklog_framework.adapters.internal_ssl.ssl_context_for",
        fake_ssl_context_for,
    )
    context = https_context_for("https://example.test:8443/path")
    assert context is not None
    assert captured == {"host": "example.test", "port": 8443}


def test_http_request_rejects_invalid_scheme():
    status, body = http_request("ftp://example.test/resource")
    assert status == 0
    assert body == "Invalid URL"
