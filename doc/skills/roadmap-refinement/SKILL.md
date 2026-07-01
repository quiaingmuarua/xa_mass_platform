---
name: roadmap-refinement
description: Converge roadmap or boundary work into executable, code-grounded plans with owner review, proof surfaces, guardrails, and verification. Use for roadmap review/repair, executable slice planning, owner-boundary review, proof/guard design, residue follow-up, merge/split decisions, or caller/dependency/boundary inventory. Do not trigger for open-ended brainstorming or ordinary code, PR, bug, or implementation reviews unless the user asks to convert findings into a roadmap, slice plan, proof plan, boundary inventory, or convergence plan.
---

# Roadmap Refinement

Use this skill to turn vague or drifting roadmap work into an executable,
code-grounded convergence plan. Default to owner review: inspect the live code
path first, then refine the roadmap.

Do not use this skill for early brainstorming or free-form architecture
discussion unless the user asks to converge on a roadmap, review, proof plan, or
implementation plan.

## Modes

Choose the mode from the user's wording before editing.

- **Review mode**: review, assess, owner-review, re-review, or give findings.
  Return findings first and do not modify files.
- **Edit mode**: fix, update, repair, rewrite, create, or revise a roadmap.
  Edit roadmap/docs directly; create an inventory only when the boundary needs
  one.
- **Implementation mode**: execute or implement an approved roadmap slice. Make
  only the approved slice's code/doc changes; stop if the slice no longer
  matches current code or needs a larger owner decision.

Use the lightest response shape that fits. Simple one-file checks can be short;
multi-module owner reviews should use findings-first structure.

## Core Semantics

- A roadmap records the full owner boundary, known debt, deferred decisions,
  phases, proof surfaces, and completion criteria.
- A current slice is the next independently verifiable implementation unit.
- Slice acceptance is not roadmap completion. Use `complete` only after all
  completion criteria are satisfied, residue is scanned, and current facts are
  moved to owning docs or archive when needed.
- Later-phase debt should remain visible as a later phase, deferred decision,
  residual risk, or non-goal. Do not hide it to make the current slice appear
  smaller or complete.

Useful status words:

- `proposed`: target direction exists; implementation not meaningfully landed.
- `active`: at least one slice is in progress or landed, and more remains.
- `slice complete, roadmap active`: current slice landed; later phases remain.
- `mainline unblocked, residual phases remain`: enough landed for a dependent
  roadmap, but this roadmap is not complete.
- `implemented-with-residue`: behavior appears landed, but old names, imports,
  docs, guards, aliases, or references remain.
- `historical` or `superseded`: do not execute except as background.

## Demand Challenge

Do not accept the requested abstraction as valid merely because it was named.
Before refining a roadmap, challenge whether the public interface, port method,
model, field, DTO, command/request object, bridge, wrapper, lifecycle state,
guard, or status sync is needed at all.

- Who is the named owner?
- Does it protect a production invariant or only agent/refactor convenience?
- Without it, where does production behavior actually fail?
- Can an existing owner seam or channel carrier express it?
- Is the interface a use-case decision surface or a narrow mechanical action?
  Do not wrap narrow mechanical actions in `Command`, `Request`, `Context`, or
  `Options` DTOs just to make the signature look cleaner.
- Is the DTO for an external server/SDK contract, an adapter codec, or an
  internal kernel/mechanism seam?
- If it is diagnostics or observability, can it be an append-only event, trace,
  counter, structured log, or owner-local bounded hook instead of a snapshot
  viewer, read model, public contract, or mainline-maintained state?
- Does it add resource or infra cost such as threads, table scans, locks,
  transactions, queues, indexes, background jobs, or infra operations?
- Is each field `truth`, `evidence`, `address`, `correlation`, `diagnostics`,
  `projection`, `hint`, `residue`, or `experimental`?
- If this abstraction is deleted, what breaks beyond naming symmetry,
  dependency comfort, or roadmap narrative?

If the answer is weak, recommend deletion, narrowing, parking, or inventory
instead of turning the request into a roadmap.

## Boundary Gate

