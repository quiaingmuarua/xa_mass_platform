# Testing Proof System Hardening Roadmap

Status: mainline implementation complete; external CI observation pending.

Predecessors:

- `doc/archive/xa-mass-testing/2026-06-09_PLATFORM_PROOF_CREDIBILITY_ROADMAP.md`
- `doc/archive/xa-mass-testing/2026-06-09_PLATFORM_PROOF_CREDIBILITY_INVENTORY.md`

Current owner references:

- `doc/TESTING_INDEX.md`
- `doc/PROOF_REGISTRY.md`
- `xa-mass-testing/README.md`
- `xa-mass-testing/VERIFIED_RUNBOOK.md`
- `xa-mass-testing/scripts/write-proof-summary.mjs`

Implementation records:

- `roadmap/TESTING_PROOF_SYSTEM_HARDENING_INVENTORY.md`
- `roadmap/TESTING_PROOF_SYSTEM_HARDENING_CORRECTNESS_GAP_MAP.md`

Current slice state:

- TPS-0 inventory and TPS-3 correctness gap map exist.
- TPS-1/TPS-2 baseline is implemented in the platform confidence and default
  startup runners: runner-native authorized-positive evidence is emitted, the
  representative no-bypass matrix exists, and writer fallback is retained only
  for older local artifacts.
- TPS-4/TPS-5/TPS-6 baseline is implemented in proof summary normalization,
  release-evidence metadata, guard tests, and residue/closure review.
  External CI observation is still needed before treating the branch as observed
  in GitHub Actions, but no remaining mainline implementation slice is known.

Current verification ledger:

- `memory-local` platform confidence packaged-process smoke passed locally on
  2026-06-09 and produced runner-native Product/API capability plus
  authorization no-bypass evidence.
- `durable-local` platform confidence packaged-process smoke passed locally on
  2026-06-09 with Redis namespace isolation and produced the same runner-native
  evidence shape.
- No-arg default startup observation passed locally on 2026-06-09 through WSL
  loopback while the Windows IDE server still occupied Windows `127.0.0.1:8088`.
  The smoke started the packaged jar with no application arguments, observed
  default `durable-local`, passed health and operator login twice, reused the
  same SQLite file, and produced separate Product/API capability and scoped
  restart resilience evidence.
- The default startup runner also records a Windows-side port precheck blocker
  as `status=blocked`, `category=port-precheck`; the summary writer keeps that
  artifact as `artifact-metadata` without increasing startup, restart, or
  operation-level proof counts.
- The focused `xa-mass-testing` proof guard suite and engine deterministic
  proof suites pass locally with the current baseline. Full CI observation
  remains pending as external release evidence, not as an unimplemented
  roadmap slice.

## Closure Audit

