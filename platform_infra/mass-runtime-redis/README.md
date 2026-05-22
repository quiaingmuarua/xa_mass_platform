# mass-runtime-redis

Status: current Redis runtime implementation for embedded/local opt-in use; not
the default verified runtime mainline.

## Role

- owns the Redis-backed `TaskWorkRuntime` implementation
- keeps Redis queue/lease/counter ownership under `platform_infra` instead of leaking back into engine or server shells
- fixes Redis runtime keyspace/index ownership before a real implementation is
  spread across other modules

## Current Truth

- the active verified runtime mainline is still `mass-runtime-memory`
- this module now provides a real Redis-backed `TaskWorkRuntime`
- dev/server shells can opt into it explicitly without changing engine constructors
- this module is intentionally not the bootstrap default
- the intended Redis keyspace and hot-path index model lives in
  [REDIS_RUNTIME_BASELINE.md](./REDIS_RUNTIME_BASELINE.md)
- current implementation keeps queue/lease/result hot-path mutations inside
  Redis-scripted atomic operations for `enqueue`, `claimReady`, `applyResult`,
  `pollExpiredLeases`, and `discardTask`
- bounded delayed promotion and stats/query reads still use straightforward
  Redis commands around that hot-path truth; further fine-grained optimization
  remains future work, not a second runtime contract

## Guardrails

- do not add scan-heavy recovery or observability semantics here
- preserve `TaskWorkRuntime` method-level queue/lease/result semantics
- keep Redis key/index ownership behind this module rather than spreading Redis-specific logic across engine, sdk, or server
- do not silently replace the in-memory default; Redis remains explicit opt-in until broader runtime/perf verification says otherwise