Before creating, repairing, or executing a boundary roadmap, walk through this
order. Do not start with guardrails, automation, or rename work before owner and
mechanism are stable.

1. **Question**
   - Name the owner of the production decision or runtime truth.
   - State the invariant and how the system fails without the change.
   - For large changes, name the mainline mechanism, boundary API, and lifecycle
     path that must close first. Strategy may stay built-in/default until that
     path is stable.
   - Classify each fact as `truth`, `evidence`, `address`, `correlation`,
     `diagnostics`, `projection`, `hint`, `residue`, or `experimental`.
   - Keep `address`, `correlation`, and `diagnostics` out of lifecycle,
     scheduling, and owner state-machine authority.
   - Keep diagnostics and observability side-channel by default: append-only,
     bounded, owner-local, and non-authoritative. Mechanism and strategy should
     stay separable; observation must not become a policy, lifecycle, dispatch,
     public DTO, or cross-module owner-fact dependency unless a named production
     invariant requires it.
   - Decide whether the work is high-ROI boundary reduction or stopgap hygiene.
     Prefer shrinking externally visible surfaces before polishing internal
     debt.
   - State whether strong consistency is required by a high-ROI production
     invariant. If not, prefer best-effort observation, retry, bounded drift, or
     eventual convergence.
   - Resource-consuming mechanisms and infra operations need explicit
     cost/blast-radius assessment, owner, cheaper-alternative rejection, and
     proof.
2. **Delete**
   - Prefer deleting old DTOs, wrappers, bridges, fallbacks, aliases, stale
     tests, and parallel narratives before adding replacements.
   - Reuse an existing owner seam when it already expresses the invariant.
   - Move ownership rather than adding fields so two owners can agree.
3. **Simplify**
   - Keep cross-module parameters minimal: stable primitives, caller-owned value
     objects, narrow public contracts, callbacks, or opaque handles.
   - Allow wider, redundant DTOs at server/SDK external API boundaries when they
     improve caller ergonomics, compatibility, or contract stability.
   - For internal kernel/mechanism seams, treat method-local DTOs and mirrored
     DTO pairs as suspect; reject them when they group fields the caller cannot
     own, validate, or construct.
   - For narrow mechanical ports, prefer explicit minimal parameters or a thin
     carrier only when that carrier is already the owner-stable object being
     moved; do not introduce `Command`, `Request`, `Context`, or `Options` DTOs
     to shorten signatures.
   - Do not invent pair/carrier records for kernel seams when an owner-stable
     model, opaque frame/handle, or primitive identity already expresses the
     action.
   - Keep policy, pre-check/filter choices, lifecycle fence/epoch, diagnostics,
     and future-extension fields out of mechanical action interfaces unless a
     named invariant proves the action itself must read them.
   - For internal mechanism seams, let only the target owner parse payload or
     domain fields. Adapter codecs may translate at protocol edges, but must not
     become lifecycle, policy, or domain owners.
   - Do not add wrapper, facade, bridge, or adapter layers unless they protect a
     real owner boundary, protocol seam, lifecycle split, or external caller
     surface.
4. **Lifecycle**
   - Only the real owner may maintain lifecycle truth.
   - Intermediate layers may emit or consume best-effort evidence, but must not
     mirror lifecycle truth or promise strong consistency unless the roadmap
     names the high-ROI invariant, writer, repair path, and proof.
   - Small timing drift in status synchronization is acceptable when retry,
     repair, or bounded observation can converge it.
5. **Prove and Automate**
   - Pick the smallest proof that fails when the owner invariant is wrong.
   - Prefer proof before guard when the owner mechanism is still settling.
   - Add guards only after the owner truth is stable enough to freeze.
   - Prefer negative guards that block old imports, old symbols, fallback paths,
     fat DTOs, mirrored DTOs, and projection-to-mainline leaks.
   - Do not guard provisional class names, temporary lifecycle states, or
     implementation shape before the mechanism is settled.

## Evidence Workflow

Use fast source search before editing. Prefer `rg`.

Check the evidence needed for the requested boundary:

