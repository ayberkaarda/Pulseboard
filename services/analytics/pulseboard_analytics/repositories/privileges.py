"""Startup check that the two database roles have exactly the intended privileges."""

from sqlalchemy import Engine, text

_ROLE = text(
    """
    SELECT current_user AS name,
           r.rolsuper,
           r.rolbypassrls,
           EXISTS (
               SELECT 1 FROM pg_class c
               WHERE c.relowner = r.oid
                 AND c.relname IN ('metric_point', 'metric_definition', 'anomaly', 'detector_state')
           ) AS owns_tables
    FROM pg_roles r
    WHERE r.rolname = current_user
    """
)
_PRIVILEGE = text("SELECT has_table_privilege(current_user, :table, :privilege)")

_Check = tuple[str, str]


class RoleConfigurationError(RuntimeError):
    """Raised when a database DSN does not connect with its intended least-privilege role."""


def _inspect(engine: Engine, checks: list[_Check]) -> tuple[str, list[str], dict[_Check, bool]]:
    with engine.connect() as conn:
        role = conn.execute(_ROLE).one()
        granted = {
            (table, kind): bool(
                conn.execute(_PRIVILEGE, {"table": table, "privilege": kind}).scalar_one()
            )
            for table, kind in checks
        }
    problems = []
    if role.rolsuper:
        problems.append("is a superuser")
    if role.rolbypassrls:
        problems.append("bypasses row level security")
    if role.owns_tables:
        problems.append("owns tables of the schema")
    return str(role.name), problems, granted


def verify_database_roles(reader: Engine, writer: Engine) -> None:
    """Check both DSNs; raises :class:`RoleConfigurationError` listing every problem found.

    The reader must be able to SELECT ``metric_point`` and must not be able to INSERT into
    ``anomaly`` or ``detector_state``. The writer must be able to INSERT into ``anomaly`` and to
    INSERT and UPDATE ``detector_state`` and must not be able to SELECT ``metric_point``. Neither
    may be a superuser, bypass row level security or own the tables.
    """
    errors: list[str] = []

    name, problems, granted = _inspect(
        reader, [("metric_point", "SELECT"), ("anomaly", "INSERT"), ("detector_state", "INSERT")]
    )
    errors.extend(f"reader role {name!r} {problem}" for problem in problems)
    if not granted[("metric_point", "SELECT")]:
        errors.append(f"reader role {name!r} cannot SELECT metric_point")
    if granted[("anomaly", "INSERT")]:
        errors.append(f"reader role {name!r} can INSERT into anomaly")
    if granted[("detector_state", "INSERT")]:
        errors.append(f"reader role {name!r} can INSERT into detector_state")

    required = [("anomaly", "INSERT"), ("detector_state", "INSERT"), ("detector_state", "UPDATE")]
    name, problems, granted = _inspect(writer, [("metric_point", "SELECT"), *required])
    errors.extend(f"writer role {name!r} {problem}" for problem in problems)
    errors.extend(
        f"writer role {name!r} cannot {kind} {table}"
        for table, kind in required
        if not granted[(table, kind)]
    )
    if granted[("metric_point", "SELECT")]:
        errors.append(f"writer role {name!r} can SELECT metric_point")

    if errors:
        raise RoleConfigurationError("database roles are misconfigured: " + "; ".join(errors))
