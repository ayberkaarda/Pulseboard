"""HMAC helpers and startup configuration rules."""

import hashlib
import hmac

import pytest
from fastapi.testclient import TestClient

from pulseboard_analytics.main import create_app
from pulseboard_analytics.security import sign, verify
from pulseboard_analytics.settings import Settings, SettingsError

SECRET = "k" * 32
GOOD_ENV = {
    "DATABASE_READER_URL": "postgresql+psycopg://r:pw1@db/x",
    "DATABASE_WRITER_URL": "postgresql+psycopg://w:pw2@db/x",
    "REDIS_URL": "redis://:pw3@redis:6379/0",
    "API_CORE_BASE_URL": "http://api-core:8080",
    "INTERNAL_SHARED_SECRET": SECRET,
}


def test_sign_is_lowercase_hex_hmac_sha256_over_the_raw_bytes() -> None:
    body = b'{"a": 1}\n'

    expected = hmac.new(SECRET.encode(), body, hashlib.sha256).hexdigest()

    assert sign(SECRET, body) == expected
    assert expected == expected.lower()


def test_verify_accepts_only_the_exact_signature() -> None:
    body = b'{"metricId":"x"}'
    good = sign(SECRET, body)

    assert verify(SECRET, body, good)
    assert not verify(SECRET, body + b" ", good)
    assert not verify("z" * 32, body, good)
    assert not verify(SECRET, body, None)
    assert not verify(SECRET, body, "")
    assert not verify(SECRET, body, good.upper())
    assert not verify(SECRET, body, good[:-1])
    assert not verify(SECRET, body, good + "0")
    assert not verify(SECRET, body, "g" * 64)


def test_from_env_reads_all_values() -> None:
    settings = Settings.from_env(GOOD_ENV)

    assert settings.api_core_base_url == "http://api-core:8080"
    assert settings.scheduler_enabled is True
    assert settings.score_interval_seconds == 60
    assert settings.lock_ttl_ms == 55_000


@pytest.mark.parametrize("secret", ["", "short", "a" * 31])
def test_short_or_empty_secret_is_refused(secret: str) -> None:
    with pytest.raises(SettingsError, match="at least 32"):
        Settings.from_env({**GOOD_ENV, "INTERNAL_SHARED_SECRET": secret})


def test_missing_secret_is_refused() -> None:
    env = {k: v for k, v in GOOD_ENV.items() if k != "INTERNAL_SHARED_SECRET"}

    with pytest.raises(SettingsError):
        Settings.from_env(env)


@pytest.mark.parametrize("name", [k for k in GOOD_ENV if k != "INTERNAL_SHARED_SECRET"])
def test_missing_required_variable_is_refused(name: str) -> None:
    env = {k: v for k, v in GOOD_ENV.items() if k != name}

    with pytest.raises(SettingsError, match=name):
        Settings.from_env(env)


def test_scheduler_can_be_disabled_and_bad_numbers_are_rejected() -> None:
    assert Settings.from_env({**GOOD_ENV, "SCHEDULER_ENABLED": "false"}).scheduler_enabled is False
    with pytest.raises(SettingsError):
        Settings.from_env({**GOOD_ENV, "SCHEDULER_ENABLED": "maybe"})
    with pytest.raises(SettingsError):
        Settings.from_env({**GOOD_ENV, "SCORE_INTERVAL_SECONDS": "0"})
    with pytest.raises(SettingsError):
        Settings.from_env({**GOOD_ENV, "SCORE_LOCK_TTL_MS": "abc"})


def test_repr_never_contains_secrets_or_passwords() -> None:
    text = repr(Settings.from_env(GOOD_ENV))

    for leaked in (SECRET, "pw1", "pw2", "pw3"):
        assert leaked not in text


def test_application_refuses_to_start_with_a_weak_secret(monkeypatch: pytest.MonkeyPatch) -> None:
    for key, value in GOOD_ENV.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setenv("INTERNAL_SHARED_SECRET", "too-short")

    with pytest.raises(SettingsError), TestClient(create_app()):
        pass
