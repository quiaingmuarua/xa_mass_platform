# Platform Infra

Status: shared platform infrastructure module family.

Current module family:

- `mass-queue-primitives`
- `mass-runtime-api`
- `mass-runtime-memory`
- `mass-runtime-redis`
- `mass-storage-api`
- `mass-storage-memory`
- `mass-storage-jdbc`
- `mass-trace-sink`

These modules host platform-level runtime semantics and implementations that are
shared by engine, transport, server, and test shells. They do not own business
workflow, task strategy, or transport-specific protocol behavior.

Think about infra through three truth layers:

1. control-plane storage
2. runtime state
3. trace / audit stream

This directory contains modules for all three layers. The trace layer is
currently represented by `mass-trace-sink`, which owns the canonical execution
event model and default JSONL sink. That does not make trace a lifecycle or
runtime correctness owner.

Use [../doc/INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md) as the dense
placement matrix. This README stays an index and owner-summary layer, not the
full decision table.

Read this file as the directory index only. For storage work, always follow it
with the owning module README under `mass-storage-memory/` or
`mass-storage-jdbc/` before changing code.

Current truth for this conservative first slice:

- `mass-queue-primitives` owns narrow keyed queue/blocking-poll/backpressure mechanics shared by runtime modules without redefining task or transport semantics
- `mass-runtime-api` owns the shared runtime contracts and related value types:
  `TaskWorkRuntime` for queue/lease/retry/apply truth and
  `TaskResultRuntime` for stable-final result-read truth, repair staging,
  task-local result sequence, and barriers; it also owns the shared
  `WorkerRegistry` / `WorkerSlot` contract and low-level worker registry
  primitives used by memory and Redis worker registry implementations. Higher
  level worker-plane contracts live in `xa-mass-worker-runtime`
- `mass-runtime-memory` owns the current in-memory runtime implementations and
  their focused tests
- `mass-runtime-redis` now owns the Redis-backed runtime implementations plus
  their keyspace/index baseline; it remains an explicit opt-in path outside the
  verified default runtime mainline
- `mass-storage-api` owns shared `TaskShellStore`, rule-definition storage
  contracts, plus the bounded `TaskDetailStore` compatibility-projection seam
  and the storage-adjacent rule types referenced by those contracts
- `xa-mass-worker-runtime` owns `WorkerDeclarationStore` /
  `WorkerDeclarationRecord`; storage modules implement that port as worker
  declaration adapters when needed
- `mass-storage-memory` owns in-memory control-plane task shell, worker
  declaration adapter, and rule-definition storage;
  rule evaluator registry and the default QLExpress rule evaluator are now
  engine rule-runtime assembly concerns
- `mass-storage-jdbc` owns the JDBC control-plane storage implementation plus H2/PostgreSQL dialect wiring, migrations, and residue-recovery helpers; engine manager assembly stays outside this module
- worker registry slot state, dispatch availability, route buckets, and
  candidate sampling are runtime state, not control-plane DB CRUD state. Higher
  level worker resource/candidate/evidence contracts such as
  `WorkerRegistrySnapshot`, `AdapterNodeRecord`, and `NodeGroupBindingRecord`
  belong to `xa-mass-worker-runtime`; if they need durable history or operator
  query, emit trace/events and let an async pipeline persist them outside the
  hot path
- `mass-trace-sink` owns the canonical `ExecutionEvent` model, event-name enum, and default asynchronous JSONL sink implementation
- `xa-mass-engine` consumes the runtime contract directly and currently also declares storage-contract plus in-memory storage dependencies in the reactor; do not summarize that as "runtime only" without re-checking the root `pom.xml`
- `xa-mass-engine` now depends on storage contracts and infra-owned in-memory storage implementations; engine no longer carries Redis storage placeholder classes or shared in-memory storage implementations under its package root

Current implementation drift agents must keep explicit:

- `mass-storage-memory` contains `InMemoryRuleStorage` for rule definitions;
  it must not grow evaluator lifecycle ownership back into storage
- `mass-storage-jdbc` currently persists control-plane task/rule truth and still exposes `JdbcStorageRuntime` as a convenience bundle for migrations and storage adapters; it returns storage contracts to outer layers, but that bundle is still convergence work rather than a long-term product extension point
- `JdbcTaskShellStore` keeps JDBC-local process-local compatibility projections for task-message residue; worker runtime registry state is intentionally not exposed through JDBC storage
- engine/runtime assembly now wires `TaskShellStore` and `TaskDetailStore` explicitly instead of relying on an implicit "task shell store also means detail store" fallback
- `xa-mass-engine` still uses `mass-storage-memory` from tests, but its main sources no longer import that package directly; keep the dependency scoped to tests unless a verified mainline caller requires more

Boundary to keep stable:

- runtime modules own queue, lease, delayed, expiry, counter, and backpressure truth
- storage modules own durable control-plane truth
- worker runtime registry, worker locks, dispatch gates, reachability, and
  worker attributes are runtime truth; DB query needs should be fed through
  trace/audit ingestion, not worker CRUD storage
- worker capability candidate indexes belong to engine/runtime owners, not
  `WorkerDeclarationStore`; storage adapters must not expose supported-project
  or supported-event worker lookup APIs as scheduling shortcuts
- high-volume task-message detail and attempt/event history belong in trace or async audit/export sinks, not in the control-plane JDBC path
- bounded compatibility projection and runtime residue remain temporary/debug
  material even though `mass-trace-sink` is landed; do not redefine that
  residue as control-plane truth, lifecycle truth, or public result-read truth

When docs and code disagree inside `platform_infra/`, preserve the disagreement
explicitly in the owner README as "current implementation drift" rather than
silently rewriting the doc to the desired target state.
