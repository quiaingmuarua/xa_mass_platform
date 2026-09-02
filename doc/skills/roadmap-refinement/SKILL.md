---
name: roadmap-refinement
description: Design, review, repair, or execute code-grounded roadmaps and convergence slices for owner boundaries, mechanisms, proof, guards, residue, callers, or dependencies. Use for explicit roadmap, slice, boundary, or convergence planning; avoid ordinary code, PR, bug, or implementation review.
---

# Roadmap Refinement

Use this skill to turn vague or drifting roadmap/boundary work into an
executable, code-grounded convergence plan. Use owner review for
Review/Design/Edit; for Implementation, default to executing the active
roadmap cursor.

Use it for roadmap or boundary discussions when the user is shaping execution
rules, goal-mode work, owner constraints, proof/guard policy, or whether a
design concern should become roadmap guidance.

This skill exists to end roadmap discussion, not prolong it. Every roadmap
discussion must end in one decision: reject, narrow, patch, or execute. For an
`active` roadmap, the default decision is execute the current cursor/cutpoint
unless code, proof, owner boundary, or stop triggers invalidate the contract.

## Modes

- **Review**: review, assess, owner-review, re-review, or findings. Return
  findings first; do not edit files.
- **Design**: design, redesign, draft, create, write, rethink, or structure a
  roadmap. Challenge owner/invariant first; then produce the roadmap shape or
  create the file when requested.
- **Edit**: fix, update, repair, rewrite, or revise a roadmap. Edit docs
  directly; create inventory only when needed.
- **Implementation**: execute the approved current cursor/cutpoint only. Stop
  if scope, owner, blast radius, or current code no longer matches the roadmap.

Keep mode boundaries sharp: Review/Design/Edit produce or repair planning
artifacts; Implementation treats an `active` roadmap as the execution contract.
Do not use Implementation time to keep improving roadmap wording unless current
code or proof invalidates the contract.

Use the lightest response shape that fits. Multi-module owner reviews should be
findings-first. Implementation updates should report action, proof, and next
cursor, not re-argue the roadmap.

## Core Semantics

- A roadmap records owner boundary, debt, deferred decisions, slices, proof,
  guards, and completion criteria.
- A slice is one independently verifiable implementation unit. Slice acceptance
  is not roadmap completion.
- Mark `complete` only after all completion criteria are satisfied, residue is
  scanned, and current facts are moved to owning docs or archive when needed.
- Track later debt as later phases, deferred decisions, residual risks, or
  non-goals. Do not hide it to make the current slice appear complete.
- Keep status small: `proposed` means still under challenge/design; `active`
  means approved execution contract; `residue` means mainline is closed but
  cleanup, guards, docs, archive, or known residual phases remain; `complete`
  means completion criteria, residue scan, and fact migration are done;
  `superseded` means no longer current.
- Declare artifact role before execution: `active-contract`,
  `design-reference`, `inventory`, or `archive-ledger`. Only
  `active-contract` may drive goal-mode implementation. Keep it
  cursor/cutpoint-driven; owner background, target-state essays, proof history,
  and completion evidence belong in the other roles.

## Owner Gate

Before creating or repairing a roadmap, challenge the request. Do not accept a
named abstraction as real just because it was proposed. During Implementation,
treat an `active` roadmap as the contract; re-challenge only when current code,
proof, owner boundary, or stop triggers invalidate that contract.

For Review/Design/Edit, ask first:

- Who is the named owner?
- What production invariant does it protect, and how does production fail
  without it?
- Can it be deleted, narrowed, parked, inventoried, or expressed by an existing
  owner seam/channel carrier?
- Is this a use-case decision surface, external server/SDK contract, adapter
  codec, or internal kernel/mechanism seam?
- Is each fact `truth`, `evidence`, `address`, `correlation`, `diagnostics`,
  `projection`, `hint`, `residue`, or `experimental`?
- Does it add resource/infra cost: threads, table scans, locks, transactions,
  queues, indexes, background jobs, or infra operations?

If answers are weak, recommend deletion or narrowing instead of turning the
request into a roadmap. In Implementation, ask only the questions implicated by
the invalidating evidence instead of reopening the whole roadmap.

## Mechanism-First Gate

For roadmap work that changes a stateful or concurrent internal mechanism, read
[mechanism-first.md](references/mechanism-first.md) completely before naming
target types, writing slices, or implementing an approved assistant-generated
plan. Approval locks user intent and scope, not a shape contradicted by the live
mechanism.

