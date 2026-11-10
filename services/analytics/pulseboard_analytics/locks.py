"""Redis lock with an owner token and compare-and-delete release."""

import secrets

from redis import Redis

_RELEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
"""


class RedisLock:
    """Single-holder lock: ``SET key token NX PX ttl``, released only by its owner."""

    def __init__(self, client: Redis, key: str, ttl_ms: int) -> None:
        self._client = client
        self._key = key
        self._ttl_ms = ttl_ms
        self._token: str | None = None

    def acquire(self) -> bool:
        """Try to take the lock; returns False when somebody else holds it."""
        token = secrets.token_hex(16)
        if self._client.set(self._key, token, nx=True, px=self._ttl_ms):
            self._token = token
            return True
        return False

    def release(self) -> bool:
        """Delete the key only if it still holds this instance's token."""
        if self._token is None:
            return False
        deleted = self._client.eval(_RELEASE_SCRIPT, 1, self._key, self._token)
        self._token = None
        return bool(deleted)
