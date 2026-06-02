# XA Mass Platform Agent Handoff

Status: current repo-root agent handoff.

Fast entry only. Use module owner READMEs and `doc/` contracts for detail.

## 0. TL;DR

- XA Mass Platform is a general distributed task scheduling platform.
- Core primitives: `Task + Worker + Scheduling + Matching`.
- `Task`: shell, contract, intake window, runtime work, result, and terminal
  aggregate.
- `Worker`: execution identity plus WorkerGroup/node membership and scheduling
  facts.
- `Scheduling`: decides when work may enter, retry, pause, resume, or leave
  dispatch competition.
- `Matching`: selects eligible workers from group capability, scheduling
  evidence, reachability, runtime load/capacity facts, and policy rules.
- Kernel truth is currently split across:
  - `Task.contract` for runtime contract
  - `Task.intakeStatus` for intake-window truth
  - `TaskWorkRuntime` for ready/delayed/lease/counter truth
  - `TaskResultRuntime` for stable-final result rows and result-side barriers
- result convergence is runtime-first, but the lifecycle owner split must be
  verified from `doc/TASK_LIFECYCLE_BASELINE.md` plus current engine/runtime
  code rather than inferred from historical `TaskWorkRuntime` wording alone
- Transport is three explicit channels: task dispatch, result ingest, and system events.
- Runtime entry is SDK-first; server HTTP/UI surfaces provide a lightweight
  backend product shell and validation host without redefining kernel ownership.
- Infra truth is three-layered: control-plane storage, runtime state, and trace/audit stream.
- Core acceptance is `perf + concurrency + Boot-shell E2E`.

Current mainline execution path:

- `Task shell -> item append -> runtime enqueue -> scheduling eligibility -> matching and assignment -> transport dispatch -> result convergence -> task state`

## 0.1 Abstraction Test

This repo is not anti-abstraction. It is anti-fake abstraction.

- module-internal direct dependency is not a problem by itself
- add a new seam only when it creates a real owner boundary, protocol seam,
  lifecycle split, or external/default caller surface
- a same-module pass-through `bridge` / `facade` / `wrapper` / `adapter`
  that only forwards to an existing owner is usually noise, not architecture
- narrow surfaces are still required for hot paths, cross-module callers,
  startup/watchdog wiring, and stable external entry points
- if a new layer does not change who owns the decision, who may call it, or
  what lifecycle boundary it protects, it probably should not exist

Common misreads to avoid:

- `TaskManager` implementing multiple engine seams is current owner design, not
  proof that a second internal bridge layer is needed
- historical message/attempt vocabulary does not mean those names are still
  the current hot-path runtime owner shape in code
- refusing a new wrapper is not "less design"; it is often the design choice
  that keeps owner boundaries visible

## 1. First Read

For a new session, read only these before changing behavior:

1. [README.md](README.md)
2. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
3. [doc/TASK_LIFECYCLE_BASELINE.md](doc/TASK_LIFECYCLE_BASELINE.md)
4. [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md) when the change touches storage, runtime, audit, or observability placement
5. [xa-mass-trace/README.md](xa-mass-trace/README.md) when the change touches
   trace, lifecycle observability, or trace-observed integration testing

Then jump to the owning module README or owner contract. Use
[doc/README.md](doc/README.md) as the expanded reading map only when needed.

## 2. Trust Order

1. code
2. verified runtime behavior
3. this handoff
4. active owner docs and ledgers (`doc/*`, `transport/*`, `DEPRECATION_LEDGER.md`)
5. module README files
6. refactor inventories and older notes only after re-verification

Direction-doc rule:

- target-direction or roadmap docs may be used as north-star constraints to keep new work from drifting across intended owner boundaries
- they must not be cited as proof that the current implementation already behaves that way
- when a direction doc and current code disagree, describe the gap explicitly and keep implementation claims tied to code and verified behavior

## 3. Fast Routing

Start here based on the change:

- engine lifecycle, matching, assignment, result, or concurrency:
  [xa-mass-engine/README.md](xa-mass-engine/README.md)
- task lifecycle, result-side ownership, and runtime/public-result boundaries:
  [doc/TASK_LIFECYCLE_BASELINE.md](doc/TASK_LIFECYCLE_BASELINE.md)
- runtime queue/lease/counter ownership or storage/runtime/trace placement:
  [platform_infra/README.md](platform_infra/README.md),
  [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md)
- transport runtime or adapter work:
  [transport/AGENTS.md](transport/AGENTS.md)
- understanding the current testing system or deciding where a new test belongs:
  read [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md) first, especially
  `0. Fast Intent`; if the question is "what is the authoritative proof for
  this invariant?" or "where is the current proof gap?", read
  [doc/PROOF_REGISTRY.md](doc/PROOF_REGISTRY.md) next before jumping to the
  owning lane README or suite
- lifecycle/trace/E2E contracts:
  [doc/TASK_LIFECYCLE_BASELINE.md](doc/TASK_LIFECYCLE_BASELINE.md),
  [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md),
  [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md)
- trace operator CLI / local trace diagnosis:
  [xa-mass-trace/README.md](xa-mass-trace/README.md),
  [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md)