- main-source imports, public signatures, and call sites
- test-only imports separately
- Maven/Gradle dependencies and scopes
- controller/API routes and external SDK/API shapes
- existing architecture guards and allowlists
- storage, runtime, transport, server, SDK, or owner docs referenced by the
  roadmap
- related active roadmaps, Non-Goals, pending slices, acceptance criteria, and
  dependency assumptions
- recent commits or archive location when status may be stale

Report facts with this split:

```text
current code says ...
target roadmap says ...
gap is ...
```

Do not treat direction docs, blueprint docs, status lines, or archived roadmaps
as proof of current behavior. If status and code disagree, report doc drift in
review mode or repair the status in edit mode.

For many-roadmap reviews, classify each roadmap by current code state, not just
its `Status:` line. Keep the table short:

```markdown
| Roadmap | Status Line | Code Evidence | Class | Next Action |
| --- | --- | --- | --- | --- |
```

## Inventory

Inventory is often the first deliverable for boundary work.

- Review mode: require or recommend inventory; do not create it.
- Edit mode: create or update a sibling `*_INVENTORY.md` when needed.
- Implementation mode: create or update inventory only if the approved roadmap
  calls for it or the code path is materially unclear.

Use an inventory when there are many callers/modules, production and test usage
must be separated, a dependency is being moved or removed, ownership is unclear,
current implementation and target docs disagree, or the first slice is
classification.

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

## Decisions

