"""Redis lock with an owner token, compare-and-delete release and a lease check."""

import secrets
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager

from redis import Redis

_RELEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
"""

_GUARDED_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call(ARGV[2], KEYS[2], unpack(ARGV, 3))
    return 1
end
return 0
"""


class RedisLock:
    """Single-holder lock: ``SET key token NX PX ttl``, released only by its owner.

    The lease is not renewed. A holder that works for a long time must call :meth:`held`
    between units of work and stop when it returns ``False``; protected work must also finish
    before :meth:`expires_at`.
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

    def expires_at(self) -> float:
        """The monotonic time at which the lease ends (a conservative, local estimate)."""
        return self._acquired_at + self._ttl_ms / 1000.0

    def held(self) -> bool:
        """True while the lease has not run out and Redis still stores this owner's token.

        The local lease is checked again after the round trip, because a slow ``GET`` may
        return an answer that was true when Redis produced it but not any more.
        """
        if self._token is None or self.remaining_ms() <= 0.0:
            return False
        owned = self._client.get(self._key) == self._token
        return owned and self.remaining_ms() > 0.0

    def guarded(self, command: str, key: str, *args: str | int) -> bool:
        """Run one Redis write command on ``key`` only while this instance still owns the lock.

        The owner comparison and the write are one atomic script, so a run whose lease was lost
        (and taken over by another holder) can never write or delete anything. Returns whether
        the command ran.
        """
        if self._token is None:
            return False
        ran = self._client.eval(_GUARDED_SCRIPT, 2, self._key, key, self._token, command, *args)
        return bool(ran)

    def release(self) -> bool:
        """Delete the key only if it still holds this instance's token."""
        if self._token is None:
            return False
        deleted = self._client.eval(_RELEASE_SCRIPT, 1, self._key, self._token)
        self._token = None
        return bool(deleted)


class MetricBusyError(RuntimeError):
    """Raised when another caller holds the per-metric lock for longer than the wait allows.

    The metric is not scored in this round; the next run picks it up again.
    """


class KeyedLocks:
    """One exact lock per key, kept only while somebody holds or waits for it.

    The table never holds more entries than there are concurrent holders and waiters, so it is
    bounded by the concurrency of the process and unrelated keys never share a lock.
    """

    def __init__(self) -> None:
        self._guard = threading.Lock()
        self._entries: dict[object, tuple[threading.Lock, list[int]]] = {}

    def __len__(self) -> int:
        with self._guard:
            return len(self._entries)

    @contextmanager
    def hold(self, key: object, timeout: float) -> Iterator[None]:
        """Hold the lock of ``key``; raises :class:`MetricBusyError` after ``timeout`` seconds."""
        with self._guard:
            lock, users = self._entries.setdefault(key, (threading.Lock(), [0]))
            users[0] += 1
        acquired = False
        try:
            acquired = lock.acquire(timeout=max(0.0, timeout))
            if not acquired:
                raise MetricBusyError(f"lock for {key} not available within {timeout:.1f}s")
            yield
        finally:
            if acquired:
                lock.release()
            with self._guard:
                users[0] -= 1
                if users[0] == 0:
                    del self._entries[key]