| Requirement | Current evidence | Status |
| --- | --- | --- |
| Every major proof summary item has proof class, proof line, evidence shape, gate type, claim scope, source artifact, and known non-proof boundaries | `write-proof-summary.mjs` emits those fields; `ProofSummaryWorkflowGuardTest` fixture covers surefire, platform confidence, default startup, chaos, perf, and soak inputs | implemented |
| Proof, guard, support, and artifact metadata use separate counting semantics | `evidenceRole`, `proofClassCounts`, `proofLineCounts`, `guardCounts`, and `guardProofLineCounts` are emitted and fixture-tested | implemented |
| Operation-level totals count only executed checks | `operationCheckExecuted(...)` filters `authorizedPositiveChecks` and `credentialChecks`; port-precheck fixture proves `not-run` checks stay out of totals | implemented |
| Product/API capability checks are runner-native | `run-platform-confidence-smoke.sh` and `run-server-default-startup-smoke.sh` emit `authorizedPositiveChecks`; memory/durable/default startup runtime summaries were locally observed | implemented |
| Representative no-bypass checks are structured and bounded | `proof/authorization-no-bypass-matrix.json`, runner `credentialChecks`, writer normalization, and `AuthorizationNoBypassMatrixGuardTest` cover this | implemented |
| Default startup capability and restart resilience are separate evidence claims | writer emits `server-default-startup` and `server-default-startup-restart`; no-arg startup/restart passed locally | implemented |
| Policy/lifecycle correctness is engine-first | `TESTING_PROOF_SYSTEM_HARDENING_CORRECTNESS_GAP_MAP.md`, `ProofRegistryClosureGuardTest`, and `EngineSchedulingCoreSuite` / `EngineKernelConvergenceSuite` preserve this routing | implemented |
| Chaos/perf/soak cannot imply broad resilience without explicit scenario contract | report normalization downgrades missing contracts to `artifact-metadata`; fixture covers downgraded chaos plus complete perf/soak reports | implemented |
| Scheduled/manual evidence has promotion and demotion criteria | `perf-soak-release-evidence.json` names owner, gate eligibility, promotion criteria, demotion triggers, thresholds, and trends; guard test covers it | implemented |
| Active docs and guards prevent stale proof wording | `doc/TESTING_INDEX.md`, `xa-mass-testing/README.md`, writer definitions, guard tests, workflow checks, and residue scan keep E2E/chaos/perf/soak as evidence shapes, not broad proof classes | implemented |

External CI observation remains a release confidence step: the GitHub workflows
are wired to run and upload proof summaries, but this local implementation
record does not claim the branch has already been observed green in GitHub
Actions.

## Purpose

The first platform proof credibility baseline made CI evidence more visible:
proof summaries now name proof classes, proof lines, evidence shape, gate type,
credential families, known non-proof boundaries, and scheduled/manual evidence.

This roadmap hardens that baseline. The goal is not more test volume and not a
larger E2E matrix. The goal is to make proof claims mechanically honest around
three questions:

1. Can the platform be used through supported external product/API paths?
2. Can the platform authorize, schedule, bind, or mutate incorrectly?
3. Can the platform withstand this explicitly named load, fault, runtime,
   duration, and oracle?

The second question is the highest-priority confidence question. A green happy
path, a perf run, or a chaos label does not compensate for wrong authorization,
wrong worker selection, wrong lifecycle mutation, or bypassed policy.

## Current Code Observations

- `doc/TESTING_INDEX.md` defines three project proof classes:
  `Product / API Capability Proof`, `Policy & Safety Correctness Proof`, and
  `Scoped Operational Resilience Proof`.
- `doc/TESTING_INDEX.md` also says E2E is an evidence shape, not a proof class,
  and policy correctness is engine deterministic proof first. Server E2E is
  representative real-wiring proof only.
- `doc/PROOF_REGISTRY.md` is the current invariant ownership ledger. It maps
  critical invariants to primary deterministic proof, representative integrated
  proof, trace proof, distributed-edge proof, status, and "do not duplicate"
  guidance.
- `doc/PROOF_REGISTRY.md` separates positive authorization capability from
  negative no-bypass safety: a correct credential/session wrongly rejected on a
  correct route/scope is a Product/API Capability failure; an incorrect
  credential, scope, route family, CSRF state, fixture/dev header, or
  impersonated worker that succeeds belongs to `authorization-no-bypass-safety`.
- `xa-mass-testing/scripts/write-proof-summary.mjs` emits proof class and proof
  line definitions, per-evidence `proofClass`, `proofLines`, `proofQuestion`,
  `evidenceShape`, `gateType`, `claimScope`, credential route families,
  authorized-positive checks, negative credential checks, scenario ids, trace
  analyzer ids, scheduled/manual evidence, and known non-proof boundaries.
- Proof, guard, and artifact metadata must not share one counting semantic.
  Evidence must declare an `evidenceRole`; only real executed proof roles such
  as `runtime-proof`, `deterministic-proof`, and `integrated-proof` may increase
  `proofClassCounts` or `proofLineCounts`. Guards must use roles such as
  `source-guard`, `schema-guard`, or `release-policy-guard` and count only in
  guard-specific totals.
