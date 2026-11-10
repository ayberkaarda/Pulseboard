"""The startup role check enforces the complete least-privilege matrix."""

from collections.abc import Iterator
from contextlib import contextmanager

import pytest
from sqlalchemy import Engine, text
from sqlalchemy.engine import make_url

from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.repositories.privileges import (
    RoleConfigurationError,
    verify_database_roles,
)


@contextmanager
def _altered(owner: Engine, apply: list[str], revert: list[str]) -> Iterator[None]:
    """Run ``apply`` as the owner and always ``revert`` afterwards."""
    with owner.begin() as conn:
        for statement in apply:
            conn.execute(text(statement))
    try:
        yield
    finally:
        with owner.begin() as conn:
            for statement in revert:
                conn.execute(text(statement))


def _refused(reader: Engine, writer: Engine) -> str:
    with pytest.raises(RoleConfigurationError) as error:
        verify_database_roles(reader, writer)
    return str(error.value)


WRITER = "writer role 'analytics_writer'"
READER = "reader role 'analytics_reader'"


@pytest.mark.parametrize(
    ("grant", "revoke", "expected"),
    [
        (
            "GRANT DELETE ON anomaly TO analytics_writer",
            "REVOKE DELETE ON anomaly FROM analytics_writer",
            f"{WRITER} can DELETE anomaly",
        ),
        (
            "GRANT UPDATE ON anomaly TO analytics_writer",
            "REVOKE UPDATE ON anomaly FROM analytics_writer",
            f"{WRITER} can UPDATE anomaly",
        ),
        (
            "GRANT TRUNCATE ON anomaly TO analytics_writer",
            "REVOKE TRUNCATE ON anomaly FROM analytics_writer",
            f"{WRITER} can TRUNCATE anomaly",
        ),
        (
            "GRANT DELETE ON detector_state TO analytics_writer",
            "REVOKE DELETE ON detector_state FROM analytics_writer",
            f"{WRITER} can DELETE detector_state",
        ),
        (
            "GRANT SELECT ON metric_definition TO analytics_writer",
            "REVOKE SELECT ON metric_definition FROM analytics_writer",
            f"{WRITER} can SELECT metric_definition",
        ),
        (
            "GRANT SELECT ON app_user TO analytics_writer",
            "REVOKE SELECT ON app_user FROM analytics_writer",
            f"{WRITER} can SELECT app_user",
        ),
        (
            "GRANT UPDATE (details) ON anomaly TO analytics_writer",
            "REVOKE UPDATE (details) ON anomaly FROM analytics_writer",
            f"{WRITER} can UPDATE anomaly",
        ),
        (
            "REVOKE UPDATE ON detector_state FROM analytics_writer",
            "GRANT UPDATE ON detector_state TO analytics_writer",
            f"{WRITER} cannot UPDATE detector_state",
        ),
        (
            "REVOKE SELECT ON anomaly FROM analytics_writer",
            "GRANT SELECT ON anomaly TO analytics_writer",
            f"{WRITER} cannot SELECT anomaly",
        ),
    ],
)
def test_the_writer_must_have_exactly_its_privileges(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    grant: str,
    revoke: str,
    expected: str,
) -> None:
    with _altered(owner, [grant], [revoke]):
        assert expected in _refused(reader_engine, writer_engine)


@pytest.mark.parametrize(
    ("grant", "revoke", "expected"),
    [
        (
            "GRANT DELETE ON metric_point TO analytics_reader",
            "REVOKE DELETE ON metric_point FROM analytics_reader",
            f"{READER} can DELETE metric_point",
        ),
        (
            "GRANT INSERT ON metric_definition TO analytics_reader",
            "REVOKE INSERT ON metric_definition FROM analytics_reader",
            f"{READER} can INSERT metric_definition",
        ),
        (
            "GRANT SELECT ON anomaly TO analytics_reader",
            "REVOKE SELECT ON anomaly FROM analytics_reader",
            f"{READER} can SELECT anomaly",
        ),
        (
            "GRANT SELECT ON detector_state TO analytics_reader",
            "REVOKE SELECT ON detector_state FROM analytics_reader",
            f"{READER} can SELECT detector_state",
        ),
        (
            "GRANT SELECT ON app_user TO analytics_reader",
            "REVOKE SELECT ON app_user FROM analytics_reader",
            f"{READER} can SELECT app_user",
        ),
        (
            "REVOKE SELECT ON metric_definition FROM analytics_reader",
            "GRANT SELECT ON metric_definition TO analytics_reader",
            f"{READER} cannot SELECT metric_definition",
        ),
        (
            "REVOKE SELECT ON metric_point FROM analytics_reader",
            "GRANT SELECT ON metric_point TO analytics_reader",
            f"{READER} cannot SELECT metric_point",
        ),
    ],
)
def test_the_reader_must_have_exactly_its_privileges(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    grant: str,
    revoke: str,
    expected: str,
) -> None:
    with _altered(owner, [grant], [revoke]):
        assert expected in _refused(reader_engine, writer_engine)


