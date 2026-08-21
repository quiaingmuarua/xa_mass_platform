# Kernel Core Design Workspace

Status: current clean-kernel mechanism oracle and Python executable
specification.

`kernel_design/` defines the target Kernel mechanisms independently from the
superseded Java platform. It is not a compatibility roadmap and does not imply
that every operation already has a JVM provider.

Cross-module ownership is defined by
[the repository architecture entrypoint](../README.md). This workspace
owns only Kernel mechanism truth.

## Trust Order

Use this order when surfaces disagree:

1. `executable_spec/` code and focused tests.
2. Verified Redis behavior.
3. Current owner mechanism documents under `doc/`.
4. This workspace README and `AGENTS.md`.
5. Historical tag material as failure-mode evidence only.

If executable behavior and a current mechanism document disagree, identify and
repair the drift. Do not silently bend one into the other.

## Current Baseline

The executable specification currently proves:

- Task, Worker and TaskItem owner contracts and independent score axes;
- Task and TaskItem record persistence with owner-local Redis shapes;
- WorkerGroup and Worker resource truth plus bounded property reads;
- `PRECOMPUTED_TASK_RULE` and `DIRECT_ITEM_RULE` Worker-acquisition profiles;
- Task admission, candidate warmup, Task dispatch and Result Routing;
- exact-observation score transitions and bounded Worker lease acquisition;
- Adapter-partitioned DeliveryCommand handoff and DeliveryReport ingestion;
- optional Adapter-route Worker serviceability discovery and score convergence;
- Kernel application assembly and the Python Runtime Server command boundary.

The authoritative implementation-status table lives in
[Kernel Core Scheduling](doc/scheduling/README.md). This README deliberately
does not maintain a second status matrix.

## Core Axioms

### Owners Maintain Truth

```text
Kernel mechanism moves owner state
Owner stores maintain truth
Policy chooses allowed transitions and bounded inputs
Transport carries already-decided evidence
```

Task metadata, Task score, TaskItem records, TaskItem score, Worker resources,
Worker score, DeliveryCommand and Result disposition remain separate owner
surfaces. A caller may orchestrate them, but it must not merge their truth.

### Score Is A Scheduling Coordinate

A score encodes the scheduling band, time coordinate and owner-specific suffix.
It is not a global lock, resource version or permission to mutate unrelated
records.

Ordinary resource writes do not acquire a score lease. Scheduling owners use
declared transitions, exact observed-score CAS and bounded leases only where
their mechanism requires them.

### Events Accelerate; Owner Scans Preserve Liveness

Delivery evidence and other events may reduce latency. Correctness must still
converge when a best-effort hint is lost, duplicated or reordered. Background
owner scans, exact rechecks and bounded recovery remain the liveness path.

### Task Types Select Acquisition Profiles

- `PRECOMPUTED_TASK_RULE` uses reusable Task-level Worker candidate computation.
- `DIRECT_ITEM_RULE` binds Worker choice to the individual TaskItem and does not
  require a warm candidate cache.

Both types use the same TaskItem identity, dispatch evidence, result routing
and Task close mechanisms. Task type does not create a second state machine.

### TaskItem Is The Runtime Unit

`TaskItem` remains the canonical unit from append to finality. Dispatch does not
create a second Work, WorkItem or Attempt aggregate. Claim and result evidence
are fenced through the owner score and delivery correlation contracts.

### Policy Is Not Kernel Truth

Kernel exposes conservative owner operations. Admission limits, fairness,
idle-disposition application, candidate policy and retry classification are policies
that call those operations. Policy richness does not justify broad cross-key or
cross-owner Kernel APIs.

## Scheduling Mainline

```text
Task admission
  -> optional PRECOMPUTED_TASK_RULE candidate warmup
  -> Task dispatch acquisition
  -> Worker validation and lease
  -> exact TaskItem claim
  -> DeliveryCommand append
  -> Worker Delivery
  -> DeliveryReport ingestion
  -> Result Routing
  -> retry, finality and Worker disposition
```

The scheduling planes are intentionally separate:

- Task score owns Task visibility, dispatch cadence, exact idle close and the
  Kernel-private idle park/unpark coordinate.
- Worker score owns scheduling eligibility and dispatch leases.
- TaskItem score owns due work, claim budget, retry and final promotion.
- Assignment Dispatch orchestrates bounded owner reads and transitions.
- Result Routing consumes delivery evidence and applies owner-local result
  policy.

Worker Delivery carries the already-assigned Command. It does not select a
Worker, claim an Item, mutate scheduling score or decide finality.

DIRECT_CALL is outside this mainline. It is a Server-owned, caller-targeted
use case that offers Worker Commands into the existing delivery mailbox
without selecting a Worker or entering Task scheduling and Result Routing.

Worker Serviceability is a separate optional Kernel policy. It scans only
explicitly configured WorkerGroups, asks the owning Adapter for bounded route
snapshots through a best-effort Runtime, and lets WorkerScoreCore fence any
resulting polarity/retry transition. It remains disabled when the optional
Kernel configuration is absent; when enabled, the existing Server consume and
result endpoints provide the Adapter bridge without owning score policy.

## Owner Contract Standard

Every new Kernel operation is a long-lived cost commitment. Prefer:

- caller-bounded identities;
- same-key aggregation;
- owner-local validation and mutation;
- explicit failure and stale semantics;
- opaque score values outside score owners;
- focused executable and real-Redis proof.

Do not add cross-key fan-out, global discovery, owner-spanning aggregation,
background coordination or mirrored DTOs merely for caller convenience. Such a
contract requires a named invariant, a worst-case bound, defined failure
semantics and rejection of cheaper caller composition.

## Document Map

Start with [the document index](doc/README.md), then use the owner path relevant
to the change:

- [Task Resource Model](doc/resource-model/task-resource-model.md)
- [Worker Resource Model](doc/resource-model/worker-resource-model.md)
- [Task Score](doc/scheduling/task-score-band-scheduling.md)
- [Worker Score](doc/scheduling/worker-score-band-scheduling.md)
- [TaskItem Score](doc/scheduling/task-item-score-band-scheduling.md)
- [Assignment Dispatch](doc/scheduling/assignment-dispatch-scheduling.md)
- [Task Dispatch](doc/scheduling/task-dispatch-pacer.md)
- [Result Routing](doc/scheduling/result-routing-scheduling.md)
- [Worker Delivery](doc/scheduling/worker-delivery-dispatch.md)
- [Worker Serviceability](doc/scheduling/worker-serviceability-scheduling.md)
- [Kernel Application Assembly](doc/kernel-application-assembly.md)

Redis documents describe current owner storage shapes; they are not public APIs
unless an owner contract explicitly says so.

## Runtime And JVM Boundaries

- `runtime_server/` exposes only the current Python Kernel command host.
- `kernel_jvm/` mirrors public contracts and selected Redis providers; it has no
  scheduling, Pacer or Kernel application lifecycle.
- `server_jvm/` composes current providers and may orchestrate use cases through
  owner contracts. It must not implement missing Kernel behavior locally.
- Transport modules receive already-targeted delivery evidence and do not read
  score or scheduling policy.

The future production-language Kernel runtime remains deferred. Incremental JVM
parity is added only for an explicit production caller and scoped parity proof.

## Verification

Deterministic executable specification:

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Redis-backed tests require `KERNEL_DESIGN_REDIS_URL`. Cross-process and JVM
proofs are listed in the repository [Proof Lanes](../TESTING.md).

Read [kernel_design/AGENTS.md](AGENTS.md) before changing a Kernel contract,
score encoding, Redis owner shape or executable-spec behavior.
