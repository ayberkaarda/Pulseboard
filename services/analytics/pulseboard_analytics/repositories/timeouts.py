"""Per-transaction statement and lock timeouts derived from a time budget."""

from sqlalchemy import Connection, text

from pulseboard_analytics.budget import Budget

_SET = text(
    "SELECT set_config('statement_timeout', :ms, true), set_config('lock_timeout', :ms, true)"
)


def apply_budget(conn: Connection, budget: Budget | None) -> None:
    """Limit the following statements of the current transaction to the time left in ``budget``.

    Raises :class:`~pulseboard_analytics.budget.BudgetExhausted` without touching the database
    when nothing is left. Without a budget the server defaults apply.
    """
    if budget is None:
        return
    conn.execute(_SET, {"ms": str(budget.timeout_ms())})
