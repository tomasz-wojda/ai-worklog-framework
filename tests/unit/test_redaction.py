"""
Unit tests for ai_worklog_framework.redaction module.
"""

import pytest
from ai_worklog_framework.redaction import (
    is_sensitive_key,
    redact_value,
    redact_string,
    redact_dict,
    REDACTED,
)


class TestIsSensitiveKey:
    def test_token_keys(self):
        assert is_sensitive_key("api_token") is True
        assert is_sensitive_key("AUTH_TOKEN") is True
        assert is_sensitive_key("bearer_token") is True

    def test_password_keys(self):
        assert is_sensitive_key("password") is True
        assert is_sensitive_key("PASSWD") is True
        assert is_sensitive_key("db_password") is True

    def test_aws_keys(self):
        assert is_sensitive_key("aws_secret_access_key") is True
        assert is_sensitive_key("aws_session_token") is True

    def test_safe_keys(self):
        assert is_sensitive_key("username") is False
        assert is_sensitive_key("hostname") is False
        assert is_sensitive_key("port") is False
        assert is_sensitive_key("description") is False


class TestRedactValue:
    def test_short_value(self):
        assert redact_value("abc") == REDACTED

    def test_longer_value(self):
        result = redact_value("my-secret-token-12345")
        assert "my" in result
        assert "45" in result
        assert "secret" not in result
        assert "21 chars" in result

    def test_empty_value(self):
        assert redact_value("") == ""


class TestRedactString:
    def test_bearer_token(self):
        text = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
        result = redact_string(text)
        assert "eyJhbGciOiJIUzI1NiJ9" not in result
        assert REDACTED in result

    def test_aws_key(self):
        text = "key = AKIAIOSFODNN7EXAMPLE"
        result = redact_string(text)
        assert "AKIAIOSFODNN7EXAMPLE" not in result

    def test_github_pat(self):
        text = "token: ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij"
        result = redact_string(text)
        assert "ghp_" not in result

    def test_safe_string(self):
        text = "hostname: host-a.example.com port: 8080"
        assert redact_string(text) == text


class TestRedactDict:
    def test_sensitive_key_redacted(self):
        data = {"url": "https://jira.example.com", "api_token": "secret123456"}
        result = redact_dict(data)
        assert result["url"] == "https://jira.example.com"
        assert "secret123456" not in result["api_token"]

    def test_nested_dict(self):
        data = {"service": {"name": "jira", "password": "hunter2"}}
        result = redact_dict(data)
        assert result["service"]["name"] == "jira"
        assert "hunter2" not in result["service"]["password"]

    def test_embedded_pattern_in_value(self):
        data = {"config": "Bearer my-token-here"}
        result = redact_dict(data)
        assert "my-token-here" not in result["config"]

    def test_safe_dict_unchanged(self):
        data = {"host": "host-a", "port": 8080, "enabled": True}
        result = redact_dict(data)
        assert result == data
