# Platform Architecture Boundary Direction

Last updated: 2026-05-18

Status: target project boundary direction, not a current implementation
baseline.

This document describes the intended top-level architecture boundary for XA Mass
Platform once the current convergence work stabilizes.

It is deliberately about owner boundaries, not about today's exact package
layout, and it must not be read as "already fully implemented".

It is a north-star constraint for architectural convergence:

- use it to keep new code and refactors from drifting across long-term boundary lines
- do not cite it as proof that the current implementation already matches the target design
- when it conflicts with current code or verified runtime behavior, current truth wins and the gap should be described explicitly rather than hand-waved away

Use with:

- [AGENT_BASELINE.md](./AGENT_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md)
- [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md)
- [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)

## 1. Why This Exists

The repo already has strong truth-layer rules:

- control-plane storage
- runtime state
- trace / audit stream

Those rules answer **where truth lives**.

This document answers a different question:

> which product or runtime boundary owns each major responsibility

The two views are orthogonal:

- truth layers describe storage/runtime/trace placement
- architecture planes describe owner boundaries and external/internal roles

Do not collapse them into one diagram.

## 2. Six-Plane Direction

The intended long-term boundary is:

1. Core Kernel
2. Worker Management Plane
3. Transport Plane
4. Result Read / Archive Plane
5. Operator Plane
6. Host / API Plane

The most important correction versus a simpler five-plane description is:

- result convergence stays in the kernel
- result reading, archive, and retention live in a separate read/archive plane
- host/API concerns are separate from operator concerns

## 3. Plane Definitions

### Core Kernel

Owns:

- task contract / intake / lifecycle
- scheduling / assignment / allocation
- runtime lease / retry / expiry
- task convergence / terminal policy
- result convergence / finality truth

Must not own:

- worker/device/account registration lifecycle
- transport protocol framing
- archive/export presentation
- operator console/read tooling
- host auth / tenant / project / user shell semantics

Current repo anchors:

- `xa-mass-engine`
- `platform_infra/mass-runtime-api`
- runtime implementations under `platform_infra/mass-runtime-*`

### Worker Management Plane

Owns:

- worker registration and lifecycle
- capability declaration
- routing and scheduling attributes
- reachability-facing scheduling view inputs
- shared-safe / capacity declaration
- future device/account/system-event-backed worker management

Must not own:

- task scheduling truth
- result finality
- transport delivery semantics

Current repo anchors:

- worker model and storage contracts
- worker registration APIs
- transport-fed reachability views

This plane is intentionally called out even though the repo has not fully
converged on a dedicated worker-management subsystem yet.

### Transport Plane

Owns:

- websocket / polling / socket / future gateway delivery
- dispatch framing and adapter routing
- result ingress framing
- transport presence and route-owner views
- transport-specific session/channel concerns

Must not own:

- task lifecycle semantics
- retry/finality policy
- worker registration truth
- result archive truth

Current repo anchors:

- `transport/*`

### Result Read / Archive Plane

Owns:

- live result window reads
- stable-final result query surfaces
- archive manifest / content
- retention / compaction policy
- export-oriented result materialization

Must not own:

- result application
- retry/finality decision
- callback acceptance
- task convergence

Current repo anchors:

- `TaskResultRuntime` read surfaces
- SDK/server result query and archive paths

Naming rule:

- this is **Result Read / Archive**, not generic "Result"
- result convergence remains kernel-owned

### Operator Plane

Owns:

- trace
- metrics
- diagnostics
- console/operator views
- analyzers and audit-facing tooling

Must not own:

- runtime acceptance truth
- scheduling policy truth
- task/result finality decisions
- host/API contract ownership

Current repo anchors:

- `xa-mass-trace`
- operator/diagnostic server surfaces
- console/debug read paths

### Host / API Plane

Owns:

- server external APIs
- SDK public contracts
- auth / tenant / project / user shell concerns
- request/response contract stability
- product-host workflow composition

Must not own:

- kernel runtime truth
- transport adapter semantics
- worker lifecycle truth
- trace/operator truth

Current repo anchors:

- `xa-mass-sdk-api`
- `xa-mass-sdk`
- `xa-mass-server`

This plane is separate from the operator plane because product/API hosting and
operator observability evolve at different rates and protect different callers.

## 4. Non-Negotiable Rules

1. No surrounding plane may redefine kernel truth.
2. Worker Management and Transport may feed scheduling inputs, but they do not
   own scheduling decisions.
3. Result Read / Archive may expose stable-final result truth, but it does not
   own callback acceptance or finality.
4. Operator surfaces may inspect and reconstruct runtime decisions, but they do
   not own them.
5. Host / API surfaces may stabilize external contracts, but they must not
   force engine/runtime internals to freeze as public truth.

## 5. Current Convergence Notes

This six-plane model is the architectural ceiling, not a claim that every plane
is already cleanly isolated today.

The main known in-progress convergence areas are:

- Worker Management Plane is still partly represented through engine-facing
  worker capability compatibility fields rather than a fully independent
  management subsystem
- Result Read / Archive Plane is still being separated from result convergence
  and compatibility residue history
- Operator Plane and Host / API Plane still share some server-hosted surfaces
- worker resource/capacity behavior is kernel-owned today; future
  worker-management integration must feed scheduling views rather than
  reintroducing context/resource ownership inside the kernel

These are implementation convergence facts, not reasons to blur the target
boundary.

## 6. Relation To Existing Docs

Use this document for the project-level boundary picture only.

For current truth, still prefer:

- [AGENT_BASELINE.md](./AGENT_BASELINE.md) for active platform baseline
- [INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) for truth placement
- [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md) for result-owner
  split
- [INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md) for current HTTP/API
  contract
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md) for kernel-local
  owner details

Usage rule:

- direction docs guide architectural convergence
- baseline docs describe active truth
- implementation and review work should use direction docs to avoid boundary drift, but should use baseline docs and code to describe what is actually live today

## 7. Working Summary

If a future architectural change cannot be described cleanly in one of these
planes, either:

- the plane boundary is still wrong, or
- the proposed change is mixing owner concerns that should stay separate

The intended end state is not "many modules". It is:

- one stable kernel
- one explicit worker-management boundary
- one explicit transport boundary
- one explicit result read/archive boundary
- one explicit operator boundary
- one explicit host/API boundary
