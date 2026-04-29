# mass-runtime-redis

Status: keyspace/schema baseline plus module scaffold; still not current verified
runtime behavior.

## Role

- reserved module for a future Redis-backed `TaskWorkRuntime` implementation
- keeps Redis queue/lease/counter ownership under `platform_infra` instead of leaking back into engine or server shells
- fixes Redis runtime keyspace/index ownership before a real implementation is
  wired

## Current Truth

- the active verified runtime mainline is still `mass-runtime-memory`
- sdk/server assembly already supports injected `TaskWorkRuntime`, so a future Redis implementation can plug in without changing engine constructors
- this module is intentionally not wired into bootstrap defaults yet
- the intended Redis keyspace and hot-path index model lives in
  [REDIS_RUNTIME_BASELINE.md](./REDIS_RUNTIME_BASELINE.md)

## Guardrails

- do not add table-scan style recovery semantics here
- preserve `TaskWorkRuntime` method-level queue/lease/result semantics
- keep Redis key/index ownership behind this module rather than spreading Redis-specific logic across engine, sdk, or server
- do not treat this module as runtime-ready until queue/lease operations are
  implemented and verified
