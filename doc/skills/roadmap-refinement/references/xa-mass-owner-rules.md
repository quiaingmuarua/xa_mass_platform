# XA Mass Platform Owner Rules

Use only for XA Mass Platform roadmaps, or when the current repo explicitly
documents the same owner split. This file is a fast local reference; current
repo `AGENTS.md`, module READMEs/contracts, code, and verified behavior win if
they disagree with this file.

## Current Owner Split

- Engine owns task lifecycle, task-side scheduling intent, assignment
  orchestration, allocation budget, dispatch binding, result convergence,
  retry, terminal policy, and task completion / assignment-release evidence.
  Engine must not select workers by transport implementation identifiers such as
  adapter id, adapter mailbox key, route key, endpoint lease id, connection id,
  or session handle.
- Worker runtime owns worker declaration, WorkerGroup membership, candidate
  acquisition, scheduling evidence projection, admission, dispatch gates,
  worker report projection, worker-fact predicate/ranking mechanics behind
  `WorkerSelectionRuntime`, and the final dispatch eligibility decision.
- WorkerGroup owns capability declaration and scheduling entry boundary.
  Workers own execution identity plus attributes/load/state evidence; worker
  rows do not self-declare project/event capability truth.
- Transport has three explicit channels: assigned task delivery, result ingress,
  and system events. Assigned delivery is a best-effort delivery executor, not
  task reliability truth.
- Transport owns transport-neutral queues, handoff, dispatch outcome,
  result-ingress mechanics, protocol/session evidence, and endpoint lease
  evidence as transport-local delivery facts. Endpoint lease may remain useful
  for diagnostics, currentness checks, or narrow freshness evidence; it must not
  become worker scheduling truth or producer-side selected-worker target
  resolution.
- Concrete adapters own final-hop protocol IO, local session/pull-buffer lookup,
  frame/request parsing, adapter-local currentness checks, and adapter-local
  diagnostics. Adapters must not choose workers or open worker schedulability.
- Storage modules implement persistence/runtime adapters. They may own storage
  contracts only when the stored data is not a higher-level runtime/domain
  contract.
- Server and SDK own assembly, bootstrap, admin API, product shell surfaces, and
  integration bridges between owners. They may bridge signals, but should not
  redefine the runtime owner truth.
- Trace/archive owns durable history and analytics evidence, not hot-path
  runtime truth.
- Lifecycle state is not owner truth by default. Promote lifecycle/status fields
  only after the mechanism, writer, consumer, failure semantics, migration or
  shutdown behavior, and proof are named. Before that, classify them as
  projection, evidence, diagnostics, or residue.

## Dispatch Eligibility Rule

Use this invariant when reviewing worker-runtime / transport / engine
eligibility roadmaps:

```text
Negative evidence may close dispatch eligibility immediately as best-effort protection.
Positive evidence may only request worker-runtime recheck.
Only worker-runtime may reopen dispatch eligibility after validating worker declaration,
slot, group membership, gates, holds, capacity, and recovery mode.
```

Implications:

- Transport/adapters may produce negative evidence only through a narrow
  integration bridge or negative-only port; they must not receive
  `WorkerDispatchGateRuntime` or any clear-capable worker-runtime surface.
- Stale session token / replaced channel disconnect must not block worker
  dispatch. Only adapter-confirmed current session loss may emit transport
  disconnect evidence.
- Connected/heartbeat/freshness must not directly make a worker schedulable.
  If transport freshness is used, it must be point-read evidence with no worker
  list, wakeup, adapter mailbox, endpoint lease, session token, connection id,
  route key, or session handle exposure.
- Engine task completion / assignment release may submit release evidence to
  worker-runtime occupancy/admission owners, but it must not directly clear
  unrelated block sources or dispatch gates.
- `WorkerSelectionOwner` must not treat transport-derived reachability as an
  independent scheduling gate. Candidate exclusion should come from
  worker-runtime registry/gate/admission projections.

## Transport Delivery Boundary

- By the time work reaches transport, Scheduling Plane has selected a concrete
  worker. Transport carries that value as `selectedWorkerId`, a delivery
  constraint, not scheduling truth or lifecycle truth.
- Assigned dispatch queues by opaque adapter mailbox / transport-owned delivery
  lane chosen before transport enqueue. Concrete adapters demux
  `selectedWorkerId` to local sessions, channels, or pull buffers.
- Transport route keys, endpoint leases, connection ids, and session handles are
  not assigned-dispatch routing contracts. If retained, they are transport-local
  evidence, diagnostics, or protocol/session currentness facts.
- Known offer rejection, unavailable mailbox, missing endpoint, corrupt dispatch
  input, or adapter final-hop failure should produce dispatch outcome/failure
  evidence. Accepted work with no later result falls back to engine-owned task
  attempt timeout, retry, reassignment, and compensation.

## Scheduling Plane Rules

- Scheduling Plane work must preserve strategy/mechanism separation. Task-side
  strategy owns resolved scheduling intent and limits; worker-runtime selection
  owns candidate acquisition, worker-fact predicates, ranking, reservation,
  locks, live worker evidence, admission, selected handles, and selected-worker
  accounting behind the minimal selection contract.
- A single computed default strategy is acceptable. Do not start by adding a
  catalog, binding table, plug-in framework, or public policy configuration
  until at least two concrete variants, caller-visible cost, storage owner,
  runtime consumer, and proof are named.
- `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy` are engine-facing value contracts. They are
  not storage truth, not trace truth, and not proof that public policy products
  exist.
- `SchedulingPolicyCatalog` and `ProjectSchedulingBinding` are target product
  boundaries until a successor decision proves the caller, cost, owner, and
  binding subject.
- `RuntimeWorkerSelection` is a first-class Scheduling Plane owner. It must not
  be collapsed into worker scheduling policy or rule DSL.
- Trace, assignment records, and diagnostics may prove a Scheduling Plane
  decision, but they must not become the source of scheduling truth.
- Read models, snapshots, diagnostics, and compatibility DTOs must not become
  task lifecycle, scheduling, delivery, retry, result, or terminal truth.
- In-repo compatibility is not a requirement during convergence. Replace
  callers and remove old paths instead of preserving aliases, fallbacks, or
  parallel fat models.

Apply the repo handoff and module README/CONTRACTS files first. If those files
contradict this reference, report the gap and follow current code plus active
repo contracts.

## XA Mass Roadmap Discovery

Roadmaps and direction docs are distributed across the repo. Do not only scan
the current roadmap directory.

Start with:

- root `AGENTS.md`
- `doc/README.md`
- owning module `README.md` and `CONTRACTS.md`
- `xa-mass-engine/doc/roadmap/`
- `doc/`

Use `doc/archive/` only as historical context unless an active doc points to
it. For stale status checks, compare the roadmap `Status:` line with current
code, architecture guards, focused tests, and recent commits.

Common active boundary tracks may include:

- worker runtime extraction and API slimming
- worker match upgrade
- rule boundary convergence
- projection boundary convergence
- task/worker runtime-history boundary
- Java external SDK and integration bootstrap roadmaps
