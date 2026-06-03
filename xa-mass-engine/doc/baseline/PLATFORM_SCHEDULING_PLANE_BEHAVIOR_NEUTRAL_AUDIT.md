# Platform Scheduling Plane Behavior-Neutral Audit

Status: current Scheduling Plane proof note.

Source commit: `9381a13ca` (`Introduce Scheduling Plane resolved views`).

Scope: `RuleBasedTaskWorkerMatchingStrategyTest` assertion audit for the first
Scheduling Plane resolved-view landing.

This audit is the SPSP-1 behavior-neutral proof path selected by
`roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`.
It is an assertion audit, not a full historical golden baseline.

## Audit Result

The `RuleBasedTaskWorkerMatchingStrategyTest` diff in `9381a13ca` is
`23 insertions / 23 deletions`. The changed lines are rule fixture expressions,
not expected match outcomes.

Observed assertion stability:

| Proof surface | Audit result |
| --- | --- |
| accepted worker ids | unchanged by the landing commit |
| rejected worker ids | unchanged by the landing commit |
| matched worker count assertions | unchanged by the landing commit |
| assignment result assertions | unchanged by the landing commit |
| rejection reason assertions | unchanged by the landing commit |
| rule evaluation count assertions | unchanged by the landing commit |
| trace accepted/rejected assertions | unchanged by the landing commit |

The rule fixture changes replace runtime/live-evidence rule expressions with
declarative scheduling-context expressions:

| Old fixture pattern | New fixture pattern | Interpretation |
| --- | --- | --- |
| `isWorkerAvailable == true && isWorkerLocked == false` | `supportsProject == true && supportsEvent == true` | live reachability / lock state moved out of rule eligibility and remains prefilter / reserve / admission evidence |
| `isWorkerSchedulingResourceAllocatable == true` | `hasWorkerSchedulingResource == true` | rule surface keeps declarative scheduling-resource presence, not live admission state |

## Boundary Interpretation

The landing commit intentionally moved live worker evidence out of rule
eligibility. Therefore behavior-neutrality here means:

- the same asserted workers are accepted or rejected in the audited matching
  scenarios,
- rejection reasons and assignment result categories remain asserted,
- rule evaluation counts in audited scenarios remain asserted,
- live evidence is still enforced by prefilter, rank, reserve, lock, or
  admission, not by the rule DSL.

This audit does not claim that every unasserted diagnostic field or internal
trace attribute is byte-for-byte identical to the pre-Scheduling Plane
implementation.

## Follow-Up Proof

The remaining stabilization proof must be carried by characterization evidence
or integrated scheduling tests that change accepted/rejected workers,
assignment results, lifecycle state, or runtime ownership outcomes.

Support regressions may still cover resolver construction drift, but those
tests are not behavior-neutral proof by themselves:

- `DefaultSchedulingPlaneResolverTest`
- `RuleBasedTaskWorkerMatchingStrategyTest`

Allocation and budget object-shape tests are not listed here because they do
not prove behavior-neutrality or runtime policy outcomes. If allocation or
budget semantics need protection, use integrated scheduling outcomes such as
ready/inflight counters, active leases, assignment records, worker load, or
task status.

If future Scheduling Plane changes alter accepted/rejected workers, assignment
result categories, rejection reasons, or rule evaluation counts, this audit is
no longer sufficient. Add a new characterization/golden baseline or a new
commit-specific assertion audit.