- Operation-level credential totals must count only checks that actually
  executed with `passed` or `failed` status. `not-run` and `not-confirmed`
  checks stay visible in artifacts but do not increase operation proof counts.
- `ProofSummaryWorkflowGuardTest` now includes an output-level fixture test for
  proof summary structure, not only source-token guards. That test currently
  covers surefire XML plus platform confidence, default startup, chaos, perf,
  and soak input directories.
- `run-platform-confidence-smoke.sh` starts a packaged server process, disables
  fixture-header auth, reads `/api/v1/auth/config`, runs admin CLI, Java SDK
  task producer, Java SDK or worker-api worker paths, result verification, and
  representative negative credential checks through external processes.
- The platform confidence runner summary records auth mode, fixture header
  state, route families, runner-native authorized-positive operation checks,
  negative credential checks, failure reasons, and source log paths. The proof
  summary writer consumes the runner-native operation manifest and keeps only a
  compatibility fallback for older local summaries.
- `run-server-default-startup-smoke.sh` covers the no-arg packaged server
  startup path, default durable-local profile/path evidence, health, operator
  login readiness, and restart against the same SQLite file.
- Chaos/perf/soak evidence is now classified as scoped operational resilience.
  It must stay scoped to scenario id, runtime backend, transport, fault or load,
  duration/volume, pass/fail oracle, and gate type. Scheduled/manual perf and
  soak are not PR gates until calibrated promotion criteria are met.
- `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` owns broader API contract,
  route/auth/DTO/SDK/frontend contract health. This roadmap should not absorb a
  full route-permission matrix.

## Owner Review

- `doc/TESTING_INDEX.md` owns the project-level testing map, proof class
  vocabulary, CI truth, and change-type verification rules.
- `doc/PROOF_REGISTRY.md` owns critical invariant proof placement. It is the
  default answer to "where should the next proof go?"
- `xa-mass-engine` owns primary deterministic scheduling and kernel correctness
  proof. Policy correctness starts there.
- `xa-mass-server` E2E suites own representative real server, HTTP, auth,
  SDK/worker, transport, and Spring wiring proof. They must not become the full
  scheduling, lifecycle, credential, or route matrix.
- `xa-mass-testing` owns packaged-process confidence runners, proof summary
  generation, chaos/perf/soak evidence, and workflow evidence guards.
- `xa-mass-trace` owns canonical observational proof through trace analyzers
  and trace-observed scenarios.
- API contract and permission-shape breadth belongs to the server API contract
  health lane, not to platform confidence smoke.

## Boundary Decision

The proof system should be hardened by improving evidence quality and proof
placement, not by broadening every lane.

- Product/API capability proof must be runner-native and operation-level. It
  should prove that valid operator sessions, task producer API keys, and worker
  API keys can use their supported external route families without being
  wrongly rejected.
- Policy and safety proof must be invariant-first. Engine deterministic proof
  is primary for scheduling and lifecycle correctness; server E2E is added only
  for representative host/API/transport/Spring wiring risk.
- Authorization no-bypass proof must use negative cases. It should not be
  inferred from a successful happy path.
- Operational resilience proof must name the exact condition. A chaos, perf, or
  soak artifact without explicit fault/load/runtime/duration/oracle remains
  evidence, but not a broad resilience proof.
- The same runner may produce more than one claim only when the claims are
  separate evidence items. For example, default startup health/operator login is
  Product/API capability, while same-SQLite restart/idempotence is Scoped
  Operational Resilience.
- Taxonomy duplication between docs and `write-proof-summary.mjs` is acceptable
  for the current baseline because output-level tests guard the emitted JSON.
  A shared JSON taxonomy can be considered later only after runner-native
  manifests stabilize.

## Non-Goals