The gate requires six compact artifacts: representative-flow trace,
state-owner ledger, failure/side-effect table, execution/blocking map, minimal
pseudocode, and before/after complexity delta. Do not proceed when data flow,
mutation authority, failure ownership, termination behavior, or added state
cannot be explained. Owner clarity means one authority per invariant, not one
class per noun.

When the user rejects an owner, abstraction, lifecycle, or retry model, the
derived plan is invalid. Re-read the scoped production path, discard its
derived types/states, and re-derive the compact mechanism from zero instead of
incrementally preserving the rejected model. After repeated corrections, show
the compact model before producing another roadmap.

## Boundary Rules

- Prefer shrinking externally visible surfaces before polishing internal debt.
- Keep cross-module parameters minimal: stable primitives, caller-owned value
  objects, narrow public contracts, callbacks, or opaque handles.
- Wider, redundant DTOs are acceptable at server/SDK external API boundaries
  when they improve caller ergonomics, compatibility, or contract stability.
- For internal kernel/mechanism seams, treat method-local DTOs and mirrored DTO
  pairs as suspect; reject them when they group fields the caller cannot own,
  validate, or construct.
- For narrow mechanical ports, prefer explicit minimal parameters or an
  owner-stable object already being moved. Do not invent pair/carrier records,
  `Command`, `Request`, `Context`, or `Options` DTOs to make signatures look
  cleaner.
- Keep each interface inside its owner's current responsibility. Do not turn a
  mechanical interface into a universal interface because consistency,
  dedupe/idempotency, diagnostics, repair, or future policy may be useful
  later. Add those only at the owning seam when a named invariant proves they
  are required.
- Keep policy, pre-check/filter choices, lifecycle fence/epoch, diagnostics,
  and future-extension fields out of mechanical action interfaces unless a
  named invariant proves the action itself must read them.
- For internal mechanism seams, let only the target owner parse payload or
  domain fields. Adapter codecs may translate at protocol edges, but must not
  become lifecycle, policy, or domain owners.
- Do not add wrapper/facade/bridge/adapter layers unless they protect a real
  owner boundary, protocol seam, lifecycle split, or external caller surface.
- Do not create an interface for a single fixed internal implementation unless
  it removes a dependency cycle or protects a real owner/protocol/test seam.
  Test mocking convenience and vocabulary symmetry are not sufficient.
- Do not mirror two paths into symmetric abstractions when their side effects,
  ordering, retry safety, producers, or blocking behavior differ. Share only
  the algorithm they actually have in common.

## Lifecycle, Diagnostics, And Cost

- Only the real owner may maintain lifecycle truth.
- Intermediate layers may emit or consume best-effort evidence, but must not
  mirror lifecycle truth or promise strong consistency unless the roadmap names
  the high-ROI invariant, writer, repair path, and proof.
- Strong consistency needs a named high-ROI production invariant. Otherwise
  prefer best-effort observation, retry, bounded drift, or eventual convergence.
- A state transition is not safer merely because every layer checks it. Put the
  decisive check at the owner transition and let non-owners use the resulting
  handle/status best effort unless a concrete race requires another fence.
- Keep diagnostics/observability side-channel by default: append-only, bounded,
  owner-local, and non-authoritative. Observation must not become policy,
  lifecycle, dispatch, public DTO, or cross-module owner-fact dependency unless
  a production invariant requires it.
- Resource-consuming mechanisms and infra operations need explicit
  cost/blast-radius assessment, owner, cheaper-alternative rejection, and proof.

## Evidence

Use fast source search, preferably `rg`. Check the current code path before
editing: imports, public signatures, call sites, test-only usage, dependencies,
controller/API routes, SDK shapes, architecture guards, owner docs, active
roadmaps, acceptance criteria, recent commits, and archive state when status may
be stale.

During Implementation, keep evidence gathering to the active cursor/cutpoint,
touched files, and required proof. Expand only from failing evidence, stop
triggers, or owner-doc references needed to complete the current slice.

Identify the target core mechanism before judging progress: the required
hot-path port, queue, index/key, state machine, or owner store, and whether
current serving code actually goes through it instead of an old/fallback path.

Report evidence as:

```text
current code says ...
target roadmap says ...
gap is ...
```

Do not treat direction docs, status lines, blueprint docs, or archived roadmaps
as proof of current behavior. If code and status disagree, report doc drift in
review mode or repair it in edit mode.

## Inventory

