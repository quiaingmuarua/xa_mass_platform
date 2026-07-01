# mass-runtime-redis

Status: current Redis worker-runtime SPI and shared queue implementation module.

## Role

- owns the first-slice Redis-backed `WorkerRegistry` implementation
- owns the Redis-backed `WorkerScoreBandSlotRuntime` implementation
- owns shared Redis keyed queue primitives used by runtime modules
- keeps Redis worker and queue keyspace ownership under `platform_infra`
  instead of leaking back into engine or server shells

## Current Truth

- old Redis `TaskWorkRuntime` / `TaskResultRuntime` implementations have been
  removed after the task-runtime serving-lane cutover
- Redis task-runtime state now lives in `../mass-task-runtime-redis`
- this module now provides a contract-tested Redis-backed `WorkerRegistry`
  foundation using group-partitioned worker slot hashes, group-local heartbeat
  deadline indexes, and candidate buckets
- dev/server shells can opt into Redis worker-runtime SPI and Redis queue
  primitives explicitly without changing engine constructors
- the intended Redis keyspace and hot-path index model lives in
  [REDIS_RUNTIME_BASELINE.md](./REDIS_RUNTIME_BASELINE.md)
- Redis `WorkerRegistry` uses `WATCH` / `MULTI` / `EXEC` over the group-local
  slot hash for first-slice reserve, confirm, release, final, gate, and lease
  mutations; broader server/runtime switching and finer-grained Lua mutation are
  outside the current implementation
- Redis and memory worker registries share the same runtime-api candidate-bucket
  default policy; workers with approved route attributes are indexed into both
  `default` and attribute buckets. The policy owns attribute dimensions and
  declares max bucket fan-out; Redis executes that policy without interpreting
  worker attribute names. Candidate buckets derived from route attributes are
  source hints, not readiness, occupancy, lifecycle eligibility, or policy
  truth.

## Guardrails

- do not add scan-heavy recovery or observability semantics here
- do not reintroduce task item scheduling/runtime ownership here
- preserve `WorkerRegistry` group-partitioned key ownership; do not introduce
  one global worker table or DB-row-style worker CRUD state here
- keep Redis key/index ownership behind this module rather than spreading Redis-specific logic across engine, sdk, or server