- No production API, auth, scheduling, or lifecycle behavior change in this
  roadmap by itself.
- No new public SDK surface solely for testing proof.
- No full server E2E matrix for worker selection, lifecycle states, active
  profiles, route permissions, or credential scopes.
- No attempt to turn scheduled/manual perf or soak into PR gates without
  calibrated thresholds and flake evidence.
- No claim that current Redis evidence proves process kill, partition/failover,
  lease-clock skew, or multi-node presence flap until a deterministic
  infra-fault harness exists.
- No new implementation language for proof tooling. Use existing shell, Java,
  or Node-based tooling.
- No dashboard or UI before the artifact schema is honest enough to review.

## Do Not Start With

Do not start by adding more E2E cases, broad chaos jobs, perf volume, soak
duration, or dashboards. Start by making each existing claim observable,
runner-native, and correctly classified. Then add only the missing proof in the
owner lane that can prove the risk with the least ambiguity.

## TPS-0 Evidence Inventory And Claim Audit

Scope:

- Create a current evidence inventory for proof summary sources:
  surefire XML, platform confidence summaries, server default startup
  summaries, chaos reports, perf reports, soak reports, release-evidence config,
  workflow upload paths, and proof registry guards.
- Classify each field as one of:
  runner-native evidence, writer-derived inference, source artifact pointer,
  proof registry lookup, trace analyzer lookup, scheduled/manual release
  interpretation, or known non-proof boundary.
- Classify each evidence item with an `evidenceRole`: `runtime-proof`,
  `deterministic-proof`, `integrated-proof`, `source-guard`, `schema-guard`,
  `release-policy-guard`, `support-proof`, or `artifact-metadata`.
- Identify where runner outputs are too weak to support the current summary
  claim without inference.
- Decide whether a shared taxonomy JSON is needed now. The default answer for
  this slice should be "not yet" unless inventory proves docs/writer drift is
  already a practical problem.

Acceptance:

- A sibling inventory document exists under `roadmap/` and names each current
  evidence source, its proof class/line mapping, and any inferred fields.
- `proofClassCounts` and `proofLineCounts` are reserved for executed proof
  roles only. Guard evidence must not increase those totals; it must use
  `guardCounts` or `guardProofLineCounts`.
- The inventory explicitly lists non-proof boundaries for current chaos/perf/
  soak/Redis/startup claims.
- The inventory names the first implementation slice after audit. It must not
  recommend broad E2E expansion as the first fix.

Suggested verification:

```bash
rg -n "proofClass|proofLines|authorizedPositiveChecks|credentialChecks|knownNonProofBoundaries" xa-mass-testing/scripts/write-proof-summary.mjs xa-mass-testing/scripts
rg -n "Product / API Capability|Policy & Safety|Scoped Operational Resilience|E2E is an evidence shape" doc/TESTING_INDEX.md doc/PROOF_REGISTRY.md
```

## TPS-1 Runner-Native Product/API Capability Evidence

Scope:

- Update `run-platform-confidence-smoke.sh` and
  `run-server-default-startup-smoke.sh` so they emit operation-level
  authorized-positive evidence directly instead of requiring
  `write-proof-summary.mjs` to infer operations from log paths and overall
  status.
- Each operation should name:
  operation id, credential/session family, route family, expected auth mode,
  expected authorization outcome, observed status, source process, source
  artifact, and failure reason when available.
- Keep the product/API capability scope representative:
  operator login/env init/task approve, task producer create/append/read, worker
  register/poll/submit result, and default-startup health/operator login.
- Preserve the current profile boundary: explicit active-profile confidence is
  separate from no-arg default startup/restart proof.
- Preserve the default-startup claim split: health and operator login are
  Product/API capability; same-SQLite restart/idempotence is a separate Scoped
  Operational Resilience evidence item.
- Keep local fixture/dev header disabled in the confidence lane and keep
  `operatorHeaderSupported=false` observable.

