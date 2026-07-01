---
name: roadmap-refinement
description: Roadmap and boundary convergence skill. Use when the user asks to design, redesign, draft, create, write, rethink, structure, review, repair, or execute a roadmap; plan slices; prepare goal-mode roadmap execution; review owner boundaries; design proof/guards; scan residue; merge/split roadmap work; build caller/dependency/boundary inventory; or turn architecture/code review findings into roadmap, proof, boundary, convergence, or long-running execution planning. Avoid ordinary code, PR, bug, or implementation review unless roadmap/slice/proof/boundary/convergence planning is requested or clearly implied.
---

# Roadmap Refinement

Use this skill to turn vague or drifting roadmap/boundary work into an
executable, code-grounded convergence plan. Default to owner review: inspect
live code and verified behavior before refining plans.

Use it for roadmap or boundary discussions when the user is shaping execution
rules, goal-mode work, owner constraints, proof/guard policy, or whether a
design concern should become roadmap guidance.

## Modes

- **Review**: review, assess, owner-review, re-review, or findings. Return
  findings first; do not edit files.
- **Design**: design, redesign, draft, create, write, rethink, or structure a
  roadmap. Challenge owner/invariant first; then produce the roadmap shape or
  create the file when requested.
- **Edit**: fix, update, repair, rewrite, or revise a roadmap. Edit docs
  directly; create inventory only when needed.
- **Implementation**: execute an approved slice only. Stop if scope, owner,
  blast radius, or current code no longer matches the roadmap.

Use the lightest response shape that fits. Multi-module owner reviews should be
findings-first.

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

## Owner Gate

Before creating, repairing, or executing a roadmap, challenge the request. Do
not accept a named abstraction as real just because it was proposed.

Ask first:

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
request into a roadmap.

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

## Lifecycle, Diagnostics, And Cost

- Only the real owner may maintain lifecycle truth.
- Intermediate layers may emit or consume best-effort evidence, but must not
  mirror lifecycle truth or promise strong consistency unless the roadmap names
  the high-ROI invariant, writer, repair path, and proof.
- Strong consistency needs a named high-ROI production invariant. Otherwise
  prefer best-effort observation, retry, bounded drift, or eventual convergence.
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
- owner review and boundary decision
- target shape only when it adds clarity
- non-goals
- executable slices with goal, scope, acceptance, and verification point
- implementation order
- roadmap completion criteria distinct from slice acceptance
- verification candidates
- "Do Not Start With" warnings for tempting wrong-order shortcuts

For active long roadmaps, link only the paired inventory and directly relevant
prerequisite roadmaps or blueprints. Do not link generic owner/proof/testing
docs as a reading list; inline any requirement needed to execute, verify, or
stop the current roadmap.

Boundary roadmaps usually converge in this order:

1. Inventory and classify current behavior.
2. Decide owner and minimal public seam.
3. Close one mainline mechanism through the boundary API and lifecycle path,
   using built-in/default strategy when needed.
4. Move/narrow contracts, retarget implementations/adapters, update assembly
   and downstream callers.
5. Add focused proof and stable negative guards.
6. Remove residue, stale docs, compatibility paths, old vocabulary, strategy
   variants, and corner cases after the mainline path is closed.

For runtime/serving migrations, use active execution phases instead of a global
waterfall:

- `pre-converge`: narrow interfaces and delete/hide wrong exposure points
  without changing runtime truth. It may run across domains before cutover work.
- `mechanism-cutover`: for one bounded cutpoint/domain, implement the needed
  owner mechanism and cut serving/runtime traffic over to it. Exit with focused
  owner/cutover proof.
- `batched-cleanup`: after enough cutpoints are proven, remove old paths,
  key/DTO/test/vocabulary residue, compatibility paths, and stale docs in
  batches.
- `guard-freeze`: add stable negative guards after owner truth and serving paths
  are stable.

Do not let `mechanism-cutover` become mechanism-only work: each slice must name
its cutpoint and smallest cutover proof. Cleanup and guard work may be batched
after cutpoint proof instead of repeated after every cutpoint.

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
- Guard stable owner invariants and forbidden regressions, not temporary class
  names, lifecycle states, or provisional implementation shape.
- Useful guards include forbidden imports, dependency-scope checks,
  architecture tests, contract-shape allowlists, route naming guards, and
  proof-registry/testing-index updates.

When executing a slice:

1. Confirm scope, acceptance, and verification.
2. Check the worktree and preserve unrelated user changes.
3. Establish a baseline when risk justifies it.
4. Implement only the current slice.
5. Stop for owner coordination if roadmap definition is materially unclear or
   wrong, owner boundary changes, blast radius expands, or code conflicts with
   the slice.
6. During long goal-mode execution, treat an `active` roadmap as the approved
   execution contract and keep a tiny execution cursor: status, phase, current
   cutpoint, locked mainline, next proof, deferred residue, and stop triggers.
   On resume or compaction, read the cursor, current diff, touched files, and
   required proof first; expand only from concrete failing evidence, owner-doc
   references, or stop triggers. Do not re-review, rewrite, broaden, or use it
   as progress notes unless the user asks. Edit only for factual
   code/status/proof/assumption changes or owner coordination.
7. Update contracts, docs, guards, and verification in the same slice when code
   changes them.
8. If the slice closes a roadmap gap or changes status, update roadmap wording
   from plan-state to evidence-state.
9. After rename, dependency, boundary, or compatibility-removal work, suggest or
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
- Expanding a mechanical operation into a universal interface for possible
  policy, lifecycle, consistency, dedupe/idempotency, diagnostics, repair, or
  future-extension needs.
- Treating "thin DTO" as sufficient when an internal seam should use an
  owner-stable model, opaque frame/handle, or explicit primitive.
- Promoting address, correlation, diagnostics, or evidence into lifecycle or
  scheduling truth.
- Coupling snapshot viewers, read models, schemas, public DTOs, or policy /
  lifecycle / dispatch dependencies into the mainline as diagnostics.
- Letting non-owners maintain lifecycle truth or promise strong consistency.
- Polishing strategy variants, corner cases, diagnostics, rename, guards, or
  API reshaping before mainline mechanism, boundary API, lifecycle, and callers
  are closed.
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
