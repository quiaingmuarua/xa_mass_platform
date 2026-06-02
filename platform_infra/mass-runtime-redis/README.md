# mass-runtime-redis

Status: current Redis runtime implementation for embedded/local opt-in use; not
the default verified runtime mainline.

## Role

- owns the Redis-backed `TaskWorkRuntime` implementation
- owns the first-slice Redis-backed `WorkerRegistry` implementation
- keeps Redis queue/lease/counter ownership under `platform_infra` instead of
  leaking back into engine or server shells
- fixes Redis runtime keyspace/index ownership inside this module

## Current Truth

- the active verified runtime mainline is still `mass-runtime-memory`
- this module now provides a real Redis-backed `TaskWorkRuntime`
- this module now provides a contract-tested Redis-backed `WorkerRegistry`
  foundation using group-partitioned worker slot hashes and route buckets
- dev/server shells can opt into it explicitly without changing engine constructors
- this module is intentionally not the bootstrap default
- the intended Redis keyspace and hot-path index model lives in
  [REDIS_RUNTIME_BASELINE.md](./REDIS_RUNTIME_BASELINE.md)
- current implementation keeps queue/lease/result hot-path mutations inside
  Redis-scripted atomic operations for `enqueue`, `claimReady`, `applyResult`,
  `pollExpiredLeases`, and `discardTask`
- bounded delayed promotion and stats/query reads still use straightforward
  Redis commands around that hot-path truth; fine-grained optimization is not a
  second runtime contract
- Redis `WorkerRegistry` uses `WATCH` / `MULTI` / `EXEC` over the group-local
  slot hash for first-slice reserve, confirm, release, final, gate, and lease
  mutations; broader server/runtime switching and finer-grained Lua mutation are
  outside the current implementation
- Redis and memory worker registries share the same runtime-api route-bucket
  default policy; workers with approved route attributes are indexed into both
  `default` and attribute buckets.

## Guardrails

- do not add scan-heavy recovery or observability semantics here
- preserve `TaskWorkRuntime` method-level queue/lease/result semantics
- preserve `WorkerRegistry` group-partitioned key ownership; do not introduce
  one global worker table or DB-row-style worker CRUD state here
- keep Redis key/index ownership behind this module rather than spreading Redis-specific logic across engine, sdk, or server
- do not silently replace the in-memory default; Redis remains explicit opt-in until broader runtime/perf verification says otherwise