Acceptance:

- Platform confidence and default startup summary JSON contain
  `authorizedPositiveChecks` emitted by the runner.
- `write-proof-summary.mjs` consumes runner-emitted authorized-positive checks
  and only uses compatibility fallback for old local artifacts.
- `ProofSummaryWorkflowGuardTest` fixture asserts that runner-emitted operation
  evidence is preserved exactly, including credential family and source
  artifact.
- Default-startup summary output has separate Product/API capability and Scoped
  Operational Resilience evidence items instead of one mixed proof claim.
- A correct credential/session wrongly rejected by auth, CSRF, route mapping, or
  credential-family handling is reported as Product/API Capability failure, not
  as authorization no-bypass success.

Suggested verification:

```bash
node --check xa-mass-testing/scripts/write-proof-summary.mjs
bash -n xa-mass-testing/scripts/run-platform-confidence-smoke.sh
bash -n xa-mass-testing/scripts/run-server-default-startup-smoke.sh
./mvnw -q -pl xa-mass-testing -am -Dtest=ProofSummaryWorkflowGuardTest,AuthorizationNoBypassMatrixGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## TPS-2 Representative Authorization No-Bypass Matrix

Scope:

- Define a small representative negative auth matrix. It should prove
  fail-closed behavior, not every server route.
- Candidate rows:
  missing operator session on an operator route, invalid task API key on a task
  producer route, invalid worker API key on a worker route, task producer key on
  an operator command route, worker key on a task producer route, wrong
  project/scope/event, worker impersonation during result submit, missing CSRF
  on a session mutation, and fixture/dev header rejection in confidence mode.
- For each row, name the owner:
  platform confidence, API contract health, engine deterministic policy proof,
  or deferred follow-up.
- Capture structured failure evidence:
  expected HTTP status family, response code/message, credential family,
  route family, proof line, and failure reason.
- Keep this separate from positive capability proof. A correct credential that
  fails is not a negative no-bypass pass.

Acceptance:

- A machine-readable or table-backed no-bypass matrix exists in the owning
  testing/proof location.
- The platform confidence runner emits the rows it owns as structured
  `credentialChecks`.
- `write-proof-summary.mjs` preserves each check with
  `proofLine=authorization-no-bypass-safety` and a concrete `failureReason`.
- Rows owned by API contract health are linked, not duplicated in platform
  confidence.
- The matrix is representative and bounded; adding a new route family does not
  automatically require a full platform confidence permutation.

Suggested verification:

```bash
node --check xa-mass-testing/scripts/write-proof-summary.mjs
./mvnw -q -pl xa-mass-testing -am -Dtest=ProofSummaryWorkflowGuardTest,AuthorizationNoBypassMatrixGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## TPS-3 Engine-First Correctness Gap Map

Scope:

- Review `doc/PROOF_REGISTRY.md` and engine baselines to identify current gaps
  in `scheduling-policy-correctness` and `lifecycle-result-correctness`.
- For each gap, decide whether it needs:
  an engine deterministic test, a representative server E2E, a trace analyzer,
  a chaos/perf/soak edge, or no new test.
- Prioritize "can it be wrong?" cases:
  wrong worker selected, disallowed worker not excluded, wrong admission,
  readiness/occupancy/capacity/lock bypass, retry/wakeup/lease-expiry binding
  without policy re-entry, duplicate/stale result mutation, finality corruption,
  and resource-release mistakes.
- Do not add server E2E permutations for policy branches that can be proven in
  engine deterministic tests.

Acceptance:

- Each new or changed Policy & Safety proof row names a primary deterministic
  owner unless the risk is explicitly host/API/transport-only.
- Any new representative server E2E explains the host wiring risk it proves and
  links back to the primary deterministic proof.
- Proof registry rows remain resolvable by the existing closure guard.
- Existing mainline-suite guards continue to prevent support or secondary
  coverage from leaking into primary proof suites.

