---
name: roadmap-refinement
description: Review, refine, create, or repair architecture and implementation roadmaps with code-first owner review, boundary analysis, executable slices, inventory planning, acceptance criteria, guardrails, and verification commands. Use when the user asks to review or update a roadmap, assess whether a roadmap is executable, decide whether work belongs in an existing roadmap or a separate one, create a module-boundary roadmap, perform owner review, or produce/update a caller/dependency/boundary inventory before implementation.
---

# Roadmap Refinement

Use this skill to turn vague or drifting roadmap work into an executable,
code-grounded plan. Default to owner review: validate the real code path first,
then refine the roadmap.

## Mode Rule

Choose the mode from the user's request before editing anything.

- **Review mode**: If the user asks to review, assess, or give findings, return
  findings first and do not modify files.
- **Edit mode**: If the user asks to fix, update, repair, rewrite, or create a
  roadmap, edit roadmap/docs directly.
- **Implementation mode**: If the user asks to execute an approved roadmap,
  make code/doc changes only within the approved slice or report when the
  roadmap is unclear.

In review mode, require or recommend an inventory when needed, but do not
create files unless the user asks for edits.

Use the lightest response shape that fits the task. Simple one-file roadmap
checks can be short. Use the full findings table for owner review,
multi-module boundary work, high-severity issues, or when the user explicitly
asks for a full review.

## Operating Rules

- Treat code and verified behavior as stronger evidence than direction docs.
- Do not document target state as current implementation.
- Keep each slice independently verifiable: compilation and the relevant test
  set should pass after the slice lands.
- Do not create "break now, fix later" intermediate roadmap states.
- Separate production dependencies from test fixtures.
- Separate owner boundaries from implementation convenience.
- Prefer deleting stale parallel narratives over preserving old and new tracks.
- Do not add compatibility aliases unless the user explicitly requires external
  compatibility.
- Do not create wrapper/facade/bridge layers unless they protect a real owner
  boundary, protocol seam, lifecycle split, or external caller surface.
- Do not merge unrelated boundary work into one roadmap because the dependency
  name looks similar.
- Do not trust a roadmap `Status:` line without checking current code and, when
  useful, recent commits or archive location.

## Convergence Rhythm

Boundary roadmaps follow layered convergence, not a flat checklist.

**Phase 1 - Identify**:
inventory callers, classify current usage, verify docs against code, and
decide ownership.

**Phase 2 - Converge**:
move consumers to the correct owner, narrow contracts, retarget adapters, and
update assembly. Each slice must leave the repo compiling and the relevant
test set passing.

**Phase 3 - Remove Residue**:
delete stale code, stale vocabulary, compatibility paths, and add guards that
prevent regression.

Do not jump to Phase 3 before Phase 2 is complete. No slice should require a
later slice to restore compilation or runtime correctness.

Every boundary roadmap should include a "Do Not Start With" note that names
the most tempting wrong-order shortcut, such as deleting dependencies before
moving callers or deleting writes before replacing read models.

## Workflow

### 1. Establish Scope

Determine whether the task is:

- roadmap review
- roadmap creation
- roadmap repair after review findings
- implementation planning for an approved roadmap
- dependency or owner-boundary convergence

Locate the current roadmap, related module README/CONTRACTS files, repo
handoff instructions, and the repository's roadmap/doc index when present.

If the user asks whether to merge with an existing roadmap, answer from owner
boundary, caller set, blast radius, and proof set.

### 2. Inspect Current Code

Use fast source search before editing. Prefer `rg`.

Check:

- main-source imports and call sites
- test-only imports separately
- Maven/Gradle dependencies and scopes
- controller/API routes
- existing architecture guards
- storage/runtime/transport ownership docs
- companion docs referenced by the roadmap header or body
- related active roadmaps from doc indexes, explicit links, and nearby roadmap
  directories
- stale roadmap or inventory documents
- recent commits when a roadmap status may be stale

When reporting facts, distinguish:

```text
current code says ...
target roadmap says ...
gap is ...
```

For referenced docs, verify that files exist and do not contradict the
roadmap's boundary decision. Do not treat referenced direction docs as proof
of current behavior unless the current code also confirms it.

For related roadmaps, check status, Non-Goals, pending slices, acceptance
criteria, and dependency assumptions. Flag conflicts or required cross-links.

For stale status detection:

- Verify `Status: proposed`, `active`, or `complete` against source code,
  guards, tests, and recent commits when available.
- Treat archived roadmaps as historical context, not active truth, unless the
  repo explicitly says otherwise.
- If status and code disagree, report doc drift in review mode or update the
  roadmap status in edit mode.

