"""Startup check that the two database roles have exactly the intended privileges.

The check compares the effective privileges of ``current_user`` on every relation of the
``public`` schema with a fixed least-privilege matrix:

==================  ============================  ================================
relation            ``analytics_reader``          ``analytics_writer``
==================  ============================  ================================
metric_point        SELECT                        nothing
metric_definition   SELECT                        nothing
anomaly             nothing                       SELECT, INSERT
detector_state      nothing                       SELECT, INSERT, UPDATE
anything else       nothing                       nothing
==================  ============================  ================================

Partitions and inheritance children (for example the monthly ``metric_point`` partitions) must
carry no direct privileges: access goes through the parent. Column-level grants count as grants,
and so do sequences and functions of the schema. Privileges are resolved by relation OID in the
``public`` schema, never by an unqualified name, so a same-named table elsewhere cannot hide a
problem.

Besides the matrix a role must not be a superuser, bypass row level security, create roles or
databases, start replication, own anything in the schema, or be able to reach such a role:
membership (with or without inheritance, and ``SET ROLE`` on PostgreSQL 16 and later) in a
superuser, in a ``pg_*`` predefined role or in an owner of schema objects is refused.

Verification is on by default (``VERIFY_DB_ROLES=true``). Setting it to ``false`` skips the check
and exists for tests that run without the real role layout; do not use it in a deployment.
"""

from sqlalchemy import Connection, Engine, text

_TABLE_PRIVILEGES = ("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")

_READER_MATRIX: dict[str, frozenset[str]] = {
    "metric_point": frozenset({"SELECT"}),
    "metric_definition": frozenset({"SELECT"}),
    "anomaly": frozenset(),
    "detector_state": frozenset(),
}
_WRITER_MATRIX: dict[str, frozenset[str]] = {
    "metric_point": frozenset(),
    "metric_definition": frozenset(),
    "anomaly": frozenset({"SELECT", "INSERT"}),
    "detector_state": frozenset({"SELECT", "INSERT", "UPDATE"}),
}

_ROLE = text(
    """
    SELECT current_user AS name, r.rolsuper, r.rolbypassrls, r.rolcreaterole, r.rolcreatedb,
           r.rolreplication,
           EXISTS (
               SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relowner = r.oid
           ) AS owns_relations,
           EXISTS (
               SELECT 1 FROM pg_namespace n WHERE n.nspname = 'public' AND n.nspowner = r.oid
           ) AS owns_schema,
           current_setting('server_version_num')::int AS server_version
    FROM pg_roles r
    WHERE r.rolname = current_user
    """
)

_RELATIONS = text(
    """
    SELECT c.relname AS name, p.priv AS privilege,
           (c.relispartition
            OR EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)) AS is_child,
           (has_table_privilege(current_user, c.oid, p.priv)
            OR (p.priv IN ('SELECT', 'INSERT', 'UPDATE', 'REFERENCES')
                AND has_any_column_privilege(current_user, c.oid, p.priv))) AS granted
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN unnest(CAST(:privileges AS text[])) AS p(priv)
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
    """
)

_SEQUENCES = text(
    """
    SELECT c.relname AS name, p.priv AS privilege,
           has_sequence_privilege(current_user, c.oid, p.priv) AS granted
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    CROSS JOIN unnest(ARRAY['USAGE', 'SELECT', 'UPDATE']) AS p(priv)
    WHERE n.nspname = 'public' AND c.relkind = 'S'
    """
)

_FUNCTIONS = text(
    """
    SELECT p.oid::regprocedure::text AS name
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
          SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e'
      )
      AND has_function_privilege(current_user, p.oid, 'EXECUTE')
    """
)

_REACHABLE = """
    SELECT t.rolname AS name,
           pg_has_role(current_user, t.oid, 'MEMBER') AS member,
           pg_has_role(current_user, t.oid, 'USAGE') AS inherits,
           {can_set} AS can_set
    FROM pg_roles t
    WHERE t.rolname <> current_user
      AND (
          t.rolsuper
          OR t.rolname LIKE 'pg\\_%'
          OR t.oid IN (
              SELECT c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public'
          )
          OR t.oid IN (SELECT n.nspowner FROM pg_namespace n WHERE n.nspname = 'public')
          OR t.oid IN (
              SELECT p.proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'public'
          )
      )
"""

_POSTGRES_16 = 160000


class RoleConfigurationError(RuntimeError):
    """Raised when a database DSN does not connect with its intended least-privilege role."""


def _role_problems(conn: Connection) -> tuple[str, list[str]]:
    role = conn.execute(_ROLE).one()
    problems = [
        message
        for flag, message in (
            (role.rolsuper, "is a superuser"),
            (role.rolbypassrls, "bypasses row level security"),
            (role.rolcreaterole, "can create roles"),
            (role.rolcreatedb, "can create databases"),
            (role.rolreplication, "can start replication"),
            (role.owns_relations, "owns tables of the schema"),
            (role.owns_schema, "owns the schema"),
        )
        if flag
    ]
    can_set = (
        "pg_has_role(current_user, t.oid, 'SET')"
        if role.server_version >= _POSTGRES_16
        else "false"
    )
    for target in conn.execute(text(_REACHABLE.format(can_set=can_set))):
        if target.member:
            via = (
                "SET ROLE" if target.can_set else "inheritance" if target.inherits else "membership"
            )
            problems.append(f"can reach role {target.name!r} ({via})")
    return str(role.name), problems


def _privilege_problems(conn: Connection, matrix: dict[str, frozenset[str]]) -> list[str]:
    problems: list[str] = []
    seen: set[str] = set()
    for row in conn.execute(_RELATIONS, {"privileges": list(_TABLE_PRIVILEGES)}):
        seen.add(row.name)
        if row.is_child:
            if row.granted:
                problems.append(f"has a direct {row.privilege} grant on child table {row.name}")
            continue
        allowed = matrix.get(row.name, frozenset())
        if row.granted and row.privilege not in allowed:
            problems.append(f"can {row.privilege} {row.name}")
        elif not row.granted and row.privilege in allowed:
            problems.append(f"cannot {row.privilege} {row.name}")
    problems.extend(f"cannot see table {name} (missing)" for name in sorted(matrix.keys() - seen))
    problems.extend(
        f"can {row.privilege} sequence {row.name}"
        for row in conn.execute(_SEQUENCES)
        if row.granted
    )
    problems.extend(f"can EXECUTE function {row.name}" for row in conn.execute(_FUNCTIONS))
    return problems


def _inspect(engine: Engine, matrix: dict[str, frozenset[str]]) -> tuple[str, list[str]]:
    with engine.connect() as conn:
        name, role_problems = _role_problems(conn)
        return name, [*role_problems, *_privilege_problems(conn, matrix)]


def verify_database_roles(reader: Engine, writer: Engine) -> None:
    """Check both DSNs against the privilege matrix; raises :class:`RoleConfigurationError`.

    Every problem found is listed. See the module docstring for the matrix and the role rules.
    """
    errors: list[str] = []
    for label, engine, matrix in (
        ("reader", reader, _READER_MATRIX),
        ("writer", writer, _WRITER_MATRIX),
    ):
        name, problems = _inspect(engine, matrix)
        errors.extend(f"{label} role {name!r} {problem}" for problem in problems)
    if errors:
        raise RoleConfigurationError("database roles are misconfigured: " + "; ".join(errors))
