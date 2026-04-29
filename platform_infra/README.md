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

Think about infra through three truth layers:

1. control-plane storage
2. runtime state
3. trace / audit stream

This directory currently contains modules for the first two layers. The third
layer is still mostly a contract and design direction rather than a landed
module family. That absence is not permission to promote trace-shaped data into
JDBC tables or hot runtime state.

Read this file as the directory index only. For storage work, always follow it
with the owning module README under `mass-storage-memory/` or
`mass-storage-jdbc/` before changing code.

Current truth for this conservative first slice:

- `mass-queue-primitives` owns narrow keyed queue/blocking-poll/backpressure mechanics shared by runtime modules without redefining task or transport semantics
- `mass-runtime-api` owns the shared `TaskWorkRuntime` contract and related value types
- `mass-runtime-memory` owns the current in-memory runtime implementation and its focused tests
- `mass-runtime-redis` now owns the Redis runtime keyspace/index baseline and remains outside the verified runtime path
- `mass-storage-api` owns shared task/worker/rule storage contracts plus the storage-adjacent rule types referenced by those contracts
- `mass-storage-memory` owns in-memory control-plane task/worker/rule storage plus the default QLExpress rule evaluator used by the current embedded SDK/server path and focused tests
- `mass-storage-jdbc` owns the JDBC control-plane storage implementation plus H2/PostgreSQL dialect wiring, migrations, and residue-recovery helpers; engine manager assembly stays outside this module
- `xa-mass-engine` consumes the runtime contract directly and currently also declares storage-contract plus in-memory storage dependencies in the reactor; do not summarize that as "runtime only" without re-checking the root `pom.xml`
- `xa-mass-engine` now depends on storage contracts and infra-owned in-memory storage implementations; engine no longer carries Redis storage placeholder classes or shared in-memory storage implementations under its package root

Current implementation drift agents must keep explicit:

- `mass-storage-memory` currently contains `InMemoryRuleStorage` and `QLExpressRuleEvaluator` in addition to task/worker storage implementations; this is current code truth, not proof that those ownership boundaries are final
- `mass-storage-jdbc` currently persists control-plane truth and still exposes `JdbcStorageRuntime` as a convenience bundle for migrations, storage adapters, and residue recovery; it now returns storage contracts to outer layers, but that bundle is still convergence work rather than a long-term product extension point
- `JdbcTaskStorage`, `JdbcWorkerStorage`, and `JdbcSubmitterRegistry` now each keep JDBC-local process-local compatibility projections for task-message, worker-runtime, and auth residue; those in-process projections are current implementation facts, not target architecture
- `xa-mass-engine` still uses `mass-storage-memory` from tests, but its main sources no longer import that package directly; keep the dependency scoped to tests unless a verified mainline caller requires more

Boundary to keep stable:

- runtime modules own queue, lease, delayed, expiry, counter, and backpressure truth
- storage modules own durable control-plane truth
- high-volume task-message detail and attempt/event history belong in trace or async audit/export sinks, not in the control-plane JDBC path
- when trace/audit sinks are not landed yet, keep trace-shaped detail in bounded runtime projections or logs only as temporary residue; do not redefine that residue as control-plane truth

When docs and code disagree inside `platform_infra/`, preserve the disagreement
explicitly in the owner README as "current implementation drift" rather than
silently rewriting the doc to the desired target state.