- ...
```

Useful classifications: runtime truth, control-plane declaration, storage
adapter, read model, compatibility residue, admin/bootstrap, transport/session
evidence, test fixture, stale documentation.

## Owner Review

Before rewriting a roadmap, state the owner decision explicitly:

```text
<Domain object> belongs to <owner>.
<Other module> may consume it through <minimal contract>, but must not define it.
<Implementation module> is an adapter/evidence producer, not the contract owner.
```

For lifecycle or status synchronization:

```text
<Lifecycle fact> is maintained by <owner>.
<Intermediate module> may emit or consume best-effort evidence, but must not
mirror lifecycle truth or promise strong consistency unless the roadmap names
the high-ROI invariant, writer, repair path, and proof.
```

Load and follow the repository's handoff docs and owner contracts. Do not apply
one repository's owner split to another repository unless the current repo has
the same documented boundary.

## Merge Or Split

Merge work into one roadmap only when all are true:

- same owner boundary
- same caller family
- same proof and guard set
- same implementation sequence
- no existing Non-Goal is violated

Split the roadmap when owner boundary, proof commands, caller set, or execution
sequence differ; when one track can finish while another is blocked; or when the
shared label is superficial, such as both touching `storage`.

When splitting, cross-link the roadmaps and say why they are separate.

## Roadmap Shape

Use this shape flexibly:

- current code observations
- owner review and boundary decision
- target shape only when it adds clarity beyond the boundary decision
- non-goals
- executable slices with goal, scope, acceptance, and verification point
- suggested implementation order
- roadmap completion criteria distinct from slice acceptance
- verification candidates
- "Do Not Start With" warning for tempting wrong-order shortcuts

Boundary roadmaps usually converge in this order:

1. Inventory and classify callers, dependencies, symbols, and current behavior.
2. Decide the contract owner and minimal public seam.
3. Close one mainline mechanism through the boundary API and lifecycle path,
   using built-in/default strategy when needed.
4. Move or narrow contracts, retarget implementations/adapters, and update
   assembly plus downstream callers.
5. Add focused proof and stable negative guards.
6. Remove residue, stale docs, compatibility paths, old vocabulary, strategy
   variants, and corner cases only after the mainline path is closed.

Do not start by deleting dependencies before moving callers. No slice should
require a later slice to restore compilation or runtime correctness.

## Acceptance, Proof, And Guards

Acceptance must be testable in code review.

- Write slice acceptance for the implementation unit.
- Write roadmap completion criteria for the whole convergence path.
- Use concrete file, class, method, package, route, dependency, or command
  names when known.
- Avoid acceptance that cannot fail, such as "clean up dependencies" or "make
  the boundary clearer."

For proof:

- Prefer owner-focused deterministic tests first.
- Add representative cross-boundary proof only when the changed risk crosses a
  real boundary.
- Use the repository's proof registry or testing index when present.
- Treat green CI as support evidence, not proof, when it preserves
  compatibility behavior or lacks a focused invariant.
- Verification candidates should be concrete commands. If exact tests are not
  known yet, say they must be corrected after inventory.

For guards:

- Guard stable owner invariants and forbidden regressions, not temporary
  implementation shape.
- Useful guards include forbidden import/package scans, Maven dependency-scope
  guards, architecture tests, contract-shape allowlists, route naming guards,
  and proof-registry/testing-index updates.

## Implementation Mode

When executing a roadmap slice:

1. Confirm exact slice, scope, acceptance criteria, and verification commands.
2. Check the worktree and preserve unrelated user changes.
3. Establish a meaningful baseline when risk justifies it.
4. Implement only the current slice.
5. Stop for owner coordination if the roadmap definition is materially unclear
   or wrong, the owner boundary changes, blast radius expands, or the slice
   conflicts with current code.
6. Update contracts, docs, guards, and verification in the same slice when the
   code change changes them.
7. If implementation closes a roadmap's known gap, current site, scope, or
   verification assumption, update the roadmap from plan-state wording to
   evidence-state wording.
8. After rename, dependency, boundary, or compatibility-removal work, suggest or
   run `roadmap-residue-scan` when available before declaring the slice
   complete.

## Delivery

In review mode, lead with findings.

Use severity only when it helps:

- **High**: blocks execution, creates an incorrect owner boundary, or likely
  causes implementation churn/failure.
- **Medium**: should be fixed before implementation; otherwise likely causes
  scope creep, weak proof, or ambiguous ownership.
- **Low**: clarity or maintainability improvement that does not block execution.

End reviews with one concrete conclusion:

- `Fix F1/F2 before implementation.`
- `Executable for the next slice; roadmap remains active for later phases.`
- `Mainline can proceed after Slice N; residual phases remain tracked.`
- `No blocking findings; remaining items can be handled during implementation.`
- `Too broad; split before executing.`

After edits, summarize files changed, major boundary decision, whether inventory
was created, unresolved decisions, and verification run or not run. If only the
roadmap changed, say no code behavior changed.

## High-Risk Failure Modes

- Accepting the user's proposed abstraction as real without challenging owner,
  invariant, failure mode, and deletion path first.
- Treating target or direction docs as current implementation proof.
- Overselling stopgap hygiene as the strategic boundary fix.
- Hiding a public exposure problem behind local import cleanup.
- Creating fake isolation with internal fat DTOs, mirrored DTOs, compatibility
  aliases, or wrappers that only forward to the same owner.
- Wrapping a clear mechanical operation in a `Command`/`Request` DTO, then
  letting policy, lifecycle, diagnostics, or future-extension fields accumulate
  inside it.
- Treating "thin DTO" as sufficient design when the internal seam should use an
  existing owner-stable model, opaque frame/handle, or explicit primitive.
- Promoting address, correlation, diagnostics, or evidence into lifecycle or
  scheduling truth.
- Treating diagnostics or observability as harmless completeness work, then
  coupling snapshot viewers, read models, schemas, public DTOs, or policy /
  lifecycle / dispatch dependencies into the mainline.
- Letting non-owner modules maintain lifecycle truth or promise strong
  consistency without a named high-ROI invariant.
- Polishing strategy variants, corner cases, diagnostics, rename, guard
  automation, or transport/API reshaping before the mainline mechanism,
  boundary API, lifecycle path, and current callers are closed.
- Adding threads, scanners, locks, transactions, queues, indexes, background
  jobs, or infra operations without cost/blast-radius assessment and
  cheaper-alternative rejection.
- Trusting module/package names over actual production call sites.
- Reusing broad tests or CI green as proof when they preserve old behavior or
  skip the focused invariant.
- Editing adjacent roadmap files because the vocabulary looks related.
- Marking a roadmap complete when only one slice or prerequisite unblocker is
  done.
- Archiving without residue scan, active-link cleanup, and owner-doc fact
  migration.
