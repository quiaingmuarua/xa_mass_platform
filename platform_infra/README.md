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

Read this file as the directory index only. For storage work, always follow it
with the owning module README under `mass-storage-memory/` or
`mass-storage-jdbc/` before changing code.

Current truth for this conservative first slice:

- `mass-queue-primitives` owns narrow keyed queue/blocking-poll/backpressure mechanics shared by runtime modules without redefining task or transport semantics
- `mass-runtime-api` owns the shared `TaskWorkRuntime` contract and related value types
- `mass-runtime-memory` owns the current in-memory runtime implementation and its focused tests
- `mass-runtime-redis` now owns the Redis runtime keyspace/index baseline and remains outside the verified runtime path
- `mass-storage-api` owns shared task/worker/rule storage contracts plus the storage-adjacent rule types referenced by those contracts
- `mass-storage-memory` owns in-memory control-plane task/worker/rule/submitter storage plus the default QLExpress rule evaluator used by the current embedded SDK/server path and focused tests
- `mass-storage-jdbc` owns the JDBC control-plane storage implementation plus H2/PostgreSQL dialect wiring, migrations, and residue-recovery helpers; engine manager assembly stays outside this module
- `xa-mass-engine` consumes the runtime contract directly and currently also declares storage-contract plus in-memory storage dependencies in the reactor; do not summarize that as "runtime only" without re-checking the root `pom.xml`
- `xa-mass-engine` now depends on storage contracts and infra-owned in-memory storage implementations; engine no longer carries Redis storage placeholder classes or shared in-memory storage implementations under its package root

Current implementation drift agents must keep explicit:

- `mass-storage-memory` currently contains `InMemoryRuleStorage`, `QLExpressRuleEvaluator`, and `InMemorySubmitterRegistry` in addition to task/worker storage implementations; this is current code truth, not proof that those ownership boundaries are final
- `mass-storage-jdbc` currently persists control-plane truth and still exposes `JdbcStorageRuntime` as a convenience bundle for migrations, storage adapters, and residue recovery; it now returns storage contracts to outer layers, but that bundle is still convergence work rather than a long-term product extension point
- `JdbcTaskStorage` and `JdbcWorkerStorage` currently keep process-local compatibility projections in memory; that reuse is current implementation fact, not a signal to deepen cross-backend coupling
- `xa-mass-engine` currently still carries a compile-time dependency on `mass-storage-memory`; verify call sites before assuming that dependency is semantically required

Boundary to keep stable:

- runtime modules own queue, lease, delayed, expiry, counter, and backpressure truth
- storage modules own durable control-plane truth
- high-volume task-message detail and attempt/event history belong in trace or async audit/export sinks, not in the control-plane JDBC path

When docs and code disagree inside `platform_infra/`, preserve the disagreement
explicitly in the owner README as "current implementation drift" rather than
silently rewriting the doc to the desired target state.