def test_a_direct_grant_on_a_partition_is_refused(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    with _altered(
        owner,
        ["GRANT SELECT ON metric_point_all TO analytics_reader"],
        ["REVOKE SELECT ON metric_point_all FROM analytics_reader"],
    ):
        message = _refused(reader_engine, writer_engine)

    assert f"{READER} has a direct SELECT grant on child table metric_point_all" in message


def test_membership_in_the_owner_role_is_refused_without_inheritance_but_with_set(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    with _altered(
        owner,
        ["GRANT test TO analytics_reader WITH INHERIT FALSE, SET TRUE"],
        ["REVOKE test FROM analytics_reader"],
    ):
        message = _refused(reader_engine, writer_engine)

    assert f"{READER} can reach role 'test' (SET ROLE)" in message


def test_plain_membership_in_the_owner_role_is_refused(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    with _altered(
        owner,
        ["GRANT test TO analytics_writer WITH INHERIT FALSE, SET FALSE"],
        ["REVOKE test FROM analytics_writer"],
    ):
        message = _refused(reader_engine, writer_engine)

    assert f"{WRITER} can reach role 'test'" in message


def test_membership_in_a_predefined_role_is_refused(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    with _altered(
        owner,
        ["GRANT pg_read_all_data TO analytics_reader"],
        ["REVOKE pg_read_all_data FROM analytics_reader"],
    ):
        message = _refused(reader_engine, writer_engine)

    assert f"{READER} can reach role 'pg_read_all_data'" in message


@pytest.mark.parametrize(
    ("attribute", "reset", "expected"),
    [
        ("CREATEROLE", "NOCREATEROLE", "can create roles"),
        ("CREATEDB", "NOCREATEDB", "can create databases"),
        ("REPLICATION", "NOREPLICATION", "can start replication"),
    ],
)
def test_dangerous_role_attributes_are_refused(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    attribute: str,
    reset: str,
    expected: str,
) -> None:
    with _altered(
        owner,
        [f"ALTER ROLE analytics_writer {attribute}"],
        [f"ALTER ROLE analytics_writer {reset}"],
    ):
        assert f"{WRITER} {expected}" in _refused(reader_engine, writer_engine)


def test_a_same_named_table_in_another_schema_cannot_hide_a_public_grant(
    owner: Engine, postgres_url: str, writer_engine: Engine
) -> None:
    setup = [
        "CREATE SCHEMA decoy",
        "CREATE TABLE decoy.anomaly (id int)",
        "ALTER ROLE analytics_reader SET search_path = decoy, public",
        "GRANT USAGE ON SCHEMA decoy TO analytics_reader",
        "GRANT SELECT ON anomaly TO analytics_reader",
    ]
    teardown = [
        "ALTER ROLE analytics_reader RESET search_path",
        "REVOKE SELECT ON anomaly FROM analytics_reader",
        "DROP SCHEMA decoy CASCADE",
    ]
    reader_url = (
        make_url(postgres_url)
        .set(drivername="postgresql+psycopg", username="analytics_reader", password="reader-secret")
        .render_as_string(hide_password=False)
    )
    with _altered(owner, setup, teardown):
        reader = make_engine(reader_url)
        try:
            message = _refused(reader, writer_engine)
        finally:
            reader.dispose()

    assert f"{READER} can SELECT anomaly" in message


def test_the_restricted_roles_pass_again_after_every_alteration(
    reader_engine: Engine, writer_engine: Engine
) -> None:
    verify_database_roles(reader_engine, writer_engine)
