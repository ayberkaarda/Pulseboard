"""Redis lock behaviour against a real Redis."""

import time
from collections.abc import Callable

from redis import Redis

from pulseboard_analytics.locks import RedisLock

KEY = "test:lock"


def test_only_one_holder_at_a_time(redis_client: Redis) -> None:
    first = RedisLock(redis_client, KEY, 10_000)
    second = RedisLock(redis_client, KEY, 10_000)

    assert first.acquire()
    assert not second.acquire()
    assert first.release()
    assert second.acquire()


def test_release_only_by_the_owner(redis_client: Redis) -> None:
    owner = RedisLock(redis_client, KEY, 10_000)
    stranger = RedisLock(redis_client, KEY, 10_000)
    assert owner.acquire()

    assert not stranger.release()

    assert redis_client.get(KEY) is not None
    assert owner.release()
    assert redis_client.get(KEY) is None


def test_stale_owner_cannot_delete_a_newer_holders_lock(redis_client: Redis) -> None:
    slow = RedisLock(redis_client, KEY, 50)
    assert slow.acquire()
    _wait_until(lambda: redis_client.get(KEY) is None)
    successor = RedisLock(redis_client, KEY, 10_000)
    assert successor.acquire()

    assert not slow.release()

    assert redis_client.get(KEY) is not None
    assert successor.release()


def test_expired_lock_can_be_taken_again(redis_client: Redis) -> None:
    short = RedisLock(redis_client, KEY, 50)
    assert short.acquire()
    assert not RedisLock(redis_client, KEY, 50).acquire()

    _wait_until(lambda: redis_client.get(KEY) is None)

    assert RedisLock(redis_client, KEY, 10_000).acquire()


def test_lock_carries_a_ttl(redis_client: Redis) -> None:
    lock = RedisLock(redis_client, KEY, 55_000)
    assert lock.acquire()

    ttl = redis_client.pttl(KEY)

    assert 0 < ttl <= 55_000


def test_release_without_acquire_is_a_noop(redis_client: Redis) -> None:
    assert not RedisLock(redis_client, KEY, 1_000).release()


def _wait_until(condition: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.01)
    raise AssertionError("condition not met before the deadline")
