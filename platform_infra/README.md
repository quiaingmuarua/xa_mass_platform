# Platform Infra

Status: shared platform infrastructure module family.

Current module family:

- `mass-queue-primitives`
- `../xa-mass-kernel-spi` (repo-level kernel contract module)
- `mass-runtime-api`
- `mass-runtime-memory`
- `mass-task-runtime-memory`
- `mass-task-runtime-redis`
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

Current boundary shorthand:

- SQLite is the preferred lightweight direction for control-plane storage only:
  project, rule, catalog, credential, submitter, and explicit environment seed
  metadata. It must not become the runtime queue/lease/heartbeat/result store.
- Redis remains the cross-process runtime-truth direction for queue, lease,
  counter, worker presence, dispatch handoff, and result ingress state. Redis
  data may be exported for offline analysis, but analysis output must not
  reverse-drive runtime truth.
- Trace/audit DB materialization is a trace-owned future path, likely fed by a
  trace queue or sink. It is lower priority than the current worker onboarding
  work and does not redefine control-plane storage or runtime ownership.

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
- `mass-runtime-api` owns shared worker-runtime SPI only:
  `WorkerRegistry` / `WorkerSlot`, low-level worker registry primitives, and
  the worker-runtime-owned `WorkerScoreBandSlotRuntime` score/meta
  state-machine contract used by memory and Redis implementations. Old
  `TaskWorkRuntime` / `TaskResultRuntime` contracts have been removed from this
  module; task item scheduling/runtime truth now belongs to
  `../xa-mass-task-runtime` and its adapter modules.
- `mass-runtime-memory` owns the in-memory worker-runtime SPI implementations
  used by the embedded default path and focused worker-runtime tests.
- `mass-task-runtime-memory` owns the first in-memory implementation of the
  new `xa-mass-task-runtime` public ports. It is a contract-proof adapter, not
  the semantic owner and not the current embedded default path.
- `mass-task-runtime-redis` owns the first Redis implementation of the new
  `xa-mass-task-runtime` public ports. It keeps Redis keyspace, Lua scripts,
  and codec details private to the adapter module.
- `mass-runtime-redis` owns Redis-backed worker-runtime SPI implementations and
  shared Redis queue primitives. It no longer owns task item scheduling/runtime
  implementations; Redis task-runtime state lives in `mass-task-runtime-redis`.
- `../xa-mass-kernel-spi` owns the kernel-facing task shell ports and
  worker-matching rule value contracts consumed by engine/runtime callers
- `mass-storage-api` owns persistence/control-plane storage contracts such as
  `TaskShellStore` and `RuleStorage`; it may depend on kernel SPI value types
  but it is not a kernel-facing API
- `xa-mass-worker-runtime` owns `WorkerDeclarationStore` /
  `WorkerDeclarationRecord`; storage modules implement that port as worker
  declaration adapters when needed
- `mass-storage-memory` owns in-memory control-plane task shell, worker
  declaration adapter, and rule-definition storage; its task shell adapter also
  implements the kernel-facing task shell ports;
  rule evaluator registry and the default QLExpress rule evaluator are now
  engine rule-runtime assembly concerns
- `mass-storage-jdbc` owns the JDBC control-plane storage implementation plus
  current H2/PostgreSQL dialect wiring and residue-recovery helpers; engine
  manager assembly stays outside this module. Do not infer a product migration
  commitment from local schema helpers: current new-environment setup should
  prefer explicit seed/import and may use SQLite as the lightweight
  control-plane DB direction.
- worker registry slot state, score-band worker slot state, dispatch
  availability, exclusive leases, and heartbeat cleanup are runtime state, not
  control-plane DB CRUD state. Pre-score-band candidate buckets and candidate
  sampling are retired from the production worker acquire path. The score-band
  runtime shape is
  `score:{homeBucketId}` plus `meta:{homeBucketId}`; transition evidence and
  diagnostics must not become writable current-state owners. Higher level
  worker resource/evidence contracts such as
  `WorkerRegistrySnapshot`, `AdapterNodeRecord`, and `NodeGroupBindingRecord`
  belong to `xa-mass-worker-runtime`; if they need durable history or operator
  query, emit trace/events and let an async pipeline persist them outside the
  hot path
- `mass-trace-sink` owns the canonical `ExecutionEvent` model, event-name enum, and default asynchronous JSONL sink implementation
- `xa-mass-engine` consumes runtime and kernel SPI contracts directly; it must
  not declare production dependencies on `mass-storage-*`
- `xa-mass-engine` may keep storage-memory as test fixture residue only; engine
  main sources must not import storage packages

Current implementation drift agents must keep explicit:

- `mass-storage-memory` contains `InMemoryRuleStorage` for rule definitions;
  it must not grow evaluator lifecycle ownership back into storage
- `mass-storage-jdbc` currently persists control-plane task/rule truth and still exposes `JdbcStorageRuntime` as a convenience bundle for migrations and storage adapters; it returns storage contracts to outer layers, but that bundle is still convergence work rather than a long-term product extension point
- `JdbcTaskShellStore` is a task-shell control-plane store; server review/export
  materialization owns any server-local review rows outside engine runtime
  assembly
- SDK/server assembly wires storage implementations into kernel SPI task-shell
  ports; engine/runtime assembly does not consume `TaskShellStore` directly
- `xa-mass-engine` still uses `mass-storage-memory` from tests, but its main sources no longer import that package directly; keep the dependency scoped to tests unless a verified mainline caller requires more

Boundary to keep stable:

- task-runtime modules own task item queue, lease, retry, final-result,
  progress, and discard truth; worker-runtime modules own worker registry,
  worker locks, dispatch gates, reachability, and worker attributes; shared
  infra queue modules own only generic queue mechanics
- storage modules own durable control-plane truth
- worker runtime registry, worker locks, dispatch gates, reachability, and
  worker attributes are runtime truth; DB query needs should be fed through
  trace/audit ingestion, not worker CRUD storage
- worker capability candidate indexes belong to engine/runtime owners, not
  `WorkerDeclarationStore`; storage adapters must not expose supported-project
  or supported-event worker lookup APIs as scheduling shortcuts
- high-volume task-message detail and attempt/event history belong in trace or async audit/export sinks, not in the control-plane JDBC path
- runtime residue and server-local review rows remain temporary/debug or
  operator-facing material even though `mass-trace-sink` is landed; do not
  redefine that residue as control-plane truth, lifecycle truth, or public
  result-read truth

When docs and code disagree inside `platform_infra/`, preserve the disagreement
explicitly in the owner README as "current implementation drift" rather than
silently rewriting the doc to the desired target state.
