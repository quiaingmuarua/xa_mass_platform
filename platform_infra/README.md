# Platform Infra

Status: shared platform infrastructure module family.

Current phase-1 scope:

- `mass-queue-primitives`
- `mass-runtime-api`
- `mass-runtime-memory`
- `mass-runtime-redis`
- `mass-storage-api`
- `mass-storage-memory`
- `mass-storage-jdbc`

These modules host platform-level runtime semantics and implementations that are
shared by engine, transport, server, and test shells. They do not own business
workflow, task strategy, or transport-specific protocol behavior.

Current truth for this conservative first slice:

- `mass-queue-primitives` owns narrow keyed queue/blocking-poll/backpressure mechanics shared by runtime modules without redefining task or transport semantics
- `mass-runtime-api` owns the shared `TaskWorkRuntime` contract and related value types
- `mass-runtime-memory` owns the current in-memory runtime implementation and its focused tests
- `mass-runtime-redis` now owns the Redis runtime keyspace/index baseline and remains outside the verified runtime path
- `mass-storage-api` owns shared task/worker/rule storage contracts plus the storage-adjacent rule types referenced by those contracts
- `mass-storage-memory` owns in-memory control-plane submitter storage used by the current embedded SDK/server path
- `mass-storage-jdbc` owns the JDBC control-plane storage implementation plus H2/PostgreSQL dialect wiring and migrations
- `xa-mass-engine` now depends only on the runtime contract; default memory-runtime assembly is lifted into sdk/server/bootstrap and explicit test harnesses
- `xa-mass-engine` still owns the in-memory/placeholder storage implementations and factory wiring for now, but the shared storage contracts no longer live under the engine package root

Boundary to keep stable:

- runtime modules own queue, lease, delayed, expiry, counter, and backpressure truth
- storage modules own durable control-plane truth
- high-volume task-message detail and attempt/event history belong in trace or async audit/export sinks, not in the control-plane JDBC path
