"""Engine factories; reader and writer connect with different database roles."""

from sqlalchemy import Engine, create_engine
from sqlalchemy.engine import make_url

from pulseboard_analytics.repositories.connections import (
    DEFAULT_CONNECT_TIMEOUT_SECONDS,
    DEFAULT_POOL_TIMEOUT_SECONDS,
    configure,
)

_DRIVER = "postgresql+psycopg"


def make_engine(
    url: str,
    *,
    pool_size: int = 5,
    pool_timeout: float = DEFAULT_POOL_TIMEOUT_SECONDS,
    connect_timeout: int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) -> Engine:
    """Create a pooled psycopg 3 engine; bare ``postgresql://`` URLs are upgraded to psycopg.

    ``pool_timeout`` bounds the wait for a free pooled connection and ``connect_timeout`` the
    opening of a new one; both are further cut to the time budget of a call (see
    :mod:`pulseboard_analytics.repositories.connections`).
    """
    parsed = make_url(url)
    if parsed.drivername == "postgresql":
        parsed = parsed.set(drivername=_DRIVER)
    engine = create_engine(
        parsed,
        pool_size=pool_size,
        max_overflow=2,
        pool_pre_ping=True,
        pool_timeout=pool_timeout,
    )
    configure(engine, pool_timeout=pool_timeout, connect_timeout=connect_timeout)
    return engine
