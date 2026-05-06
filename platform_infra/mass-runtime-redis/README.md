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
- current implementation keeps one runtime-scoped Redis mutex instead of Lua-scripted fine-grained claim/apply paths; this is current implementation truth, not the target hot-path shape

## Guardrails

- do not add scan-heavy recovery or observability semantics here
- preserve `TaskWorkRuntime` method-level queue/lease/result semantics
- keep Redis key/index ownership behind this module rather than spreading Redis-specific logic across engine, sdk, or server
- do not silently replace the in-memory default; Redis remains explicit opt-in until broader runtime/perf verification says otherwise
