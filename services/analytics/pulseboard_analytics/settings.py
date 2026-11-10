"""Runtime configuration read from environment variables."""

import os
from collections.abc import Mapping
from dataclasses import dataclass, field

MIN_SECRET_LENGTH = 32


class SettingsError(ValueError):
    """Raised when the configuration is missing or unsafe; the service refuses to start."""


def _require(env: Mapping[str, str], name: str) -> str:
    value = env.get(name, "").strip()
    if not value:
        raise SettingsError(f"{name} must be set")
    return value


def _positive_int(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError:
        raise SettingsError(f"{name} must be an integer") from None
    if value < 1:
        raise SettingsError(f"{name} must be at least 1")
    return value


def _flag(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None or not raw.strip():
        return default
    lowered = raw.strip().lower()
    if lowered in {"1", "true", "yes", "on"}:
        return True
    if lowered in {"0", "false", "no", "off"}:
        return False
    raise SettingsError(f"{name} must be a boolean")


@dataclass(frozen=True)
class Settings:
    """Validated configuration. Secrets and DSNs are excluded from ``repr``."""

    database_reader_url: str = field(repr=False)
    database_writer_url: str = field(repr=False)
    redis_url: str = field(repr=False)
    api_core_base_url: str
    internal_shared_secret: str = field(repr=False)
    scheduler_enabled: bool = True
    score_interval_seconds: int = 60
    lock_ttl_ms: int = 55_000
    max_request_bytes: int = 4096

    def __post_init__(self) -> None:
        if len(self.internal_shared_secret) < MIN_SECRET_LENGTH:
            raise SettingsError(
                f"INTERNAL_SHARED_SECRET must be at least {MIN_SECRET_LENGTH} characters"
            )

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        """Build settings from ``env`` (defaults to the process environment)."""
        source = os.environ if env is None else env
        return cls(
            database_reader_url=_require(source, "DATABASE_READER_URL"),
            database_writer_url=_require(source, "DATABASE_WRITER_URL"),
            redis_url=_require(source, "REDIS_URL"),
            api_core_base_url=_require(source, "API_CORE_BASE_URL"),
            internal_shared_secret=source.get("INTERNAL_SHARED_SECRET", ""),
            scheduler_enabled=_flag(source, "SCHEDULER_ENABLED", True),
            score_interval_seconds=_positive_int(source, "SCORE_INTERVAL_SECONDS", 60),
            lock_ttl_ms=_positive_int(source, "SCORE_LOCK_TTL_MS", 55_000),
        )
