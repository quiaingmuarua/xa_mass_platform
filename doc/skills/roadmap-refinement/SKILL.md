---
name: roadmap-refinement
description: Design, review, revise, or execute an explicitly requested roadmap or convergence plan. Ground owner changes, migrations, cleanup, and proof in current evidence; do not turn ordinary code review or a small fix into roadmap work.
---

# Roadmap Refinement

Turn an intended outcome into bounded, verifiable work. Use the user's current
request to decide whether to review, plan, edit, or implement. A roadmap helps
coordinate that work; its labels and templates do not supply authorization or
replace current repository evidence.

## Intent And Authority

- Follow system/developer execution limits, the user's current instructions,
  and applicable repository contracts. This skill adds no permission to mutate
  files, publish, delegate, create goals, or act outside the authorized scope.
- Preserve established user decisions and authorization across turns. A request
  to implement a whole plan covers its necessary slices; the current cursor is
  a progress checkpoint, not an instruction to stop after one slice. Follow an
  explicit request to implement only one slice when the user gives that limit.
- A `Status: active` label alone does not authorize implementation. An approved
  plan in the conversation does not need a new file, status label, role field,
  or second approval before authorized work can begin.
- Questions below are analysis prompts. First answer them from code, callers,
  repository contracts and conversation context. Ask the user only for a
  material unresolved choice that cannot be established from that evidence.

## Select The Work From The Request

- **Review**: inspect and report findings. Do not edit when the user requested
  review only. Lead with the findings that affect the requested decision.
- **Design**: produce an executable plan. Resolve ownership and failure
  semantics only to the depth required by the proposed change.
- **Edit**: revise the requested planning or documentation artifacts directly.
  Preserve accepted intent; do not reopen unrelated architecture decisions.
- **Implementation**: complete the authorized outcome, using slices to control
  dependencies and verification. Continue to the next authorized slice after
  its checks pass. Keep factual plan/status corrections in the same work.

These are task descriptions, not tool modes. They cannot override Plan Mode,
filesystem rules, or other execution limits. A mixed request may authorize both
review and fixes; do the work the user actually requested.

Documentation cleanup, inventory, memory maintenance, and proof repair may be
complete implementation tasks in their own right. Judge them by their stated
acceptance criteria; do not invent a runtime cutover to make them qualify.

## Establish The Necessary Evidence

Check current Git state and preserve unrelated changes. Read the affected
entrypoints, Owner contracts, production callers, assembly and proof surfaces.
Use `rg` to trace the relevant path; do not scan unrelated modules just to fill
an inventory. Documentation or external-state tasks use their actual source of
truth and access mechanism instead of an assumed production hot path.

For a proposed owner or abstraction, establish:

- the invariant and its mutation authority;
- the current callers and the boundary they cross;
- the failure or limitation the change must resolve;
- whether an existing owner or smaller operation already satisfies it;
- whether each transferred fact is truth, evidence, address, correlation,
  projection, diagnostics or a hint;
- any additional state, coordination, public surface or operating cost.

Challenge an unsupported abstraction when evidence warrants it. Do not require
a rejection exercise for an already-grounded small change, or treat the user's
preferred outcome as invalid merely because its implementation is incomplete.

Distinguish three cases when code and a roadmap differ:

1. **Expected migration gap**: implement the planned change.
2. **Stale factual detail**: repair the detail and continue within the accepted
   intent and scope; report the adjustment.
3. **Material conflict**: resolve an actual contradiction in required behavior,
   ownership, compatibility, destructive effects or authorization before the
   dependent action. Continue independent authorized work where possible.

A missing class, renamed caller, outdated status, or failing baseline test does
not by itself require stopping or asking for permission.

## Stateful Or Concurrent Mechanism Changes

When changing mutation ownership, concurrency, retry/recovery, side effects or
termination, read [mechanism-first.md](references/mechanism-first.md). Use its
evidence questions for the affected flow before choosing new types or changing
its execution model. Reuse evidence already established in the task.

The reference does not apply merely because a document mentions a mechanism.
A wording fix, link repair, inventory update or memory cleanup does not require
six runtime artifacts. For mechanism work, combine the relevant evidence in a
compact explanation or sketch; separate files and fixed output headings are
not required.

When the user rejects a model, identify the rejected assumption and reconsider
the decisions derived from it. Retain unrelated established facts and valid
invariants. Re-read the affected path, then revise the mechanism; do not restart
the whole project investigation or delete production code simply to reset a
plan. Show the corrected compact model when useful; ask again only if a
material user decision remains unresolved.

## Plan Shape And Progress

Use an existing roadmap format when it is useful. A small task may need only a
short plan; a multi-slice effort should identify:

