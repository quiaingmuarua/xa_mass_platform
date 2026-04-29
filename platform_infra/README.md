# Platform Infra

Status: shared platform infrastructure module family.

Current phase-1 scope:

- `mass-queue-primitives`
- `mass-runtime-api`
- `mass-runtime-memory`
- `mass-runtime-redis`

These modules host platform-level runtime semantics and implementations that are
shared by engine, transport, server, and test shells. They do not own business
workflow, task strategy, or transport-specific protocol behavior.

Current truth for this conservative first slice:

- `mass-queue-primitives` owns narrow keyed queue/blocking-poll/backpressure mechanics shared by runtime modules without redefining task or transport semantics
- `mass-runtime-api` owns the shared `TaskWorkRuntime` contract and related value types
- `mass-runtime-memory` owns the current in-memory runtime implementation and its focused tests
- `mass-runtime-redis` now owns the Redis runtime keyspace/index baseline and remains outside the verified runtime path
- `xa-mass-engine` now depends only on the runtime contract; default memory-runtime assembly is lifted into sdk/server/bootstrap and explicit test harnesses
- Redis runtime and storage extraction are intentionally out of scope for this phase
