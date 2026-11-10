"""Time budget of a unit of protected work (one metric, one notification round)."""

import time
from collections.abc import Callable
from dataclasses import dataclass, field


class BudgetExhausted(RuntimeError):
    """Raised when protected work would start or continue after its time budget ran out."""


@dataclass(frozen=True)
class Budget:
    """An absolute point on a monotonic clock after which no protected work may run."""

    expires_at: float
    monotonic: Callable[[], float] = field(default=time.monotonic, repr=False)

    def remaining_seconds(self) -> float:
        """Seconds left; zero or negative once the budget is spent."""
        return self.expires_at - self.monotonic()

    def timeout_ms(self) -> int:
        """Whole milliseconds left, at least 1; raises :class:`BudgetExhausted` when spent."""
        remaining = self.remaining_seconds()
        if remaining < 0.001:
            raise BudgetExhausted("time budget exhausted")
        return int(remaining * 1000.0)