- the requested outcome, scope and completion criteria;
- current evidence and the intended behavioral or ownership change;
- ordered slices with the smallest verifiable outcome and required checks;
- real dependencies, deferred work and decisions that could require escalation.

For a migration, name the old serving path and its replacement. For a new
capability or non-runtime task, state the applicable outcome instead; an old
path or production cutpoint is not a universal requirement. A missing template
field is a reason to fill a real information gap, not a reason to reject an
otherwise executable user-approved plan.

Use a paired inventory only when many callers, dependencies or classifications
need a mutable ledger. Keep decisions in the plan and factual rows in the
inventory. Link canonical Owner/proof documents instead of copying their full
contracts; include exact commands or constraints where they are needed to make
a slice executable. Do not create artifact-role files, progress diaries or
inventories solely to satisfy a template.

For long work, keep status and the next unfinished step current. `proposed`,
`active`, `complete` and `superseded` are useful labels when the repository uses
them; they are not a separate permission system. A completed slice does not
complete a larger authorized plan. Required cleanup and validation remain work,
while explicitly deferred non-goals do not block the agreed completion criteria.

### Runtime Migration Guidance

Use these phases only when their effects match the actual migration:

- **pre-converge**: remove a demonstrated wrong-owner dependency or problematic
  mechanism exposure from current callers without changing their runtime truth.
  Name the cutover it enables and the bounded exit condition.
- **mechanism-cutover**: route the chosen production entry through the intended
  Owner mechanism, with focused proof that the old path cannot satisfy the
  migrated invariant.
- **batched-cleanup**: remove obsolete callers, contracts, vocabulary and docs
  once the replacement is usable. This phase may be the entire requested task.
- **guard-freeze**: protect established ownership and behavioral invariants
  without freezing provisional class names or decomposition.

They are not a mandatory waterfall. Move callers before removing dependencies;
no slice may require a later slice to restore compilation or correctness.
Do not leave `pre-converge` open-ended: after its named dependency is resolved,
continue to the next authorized outcome rather than expanding adjacent cleanup.

## Boundary And Cost Decisions

- Keep one mutation authority per invariant. Ownership does not require one
  class per noun or symmetric abstractions for paths with different semantics.
- Size a public contract for its actual callers and repository compatibility
  requirements. Prefer a minimal coherent seam; neither adding DTOs nor
  shrinking a published API is an automatic improvement.
- For internal mechanical seams, prefer owner-stable values, opaque handles or
  explicit parameters. A carrier, facade, bridge or interface needs a concrete
  boundary, invariant, shared algorithm or dependency benefit; naming symmetry
  and mocking convenience alone do not justify it.
- Keep policy, lifecycle and domain interpretation with their owners. Codecs
  translate protocol edges. Additional validation or fencing must address a
  concrete race rather than mirror every check at every layer.
- Treat diagnostics as bounded, non-authoritative observation unless the
  contract explicitly gives that fact another role. Do not promote projections,
  addresses or correlation into scheduling or lifecycle truth for convenience.
- Derive consistency, delivery and recovery guarantees from required behavior
  and failure consequences. Preserve those guarantees during a refactor; do
  not substitute best-effort behavior merely to reduce complexity or cost.
- Assess added threads, queues, stores, scans, locks and background coordination
  against the invariant they establish and a simpler alternative. Keep that
  assessment proportional to the change.

## Verification And Completion

Choose checks that own the changed claim. Use focused deterministic tests for
local mechanisms, and real infrastructure or process proof when the claim
requires it. For documentation, inventory or memory work, verify content,
references, preservation and actual application through the owning system.
Do not invent runtime tests or structural wording tests for a prose-only fix.

For owner cutovers, ask whether the proof would still pass if the old or wrong
path handled the behavior. If it would, add the missing owner/behavior evidence.
Prefer failure, ordering and lifecycle tests to assertions about internal type
names or lock keywords. Green CI is supporting evidence, not a replacement for
the named invariant or proof of an untested capacity/performance claim.

Before declaring the authorized task complete:

- verify its acceptance criteria, affected callers and required cleanup;
- inspect removed-name references and update owning documents where applicable;
- distinguish source inspection, simulated changes and freshly executed proof;
- distinguish a submitted request or built artifact from its actual application
  when completion depends on an external owner.

If an external capability or required decision is unavailable, finish the useful
work already authorized and report the precise remaining dependency. Do not
fabricate an application result, bypass access rules, repeatedly generate the
same proposal, or relabel incomplete work as complete.

On resume, read the latest request, current progress, diff and relevant proof.
Reuse established evidence until a concrete change invalidates it. Report what
changed, why, what was verified and any remaining limitation; keep review
findings and implementation outcomes distinct.
