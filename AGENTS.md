# XA Mass Platform Agent Handoff

Status: current repo-root agent handoff.

Fast entry only. Use module owner READMEs and `doc/` contracts for detail.

## 0. TL;DR

- XA Mass Platform is a general distributed task scheduling platform.
- Stable kernel: `Task / TaskMsg / TaskMsgAttempt / assignment / result / audit / terminal policy`.
- Transport is three explicit channels: task dispatch, result ingest, and system events.
- Runtime entry is SDK-first; HTTP pages and demo APIs are validation shells.
- Infra truth is three-layered: control-plane storage, runtime state, and trace/audit stream.
- Core acceptance is `perf + concurrency + Boot-shell E2E`.

Current mainline execution path:

- `Task shell -> item append -> runtime enqueue -> dispatch binder -> transport delivery view -> result convergence -> task state`

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
- stable kernel vocabulary such as `TaskMsg` / `TaskMsgAttempt` does not mean
  those names are still the current hot-path runtime owner shape in code
- refusing a new wrapper is not "less design"; it is often the design choice
  that keeps owner boundaries visible

## 1. First Read

For a new session, read only these before changing behavior:

1. [README.md](README.md)
2. [doc/AGENT_BASELINE.md](doc/AGENT_BASELINE.md)
3. [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md)
4. [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md) when the change touches storage, runtime, audit, or observability placement

Then jump to the owning module README or owner contract. Use
[doc/README.md](doc/README.md) as the expanded reading map only when needed.

## 2. Trust Order

1. code
2. verified runtime behavior
3. this handoff
4. active owner docs and ledgers (`doc/*`, `transport/*`, `DEPRECATION_LEDGER.md`)
5. module README files
6. refactor inventories and older notes only after re-verification

## 3. Fast Routing

Start here based on the change:

- engine lifecycle, matching, assignment, result, or concurrency:
  [xa-mass-engine/README.md](xa-mass-engine/README.md)
- runtime queue/lease/counter ownership or storage/runtime/trace placement:
  [platform_infra/README.md](platform_infra/README.md),
  [doc/INFRA_TRUTH_LAYERS.md](doc/INFRA_TRUTH_LAYERS.md),
  [doc/DB_STORAGE_PRINCIPLES.md](doc/DB_STORAGE_PRINCIPLES.md)
- transport runtime or adapter work:
  [transport/AGENTS.md](transport/AGENTS.md)
- lifecycle/trace/E2E contracts:
  [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md),
  [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md),
  [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md)
- perf/concurrency/core acceptance:
  [doc/TESTING_BASELINE.md](doc/TESTING_BASELINE.md),
  [xa-mass-testing/README.md](xa-mass-testing/README.md),
  [xa-mass-engine/README.md](xa-mass-engine/README.md)
- startup/runtime verification:
  [doc/VERIFIED_RUNBOOK.md](doc/VERIFIED_RUNBOOK.md)
- HTTP/API contracts:
  [doc/INTERNAL_API_REFERENCE.md](doc/INTERNAL_API_REFERENCE.md)
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
- `Task.sharedConfig` and `TaskMsg.input/output` are the generic payload boundaries
- `target` is only a conventional key inside `TaskMsg.input`, not a model field
- task shell create enters through `POST /api/v1/tasks`, and work-item ingest is explicit through `POST /api/v1/tasks/{taskId}/items`
- `eventCode` is globally unique capability identity
- do not add scan-heavy observability or reconciliation loops to hot paths
- trace and query concerns must not reverse-drive runtime ownership or mainline lifecycle design
- bias transport and lifecycle writes toward idempotent operations and retry safety

## 6. Working Defaults

- verify the current code path before changing behavior
- apply the abstraction test above; do not introduce `bridge` / `facade` / `wrapper` / `adapter` shells without a real owner boundary, protocol seam, lifecycle split, or concrete replacement need
- judge refactors by visibility, owner clarity, dependency surface, and whether the mainline becomes easier to reason about; a large internal orchestrator is acceptable when ownership stays explicit and splitting it would only fragment the mainline
- prefer logs, traces, and bounded diagnostics over model-coupled realtime observability
- prefer E2E or integration coverage for lifecycle changes
- when lifecycle semantics change, update
  [doc/STATE_MACHINE_BASELINE.md](doc/STATE_MACHINE_BASELINE.md),
  [doc/TRACE_CONTRACT.md](doc/TRACE_CONTRACT.md), and
  [doc/E2E_BASELINE.md](doc/E2E_BASELINE.md) together
- keep docs concise and current; delete stale notes instead of preserving parallel narratives
- keep module-owned docs inside the owning module; `doc/` is for global contracts, constraints, indexes, and runbooks
- do not document target state as already implemented
- do not recreate removed archive/v2 code