### 3. Classify Roadmap Portfolio State

When a repository has many roadmaps, classify each roadmap by current code
state, not only by its `Status:` line.

Use this taxonomy:

| Class | Meaning | Action |
| --- | --- | --- |
| proposed | target direction exists, no meaningful implementation has landed | review/refine before execution |
| active | implementation is in progress and slices remain | continue from the current slice |
| implemented | acceptance appears satisfied in code/tests/docs | update status and proof; consider archive |
| implemented-with-residue | mainline is done but old names/imports/docs/aliases remain | run residue scan before archive |
| superseded | a newer roadmap owns the direction | mark pointer to replacement; archive when safe |
| blocked | owner decision, external dependency, or proof gap blocks execution | record decision needed |
| stale-status | status line disagrees with code or commits | repair status before planning work |
| historical | archived or obsolete context only | do not execute; use only for background |

For portfolio review, produce a table:

```markdown
| Roadmap | Status Line | Code Evidence | Class | Next Action |
| --- | --- | --- | --- | --- |
```

Do not archive just because code exists. Archive only after status, proof,
remaining residue, and replacement links are clear.

### 4. Require, Propose, Or Create Inventory

Inventory is often the first real deliverable for boundary work, but it should
match the mode.

- Review mode: say an inventory is required or recommended; do not create it.
- Edit mode: create or update a sibling `*_INVENTORY.md` when conditions match.
- Implementation mode: create or update inventory only if the approved roadmap
  calls for it or the code path is materially unclear.

Use an inventory when any of these are true:

- many callers or modules are involved
- production and test usage must be separated
- a dependency is being moved or removed
- ownership is unclear
- current implementation and target docs disagree
- the first roadmap slice is classification

Do not create an inventory for a trivial one-file documentation correction.

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

Useful classifications:

- runtime truth
- control-plane declaration
- storage adapter
- read model
- compatibility residue
- admin/bootstrap
- transport/session evidence
- test fixture
- stale documentation

### 5. Owner Review

Before rewriting a roadmap, state the owner decision explicitly.

Use this pattern:

```text
<Domain object> belongs to <owner>.
<Other module> may consume it through <contract>, but must not define it.
<Implementation module> is an adapter, not the contract owner.
```

For repository-specific ownership rules, load and follow the repository's
handoff docs and module contracts. Do not apply one repository's owner split to
another repository unless the current repo has the same documented boundary.

For XA Mass Platform roadmaps, load
`references/xa-mass-owner-rules.md` from this skill directory if present.

### 6. Decide Merge Versus Separate Roadmap

Merge into an existing roadmap only when all are true:

- same owner boundary
- same caller family
- same proof and guard set
- same implementation sequence
- no existing Non-Goal is violated

Create a separate roadmap when any are true:

- the work has a different owner boundary
- proof commands differ materially
- one track can finish while the other is blocked
- the shared label is superficial, such as both touching `storage`
- merging would turn the roadmap into a broad cleanup bucket

When splitting, cross-link the roadmaps and state why they are separate.

### 7. Roadmap Structure

Use this as a flexible skeleton, not a rigid template:

```markdown
# <Roadmap Title>

Status: proposed direction document.

## Current Facts

or

## Current Code Observations

## Owner Review

## Boundary Decision

## Target Shape

## Non-Goals

## <SLICE-0> Inventory And Classification

Scope:

Acceptance:

## <SLICE-1> ...

## Suggested Implementation Order

## Verification Candidates
```

`Owner Review` is recommended for complex module-boundary work. `Target Shape`
is optional when `Boundary Decision` already contains the target. Topic-specific
review sections such as dependency convergence or API compatibility are valid
when they make the roadmap easier to review.

Keep slices executable. Each slice should have:

- goal
- scope
- acceptance
- no hidden behavior changes unless explicitly stated
- a stable verification point

Include a "Do Not Start With" note for boundary roadmaps that are likely to
tempt agents into the wrong order.

### 8. Slice Ordering

Map slices to the convergence rhythm.

Identify:

1. inventory/classification
2. contract/owner decision

Converge:

3. move or narrow contracts
4. retarget implementations/adapters
5. update SDK/server/test assembly

Remove residue:

6. add guards and proof
7. remove residue and stale docs

Do not start by deleting dependencies. First prove what owns the contract, move
callers, then remove the dependency. Each step should be commit-sized or
phase-sized and independently verifiable.

### 9. Acceptance Criteria

Acceptance must be testable in code review.

Good:

- `xa-mass-worker-runtime` main sources no longer import
  `com.xa.mass.storage.api.WorkerDeclaration*`.
- Architecture guard fails if engine runtime packages import
  `com.xa.mass.storage.api.projection.*`.