Suggested verification:

```bash
./mvnw -q -pl xa-mass-testing -am -Dtest=ProofRegistryClosureGuardTest,ProofSummaryWorkflowGuardTest,AuthorizationNoBypassMatrixGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl xa-mass-engine -am -Dtest=EngineSchedulingCoreSuite,EngineKernelConvergenceSuite -Dsurefire.failIfNoSpecifiedTests=false test
```

## TPS-4 Scoped Resilience Scenario Contract

Scope:

- Normalize chaos/perf/soak reports around explicit scenario contracts:
  `scenarioId`, proof line, runtime backend, transport, worker profile, load or
  fault shape, duration or volume, pass/fail oracle, threshold signals, trend
  signals, trace analyzers, gate type, and known non-proof boundaries.
- Keep PR-gated resilience small and deterministic:
  lease-expiry redispatch, retry redispatch, duplicate/stale result handling,
  selected reconnect recovery, and small contention cases.
- Keep expensive or noisy evidence scheduled/manual until calibrated:
  high volume, long soak, Redis process kill, partition/failover, lease-clock
  skew, multi-node presence flap, and long worker churn.
- Update proof summary behavior so reports missing the scenario contract are
  downgraded or marked `unknown` rather than promoted into broad resilience
  claims.

Acceptance:

- Chaos/perf/soak evidence in proof summary has enough fields to answer
  "can it withstand this exact condition?"
- If a chaos/perf/soak artifact lacks required scenario contract fields, the
  summary must set `status=unknown` or `status=downgraded`, assign
  `evidenceRole=artifact-metadata`, leave `proofLines=[]`, and avoid increasing
  `fault-recovery-evidence` or `scale-contention-evidence` counts.
- Downgraded resilience artifacts must include an explicit
  `knownNonProofBoundaries` entry naming the missing contract field or oracle.
- Scheduled/manual evidence remains visibly scheduled/manual in summaries and
  release-evidence interpretation.
- Redis and infra-fault non-proofs remain explicit unless a report names and
  asserts the condition.
- Fixture tests cover resilience report normalization and downgrade behavior.

Suggested verification:

```bash
node --check xa-mass-testing/scripts/write-proof-summary.mjs
./mvnw -q -pl xa-mass-testing -am -Dtest=ProofSummaryWorkflowGuardTest,WorkerFaultScenarioIndexTest,WorkerFaultReportMetadataTest,PerfSoakReleaseEvidenceGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## TPS-5 Promotion And Release Evidence Governance

Scope:

- Define how scheduled/manual evidence can be promoted to PR gates.
- Promotion criteria should include:
  stable scenario id, deterministic pass/fail oracle, bounded runtime,
  acceptable flake rate, owned threshold signals, trend-only signal separation,
  artifact retention, and clear demotion rules.
- Update release-evidence metadata so perf and soak reports distinguish hard
  threshold signals from trend observations.
- Do not promote long-running or nondeterministic jobs just because their names
  sound important.

Acceptance:

- `xa-mass-testing/proof/perf-soak-release-evidence.json` or its successor
  names gate eligibility, owner, threshold signals, trend signals, promotion
  criteria, and demotion triggers.
- `doc/TESTING_INDEX.md` and `xa-mass-testing/README.md` continue to state PR
  gate versus scheduled/manual truth without overclaiming.
- A promoted job has a proof summary entry with `gateType=pr-gate` or an
  equally explicit value; unpromoted jobs stay scheduled/manual.

Suggested verification:

```bash
./mvnw -q -pl xa-mass-testing -am -Dtest=PerfSoakReleaseEvidenceGuardTest,ProofSummaryWorkflowGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## TPS-6 Drift Guards And Roadmap Closure

Scope:

- Add or strengthen guards that prevent proof vocabulary, workflow evidence
  paths, profile matrices, and proof registry rows from drifting silently.
