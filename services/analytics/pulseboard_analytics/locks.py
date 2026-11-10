"""Redis lock with an owner token, compare-and-delete release and a lease check."""

import secrets
import time
from collections.abc import Callable

from redis import Redis

_RELEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
"""


class RedisLock:
    """Single-holder lock: ``SET key token NX PX ttl``, released only by its owner.

    The lease is not renewed. A holder that works for a long time must call :meth:`held`
    between units of work and stop when it returns ``False``.
    """

    def __init__(
        self,
        client: Redis,
        key: str,
        ttl_ms: int,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._client = client
        self._key = key
        self._ttl_ms = ttl_ms
        self._monotonic = monotonic
        self._token: str | None = None
        self._acquired_at = 0.0

    def acquire(self) -> bool:
        """Try to take the lock; returns False when somebody else holds it."""
        token = secrets.token_hex(16)
        # Taken before the SET so the local lease estimate never outlives the server-side TTL.
        started = self._monotonic()
        if self._client.set(self._key, token, nx=True, px=self._ttl_ms):
            self._token = token
            self._acquired_at = started
            return True
        return False

    def remaining_ms(self) -> float:
        """Lease left according to the local monotonic clock (0 when not held)."""
        if self._token is None:
            return 0.0
        return max(0.0, self._ttl_ms - (self._monotonic() - self._acquired_at) * 1000.0)

    def held(self) -> bool:
        """True while the lease has not run out and Redis still stores this owner's token."""
        if self._token is None or self.remaining_ms() <= 0.0:
            return False
        return self._client.get(self._key) == self._token

    def release(self) -> bool:
        """Delete the key only if it still holds this instance's token."""
        if self._token is None:
            return False
        deleted = self._client.eval(_RELEASE_SCRIPT, 1, self._key, self._token)
        self._token = None
        return bool(deleted)
