#!/usr/bin/env python3
"""Delete only one explicitly held XA Mass Redis test scope."""

from __future__ import annotations

import argparse
import os
import re
import sys


_TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")
_EPHEMERAL_REDIS_ENV = "XA_MASS_REDIS_EPHEMERAL"


def _validate(redis_url: str, scope: str) -> None:
    if _TEST_SCOPE.fullmatch(scope) is None:
        raise ValueError("cleanup scope must be an exact test_* scope")
    if not redis_url:
        raise ValueError("Redis URL must be non-empty")


def cleanup(redis_url: str, scope: str) -> tuple[int, int]:
    _validate(redis_url, scope)

    import redis

    pattern = f"xa_mass:{scope}:*"
    with redis.Redis.from_url(redis_url, decode_responses=True) as client:
        batch: list[str] = []
        observed = 0
        removed = 0
        for key in client.scan_iter(match=pattern, count=100):
            observed += 1
            batch.append(key)
            if len(batch) == 100:
                removed += int(client.unlink(*batch))
                batch.clear()
        if batch:
            removed += int(client.unlink(*batch))
    print(
        "cleaned Redis test scope "
        f"scope={scope} observedKeys={observed} removedKeys={removed}"
    )
    return observed, removed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--scope", required=True)
    parser.add_argument("--best-effort", action="store_true")
    args = parser.parse_args()
    _validate(args.redis_url, args.scope)
    if os.environ.get(_EPHEMERAL_REDIS_ENV) == "true":
        print(
            "skipped Redis test scope cleanup for ephemeral service "
            f"scope={args.scope}"
        )
        return 0
    try:
        cleanup(args.redis_url, args.scope)
    except Exception as error:
        if not args.best_effort:
            raise
        print(
            f"Redis test scope cleanup failed scope={args.scope}: {error}",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