Use or request an inventory when there are many callers/modules, production and
test usage must be separated, dependency movement is involved, ownership is
unclear, target docs disagree with current code, or the first slice is
classification.

For active long roadmaps, use a paired `<ROADMAP>_INVENTORY.md` only when
execution needs a mutable current-code ledger. The roadmap owns decisions:
owner, target mechanism, slice order, acceptance, proof, guards, and stop
conditions. The inventory owns rows: callers, symbols, dependencies, routes,
keys, DTOs, tests, classifications, proof/guard mapping, residue, and closure
status. Inventory rows may expose gaps or refine scope, but must not change
owner, target direction, or mainline order; stop for roadmap review when rows
invalidate those decisions. Do not create an inventory for small or single-slice
work just to satisfy a template.

Minimal inventory shape:

```markdown
# <Topic> Inventory

Status: current code inventory for <roadmap>.

## Symbols

| Symbol | Current Owner | Caller | Classification | Target |
| --- | --- | --- | --- | --- |

## Dependencies

| Module | Dependency | Scope | Reason | Target |
| --- | --- | --- | --- | --- |

## Closure Notes

- ...
```

Add proof, guard, and status columns when the inventory gates execution or
closure. Keep rows factual and current-code grounded.

Useful classifications: runtime truth, control-plane declaration, storage
adapter, read model, compatibility residue, admin/bootstrap, transport/session
evidence, test fixture, stale documentation.

## Roadmap Shape

Use this shape flexibly:

- current code observations
- minimal mechanism sketch and failure/side-effect decisions for stateful work
- state-owner and complexity delta when the goal includes simplification
- owner review and boundary decision
- target shape only when it adds clarity
- non-goals
- executable slices with goal, scope, acceptance, and verification point
- implementation order
- roadmap completion criteria distinct from slice acceptance
- verification candidates
- "Do Not Start With" warnings for tempting wrong-order shortcuts

For an `active-contract`, require the minimum execution contract:

- status
- current cursor
- cutpoint
- old path to close
- target mechanism/path
- allowed changes
- forbidden drift
- exit proof
- next cursor
- deferred residue
- stop triggers

If `old path to close` and `exit proof` are missing, do not treat the artifact
as active implementation input.

A roadmap should be good enough to execute, not exhaustive. Prefer a narrow
cutpoint with proof over a broader document that invites more discussion.
Do not use a long contract as a substitute for the mechanism-first gate. A plan
that precisely specifies many types while leaving item flow, mutation owner,
or replay safety implicit is not executable.
Do not store implementation history, broad proof catalogs, or owner essays in an
`active-contract`; move them to inventory, proof registry, owner docs, or
archive-ledger after proof.

For active long roadmaps, link only the paired inventory and directly relevant
prerequisite roadmaps or blueprints. Do not link generic owner/proof/testing
docs as a reading list; inline any requirement needed to execute, verify, or
stop the current roadmap.

Every active roadmap needs a mainline anchor: the owner path, external entry,
hot path, boundary API, or serving mechanism whose closure makes the roadmap
valuable. Rename-only, local polish, test-name/vocabulary changes, and docs
wording cleanup are `batched-cleanup` residue, not mainline or pre-converge.
If a change moves a fact between owner surfaces, such as moving a read model out
of a mutation/core runtime port, classify it as owner/boundary cutover work, not
rename.

Boundary roadmaps usually converge in this order:

1. Inventory and classify current behavior.
2. Decide owner and minimal public seam.
3. Close one mainline mechanism through the boundary API and lifecycle path,
   using built-in/default strategy when needed.
4. Move/narrow contracts, retarget implementations/adapters, update assembly
   and downstream callers.
5. Add focused cutover proof; add stable negative guards only after owner truth
   and serving paths are stable.
6. Remove residue, stale docs, compatibility paths, old vocabulary, strategy
   variants, and corner cases after the mainline path is closed.

For runtime/serving migrations, use active execution phases instead of a global
waterfall:

- `pre-converge`: materially converge the current runtime mechanism without
  changing runtime truth: revise boundary interfaces, DTO shapes, method
  signatures, caller wiring, or adapter seams so current serving callers no
  longer depend on the wrong owner fact or old mechanism exposure. It may run
  across domains before cutover work. It is not simple rename, local polish,
  test-name/vocabulary cleanup, docs wording cleanup, or API reshaping that
  does not reduce current runtime-mechanism pressure.
- `mechanism-cutover`: for one bounded cutpoint/domain, implement the needed
  owner mechanism and cut serving/runtime traffic over to it. Exit with focused
  owner/cutover proof.