- `mass-storage-memory` implements the worker-runtime declaration port as an
  adapter.

Weak:

- "Clean up dependencies."
- "Improve architecture."
- "Make the boundary clearer."
- "Consider adding a guard."

### 10. Guards And Verification

For every boundary decision, prefer at least one guard:

- forbidden import/package scan
- Maven dependency-scope guard
- architecture test
- contract-shape allowlist
- route naming guard
- proof registry or testing index update

Verification candidates should be concrete commands. If exact tests are not
known yet, say they must be corrected after inventory.

### 11. Implementation Mode Rules

When executing a roadmap slice:

1. Confirm the exact slice, scope, acceptance criteria, and verification
   commands before editing.
2. Check the worktree and avoid reverting unrelated user changes.
3. Establish a baseline when risk is meaningful: compile, focused tests, or at
   minimum inspect recent failures so pre-existing failures are not confused
   with new regressions.
4. Implement only the current slice. Do not opportunistically pull in future
   slices.
5. If the roadmap conflicts with current code or the slice requires a larger
   owner decision, stop implementation and return to roadmap refinement.
6. If compilation fails because of this slice, fix it within scope before
   continuing.
7. If tests fail:
   - fix failures clearly caused by the slice
   - record clearly pre-existing failures without expanding scope
   - reduce ambiguous failures to a focused repro before deciding
8. If fixing requires changing contracts, docs, guards, or verification, update
   them in the same slice.
9. End the slice at a stable point: relevant compile/tests pass, guards are in
   place, docs match behavior, and the repo is ready for a phase commit.
10. After a rename, dependency, boundary, or compatibility-removal slice,
    suggest or run `roadmap-residue-scan` when available before declaring the
    slice complete.

### 12. Review Delivery Format

In review mode, lead with findings.

Use this structure:

```markdown
## <Roadmap> Review

### Findings

**F1 - <title>**
Severity: High | Medium | Low
Evidence: <file:line or method/class reference>
Impact: <why it matters>
Recommendation: <specific fix>

### Summary

| Finding | Severity | Blocks execution? |
| --- | --- | --- |
| F1 | High | Yes |

Conclusion: <formula>
```

Severity meanings:

- **High**: blocks execution, creates an incorrect owner boundary, or would
  likely cause implementation churn/failure.
- **Medium**: should be fixed before implementation when practical; otherwise
  likely causes scope creep, weak verification, or ambiguous ownership.
- **Low**: clarity, maintainability, or follow-up improvement that does not
  block execution.

Use conclusion formulas:

- `Fix F1/F2 before implementation.`
- `Executable after the named Medium findings are clarified.`
- `No blocking findings; remaining items can be handled during implementation.`
- `Too broad; split into separate roadmaps before executing.`

### 13. Final Response

After editing, summarize:

- files changed
- major boundary decision
- whether an inventory was created
- unresolved decisions
- verification run or not run

State whether the roadmap is:

- executable
- executable after named decisions
- blocked
- too broad and should be split

Do not overstate completion. If only the roadmap changed, say no code behavior
changed.

## Anti-Patterns

- Treating a direction doc as proof of current behavior.
- Combining projection, rule, worker lifecycle, and task shell work into one
  roadmap because they all touch storage.
- Leaving "or document it" as an escape hatch for a known production boundary
  problem.
- Creating compatibility aliases inside the repo after moving all callers.
- Letting tests preserve old vocabulary as a hidden second API.
- Hiding production dependency removal under a documentation-only slice.
- Writing acceptance criteria that cannot fail.
- Starting with a large rename before call sites and owner decisions are known.

## Quick Checklist

Before finishing a roadmap refinement:

- Current code observations are verified with source search.
- Target state is not described as already implemented.
- Roadmap `Status:` was checked against code, tests, guards, or commits when
  plausibly stale.
- Portfolio state was classified when the request involves many roadmaps.
- Mode was respected: review-only did not edit files.
- Companion docs referenced by the roadmap exist and do not contradict it.
- Related roadmap Non-Goals and acceptance criteria do not conflict.
- Public SDK/API breaking changes are called out when present.
- File, class, method, and route names match current code.
- Non-goals prevent scope creep.
- First slice inventories ambiguous caller/dependency sets.
- Each slice has scope and acceptance.
- Each slice is independently verifiable; no break-now-fix-later state exists.
- A "Do Not Start With" warning exists for boundary roadmaps with tempting
  wrong-order shortcuts.
- Merge/split decision is justified by owner boundary and proof set.
- Verification commands are present and reference real modules/tests where
  known.
- Remaining decisions are explicit.