- Decide after TPS-1 through TPS-4 whether proof taxonomy should move into a
  shared JSON source consumed by docs/tests/scripts. Do this only if the
  runner-native manifest shape is stable enough to justify the extra owner.
- Remove stale wording that treats E2E, chaos, perf, soak, or startup smoke as a
  proof class rather than an evidence shape.
- Run a residue scan before archiving this roadmap.

Acceptance:

- Proof summary output tests cover every supported evidence source type.
- Proof summary output tests prove that guards do not increase
  `proofClassCounts` or `proofLineCounts`, and that guard-related proof lines
  are counted only under guard-specific totals.
- Workflow summary inputs remain job-scoped and artifact uploads remain
  present.
- Supported active profiles and platform confidence matrix remain mechanically
  guarded.
- Docs, scripts, and guard tests use the same proof class and proof line
  meanings.
- No active doc claims broad Redis HA, production SLO, full route-permission,
  full policy-matrix, or full scale proof without matching evidence.

Suggested verification:

```bash
./mvnw -q -pl xa-mass-testing -am -Dtest=ProofSummaryWorkflowGuardTest,PlatformConfidenceProfileMatrixGuardTest,ProofRegistryClosureGuardTest,AuthorizationNoBypassMatrixGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "E2E.*proof class|chaos.*full|perf.*full|soak.*full|Redis HA|partition/failover" doc roadmap xa-mass-testing
```

## Suggested Implementation Order

1. TPS-0: inventory current claims and inferred fields.
2. TPS-1 and TPS-2 together: make Product/API capability evidence and
   authorization no-bypass evidence runner-native using one shared operation/
   credential evidence envelope. TPS-1 is allowed to land first only when it
   creates the shared artifact structure that TPS-2 consumes immediately.
3. TPS-3: harden engine-first correctness gaps before adding server E2E.
4. TPS-4: normalize scoped resilience scenario contracts.
5. TPS-5: define promotion/demotion rules for scheduled/manual evidence.
6. TPS-6: tighten drift guards and archive only after residue scan.

TPS-1 must not become a polished happy-path-only slice. If it lands before
TPS-2, its acceptance must explicitly leave the shared operation/credential
manifest ready for the no-bypass matrix, and TPS-2 should be the immediate next
slice. TPS-3 should not wait for chaos/perf/soak work because "can it be wrong?"
is the higher-value confidence question. TPS-4 and TPS-5 should not promote
more runtime pressure into PR until the scenario contract and flake behavior are
explicit.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Every major proof summary evidence item has proof class, proof line, evidence
  shape, gate type, claim scope, source artifact, and known non-proof
  boundaries.
- Every evidence item has an `evidenceRole`; proof totals count executed proof
  only, while source/schema/release-policy guards and artifact metadata have
  separate counts.
- Operation-level `authorizedPositiveChecks` and `credentialChecks` totals count
  only executed checks, not blocked prechecks or inferred `not-confirmed`
  placeholders.
- Product/API capability checks are runner-native and operation-level for the
  supported operator, task producer, and worker paths.
- Representative negative authorization/no-bypass checks are structured,
  bounded, and clearly separated from positive capability proof.
- Default startup health/login and same-SQLite restart/idempotence are emitted
  as separate evidence claims with separate proof classes and proof lines.
- Policy and lifecycle correctness gaps are first routed to deterministic
  engine proof unless host/API/transport wiring is the actual risk.
- Chaos/perf/soak reports cannot imply broad resilience without explicit
  scenario condition and pass/fail oracle.
- Scheduled/manual evidence has promotion and demotion criteria before any job
  becomes a PR gate.
- The active docs and guard tests prevent drift without preserving stale
  parallel narratives.

Slice acceptance is not roadmap completion. Do not archive this roadmap until
the completion criteria above are satisfied and a residue scan confirms no
stale proof wording remains in active docs or scripts.