- `batched-cleanup`: after enough cutpoints are proven, remove old paths,
  key/DTO/test/vocabulary residue, compatibility paths, and stale docs in
  batches.
- `guard-freeze`: add stable negative guards after owner truth and serving paths
  are stable.

Do not let `mechanism-cutover` become mechanism-only work: each slice must name
its cutpoint, target mechanism, old mechanism/path to close, and smallest
cutover proof. Add allowed/forbidden scope only when drift risk is high. Cleanup
and guard work may be batched after cutpoint proof instead of repeated after
every cutpoint.

Do not let `pre-converge` become open-ended cleanup: each slice must name the
mainline anchor it unblocks, the current runtime-mechanism pressure it removes,
the smallest boundary surface to change, exit proof, and deferred residue.
After that surface is closed or classified, move to `mechanism-cutover` or stop
for owner coordination instead of expanding adjacent cleanup.

Progress rule: stay in `pre-converge` only while each slice materially reduces
current runtime-mechanism pressure and directly moves a named
`mechanism-cutover` cutpoint closer. If the next work is rename, polish,
inventory expansion, broad caller cleanup, or proof grooming beyond required
exit proof, defer it to `batched-cleanup` or stop for owner coordination.

Use phase names by effect, not file operation: changing an interface/DTO/method
signature so current serving callers stop depending on the wrong owner fact or
old runtime-mechanism exposure is pre-converge; moving a capability to a
different owner entry is mechanism/boundary cutover; simple rename, local
naming, test renames, and docs vocabulary cleanup are batched cleanup.

Do not start by deleting dependencies before moving callers. No slice should
require a later slice to restore compilation or runtime correctness.

## Proof, Guards, And Execution

- Acceptance must be testable in code review and use concrete files, symbols,
  routes, dependencies, or commands when known.
- Prefer owner-focused deterministic tests first. Add representative
  cross-boundary proof only when risk crosses a real boundary.
- Label proof type explicitly. Behavior proof shows user-visible/runtime
  behavior; ownership proof shows the required owner mechanism/hot path is used
  and old/fallback paths cannot satisfy the invariant.
- Treat green CI as support evidence, not proof, when it preserves old behavior
  or lacks a focused invariant.
- For each proof, ask the anti-proof question: would this still pass if the old
  or wrong mechanism handled the behavior? If yes, add a mechanism-specific
  assertion, negative guard, or old-path disablement before calling it owner
  proof.
- A slice is not successful because the target mechanism exists. It succeeds
  only when the production cutpoint enters that mechanism and the old path can
  no longer satisfy the exit proof.
- Guard stable owner invariants and forbidden regressions, not temporary class
  names, lifecycle states, or provisional implementation shape.
- Prefer behavioral failure/order/lifecycle proof before structural guards.
  Do not assert a lock/CAS keyword, internal class name, wrapper count, or
  provisional decomposition unless its presence or absence is itself a stable
  owner invariant. Use negative residue guards only after the replacement path
  is proven.
- Useful guards include forbidden imports, dependency-scope checks,
  architecture tests, contract-shape allowlists, route naming guards, and
  proof-registry/testing-index updates.

When executing a slice:

1. Confirm scope, current cursor, cutpoint, old path to close, target
   mechanism/path, and exit proof. For stateful internal work, also confirm the
   representative-flow trace, state ledger, failure boundary,
   execution/blocking map, minimal pseudocode, and complexity delta before
   editing.
2. Check the worktree and preserve unrelated user changes.
3. Establish a baseline when risk justifies it.
4. Implement only the current slice; prefer closing or disabling the named old
   path over adding new structure. Start from the simplest standard-library
   mechanism that satisfies the named invariants; add abstractions only when
   the Mechanism-First abstraction admission test succeeds.
5. Stop for owner coordination if roadmap definition is materially unclear or
   wrong, owner boundary changes, blast radius expands, or code conflicts with
   the slice.
6. During long goal-mode execution, treat an `active` roadmap as the approved
   execution contract and keep a tiny execution cursor: status, phase, mainline
   anchor, current cutpoint, locked mainline, next proof, deferred residue, and
   stop triggers. For `mechanism-cutover`, also include target mechanism and old
   mechanism/path to close. On resume or compaction, read the cursor, current
   diff, touched files, and required proof first; expand only from concrete
   failing evidence, owner-doc references, or stop triggers. Do not re-review,
   rewrite, broaden, or use it as progress notes unless the user asks. Edit
   only for factual code/status/proof/assumption changes or owner coordination.
