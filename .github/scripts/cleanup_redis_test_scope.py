#!/usr/bin/env python3
"""Delete only one explicitly held XA Mass Redis test scope."""

from __future__ import annotations

import argparse
import re


_TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")


def cleanup(redis_url: str, scope: str) -> tuple[int, int]:
    if _TEST_SCOPE.fullmatch(scope) is None:
        raise ValueError("cleanup scope must be an exact test_* scope")
    if not redis_url:
        raise ValueError("Redis URL must be non-empty")

    import redis

    client = redis.Redis.from_url(redis_url, decode_responses=True)
    pattern = f"xa_mass:{scope}:*"
    keys = tuple(client.scan_iter(match=pattern, count=100))
    removed = 0
    for offset in range(0, len(keys), 100):
        removed += int(client.unlink(*keys[offset:offset + 100]))
    print(
        "cleaned Redis test scope "
        f"scope={scope} observedKeys={len(keys)} removedKeys={removed}"
    )
    return len(keys), removed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--scope", required=True)
    args = parser.parse_args()
    cleanup(args.redis_url, args.scope)


if __name__ == "__main__":
    main()
