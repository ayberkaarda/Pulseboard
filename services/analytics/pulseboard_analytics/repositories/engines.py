"""Engine factories; reader and writer connect with different database roles."""

from sqlalchemy import Engine, create_engine
from sqlalchemy.engine import make_url

_DRIVER = "postgresql+psycopg"


def make_engine(url: str, *, pool_size: int = 5) -> Engine:
    """Create a pooled psycopg 3 engine; bare ``postgresql://`` URLs are upgraded to psycopg."""
    parsed = make_url(url)
    if parsed.drivername == "postgresql":
        parsed = parsed.set(drivername=_DRIVER)
    return create_engine(parsed, pool_size=pool_size, max_overflow=2, pool_pre_ping=True)