7. After each slice, state one progress outcome: materially reduced current
   runtime-mechanism pressure toward a named cutpoint, moved to
   `mechanism-cutover`, closed one named cutpoint with proof, stopped because
   owner/cutpoint is unclear, or deferred residue to `batched-cleanup`. If none
   is true, stop and re-anchor instead of continuing.
8. If the diff only improves docs, inventory, proof catalogs, or owner wording
   without moving or closing the named cutpoint, report it as Review/Edit or
   residue work, not Implementation success.
9. Update contracts, docs, guards, and verification in the same slice when code
   changes them.
10. If the slice closes a roadmap gap or changes status, update roadmap wording
   from plan-state to evidence-state.
11. After rename, dependency, boundary, or compatibility-removal work, suggest or
   run `roadmap-residue-scan` before declaring completion.

## Delivery

In review mode, lead with findings. Use severity only when it helps:

- **High**: blocks execution, creates wrong ownership, or likely causes churn.
- **Medium**: fix before implementation; likely scope/proof/ownership risk.
- **Low**: clarity or maintainability issue that does not block execution.

End with one concrete conclusion: fix blockers first, executable next slice,
mainline can proceed with residual phases, no blocking findings, or too broad
and should split before execution.

After edits, summarize files changed, boundary decision, inventory status,
unresolved decisions, and verification. If only roadmap/docs changed, say no
code behavior changed.

## High-Risk Failure Modes

- Accepting the user's abstraction without challenging owner, invariant,
  failure mode, and deletion path.
- Treating target/direction docs as current implementation proof.
- Overselling stopgap hygiene as strategic boundary repair.
- Hiding public exposure behind local import cleanup.
- Creating fake isolation with internal fat DTOs, mirrored DTOs, compatibility
  aliases, or pass-through wrappers.
- Converting every noun or verb into a class/interface and calling the larger
  type graph clearer ownership. Ownership follows mutable invariants.
- Designing target types before tracing a representative runtime flow, its
  state transitions, side-effect boundary, failure owner, and termination path.
- Treating genericity as interface count instead of one shared algorithm with
  narrow variation points.
- Combining multiple retry, replay, recovery, or compensation paths without
  distinct invariants, or replaying an unknown partial side effect.
- Mechanically replacing coordination primitives or lifecycle state shapes
  without proving the original shared-state invariant still exists.
- Incrementally preserving a rejected plan's derived abstractions instead of
  discarding the invalid model and re-deriving the minimal mechanism.
- Expanding a mechanical operation into a universal interface for possible
  policy, lifecycle, consistency, dedupe/idempotency, diagnostics, repair, or
  future-extension needs.
- Treating "thin DTO" as sufficient when an internal seam should use an
  owner-stable model, opaque frame/handle, or explicit primitive.
- Promoting address, correlation, diagnostics, or evidence into lifecycle or
  scheduling truth.
- Coupling snapshot viewers, read models, schemas, public DTOs, or policy /
  lifecycle / dispatch dependencies into the mainline as diagnostics.
- Treating an `active-contract` as a design reference, proof archive, or
  implementation diary.
- Letting non-owners maintain lifecycle truth or promise strong consistency.
- Writing a correct target-state document while the current diff does not close
  a production old path.
- Treating rename-only, local variable cleanup, test-name/vocabulary changes,
  docs wording cleanup, guards, diagnostics, or local polish as pre-converge or
  mainline work. They are residue/cleanup; if a change actually moves owner
  responsibility or caller entry, classify it as owner/boundary cutover instead
  of rename.
- Calling boundary churn `pre-converge` when it does not materially reduce
  current runtime-mechanism pressure. Pre-converge must converge current serving
  mechanism exposure while preserving runtime truth; otherwise it is residue,
  polish, or roadmap churn.
- Staying in `pre-converge` when the next slice does not materially reduce
  current runtime-mechanism pressure toward a named cutpoint.
- Adding threads, scanners, locks, transactions, queues, indexes, background
  jobs, or infra operations without cost/blast-radius assessment and
  cheaper-alternative rejection.
- Trusting module/package names over production call sites.
- Reusing broad tests or CI green as proof when focused invariant proof is
  missing.
- Editing adjacent roadmap files without requested scope, current evidence, or
  residue/coordination need.
- Marking a roadmap complete after only one slice or prerequisite unblocker.
- Archiving without residue scan, active-link cleanup, and owner-doc fact
  migration.