- perf/concurrency/core acceptance:
  [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md),
  [xa-mass-testing/README.md](xa-mass-testing/README.md),
  [xa-mass-engine/README.md](xa-mass-engine/README.md)
- startup/runtime verification:
  [xa-mass-testing/VERIFIED_RUNBOOK.md](xa-mass-testing/VERIFIED_RUNBOOK.md)
- HTTP/API contracts:
  [xa-mass-server/doc/INTERNAL_API_REFERENCE.md](xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- SDK/integrations boundary guard:
  [doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md](doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)
- active cross-module roadmap or decision work:
  [roadmap/README.md](roadmap/README.md)
- legacy/compatibility/deprecation work:
  [DEPRECATION_LEDGER.md](DEPRECATION_LEDGER.md)

## 4. Agent Contract

Hard rules:

- act as a codebase owner, not a passive executor; form an independent technical judgment from code and verified runtime behavior first, and argue before coding when a requested direction conflicts with mainline ownership, runtime truth, or boundary clarity
- within this repository, there is no compatibility obligation for superseded internal paths; update in-repo callers instead of preserving the old path, and never leave deprecated and replacement paths as two live tracks
- do not preserve the old path through adapters, aliases, wrappers, fallbacks, rename-only relabeling, or phase markers; outside genuine new-feature work, determine whether the existing path should be replaced, converged, or removed
- `@Deprecated` is a temporary convergence marker only; never extend deprecated or legacy seams, and a failing test is never a reason to add a compatibility layer
- rename is justified when logic meaning changed or when an existing name materially misleads a hot-path mainline method; broad rename-only churn is not cheap
- if code changes a documented contract, ownership rule, or mainline workflow assumption, update the owning doc in the same change

Planning rule for multi-file or core changes:

- include scope, out of scope, files and symbols, alternative considered, costs,
  test impact with classifications, risk, and verification
- if reality materially diverges from the approved plan, stop and report before continuing

## 5. Highest-Priority Guardrails

- do not let transport-specific shapes redefine the kernel
- `engine` is a runtime kernel, not a CRUD backend module
- `Task.sharedConfig` plus runtime item payload or `payloadRef` are the generic payload boundaries
- `target` is only a conventional key inside the runtime item payload, not a model field
- task shell create enters through `POST /api/v1/tasks`, and work-item ingest is explicit through `POST /api/v1/tasks/{taskId}/items`
- `eventCode` is business/intake/runtime evidence; scheduling candidate truth
  is explicit `workerGroupId` / `workerGroupIds`
- do not add scan-heavy observability or reconciliation loops to hot paths
- trace and query concerns must not reverse-drive runtime ownership or mainline lifecycle design
- bias transport and lifecycle writes toward idempotent operations and retry safety
- for SDK or integrations changes, read
  [doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md](doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)
  before adding dependencies, DTOs, samples, worker-pack capability paths, or
  server bootstrap behavior

## 6. Working Defaults

- verify the current code path before changing behavior
- apply the abstraction test above; do not introduce `bridge` / `facade` / `wrapper` / `adapter` shells without a real owner boundary, protocol seam, lifecycle split, or concrete replacement need
- judge refactors by visibility, owner clarity, dependency surface, and whether the mainline becomes easier to reason about; a large internal orchestrator is acceptable when ownership stays explicit and splitting it would only fragment the mainline
- prefer logs, traces, and bounded diagnostics over model-coupled realtime observability
- prefer E2E or integration coverage for lifecycle changes
- when lifecycle semantics change, update
  [doc/TASK_LIFECYCLE_BASELINE.md](doc/TASK_LIFECYCLE_BASELINE.md),
  [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), and
  [doc/TESTING_INDEX.md](doc/TESTING_INDEX.md) together
- keep docs concise and current; delete stale notes instead of preserving parallel narratives
- keep module-owned docs inside the owning module; `doc/` is for global contracts, constraints, indexes, and runbooks
- do not add new root directories unless they are cross-module entry points
  linked from this handoff or the root README; otherwise put the material under
  the owning module or archive it
- do not document target state as already implemented
- do not recreate removed archive/v2 code

## 7. Documentation Governance

Hard rules for new or updated docs:

- new cross-module roadmap, inventory, decision, or direction records go under
  [roadmap/](roadmap/), not under global `doc/`
- when a roadmap is complete, run a residue scan, move still-current facts into
  the owning README/baseline, then archive the completed record under
  `doc/archive/<owner>/YYYY-MM-DD_NAME.md`
- module-local implementation truth belongs in the owning module README,
  module `doc/README.md`, or owner baseline; do not promote it into global
  `doc/` unless it is a cross-module contract or constraint
- root [README.md](README.md) is only for current facts, entry lanes, and
  top-level directory rationale; do not grow it into a roadmap or design log
- [architecture/](architecture/README.md) is human-facing explanation and
  onboarding material, not implementation truth or acceptance proof
- SDK, public-contract, or integrations boundary changes must update
  [sdk/README.md](sdk/README.md) and
  [integrations/README.md](integrations/README.md) in the same change; update
  the external SDK quickstart and boundary guard when caller behavior or
  dependency rules change
