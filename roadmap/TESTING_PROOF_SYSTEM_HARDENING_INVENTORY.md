# Testing Proof System Hardening Inventory

Status: current evidence inventory for `TESTING_PROOF_SYSTEM_HARDENING_ROADMAP.md`.

Last updated: 2026-06-09

## Purpose

This inventory records the current proof-summary evidence sources and the
claim-strength boundary for each source. It exists so the hardening roadmap can
improve proof quality without inflating claims or expanding low-value E2E
matrices.

## Evidence Sources

| Source | Current artifact | Evidence role | Proof class / line mapping | Runner-native fields | Writer-derived fields | Boundary |
| --- | --- | --- | --- | --- | --- | --- |
| Surefire XML: `EngineSchedulingCoreSuite` | `target/*reports/TEST-*.xml` | `deterministic-proof` | `policy-safety-correctness` / `scheduling-policy-correctness` | suite name, tests, failures, errors, skipped | proof class, proof line, invariant ids, claim scope | deterministic engine proof only; not packaged process/API/auth proof |
| Surefire XML: `EngineKernelConvergenceSuite` | `target/*reports/TEST-*.xml` | `deterministic-proof` | `policy-safety-correctness` / `lifecycle-result-correctness` | suite name, tests, failures, errors, skipped | proof class, proof line, invariant ids, claim scope | deterministic lifecycle/result proof only |
| Surefire XML: representative server E2E suites | `target/*reports/TEST-*.xml` | `integrated-proof` | policy/safety proof lines by suite | suite name, tests, failures, errors, skipped | proof class, proof line, invariant ids, claim scope | representative real-wiring proof; not full policy/lifecycle matrix |
| Surefire XML: external worker parity | `target/*reports/TEST-*.xml` | `integrated-proof` | `task-producer-api-key`, `worker-api-key` | suite name, tests, failures, errors, skipped | proof class, proof line, invariant ids, claim scope | external contract proof, not full SDK transport fault matrix |
| Surefire XML: closure/profile/fault/release guards | `target/*reports/TEST-*.xml` | `source-guard`, `schema-guard`, or `release-policy-guard` | guarded proof lines only | suite name, tests, failures, errors, skipped | guard role, guarded proof lines, claim scope | guards must not increase `proofClassCounts` or `proofLineCounts` |
| Platform confidence smoke | `target/platform-confidence/*/summary.json` | `runtime-proof` | `operator-admin-session`, `task-producer-api-key`, `worker-api-key`; negative rows use `authorization-no-bypass-safety` | status, profile, auth config fields, route families, credential checks, source logs; `authorizedPositiveChecks` after TPS-1 | fallback authorized-positive checks for older local artifacts | active-profile external API/auth confidence only; no-arg startup is separate |
| Server default startup smoke | `target/server-default-startup/*/summary.json` | `runtime-proof` | health/login: `operator-admin-session`; same-SQLite restart: `fault-recovery-evidence` | status, default profile observation, health/login checks, SQLite path, restart reuse | claim split into capability and restart evidence | local durable no-arg startup/restart only; not task/worker scheduling proof |
| Chaos reports | `target/chaos-reports/*.json` | `runtime-proof` when scenario contract is complete; otherwise `artifact-metadata` | complete reports map to `fault-recovery-evidence` | scenario id, task terminal state or trace oracle, runtime metadata when present | proof line, invariant ids, downgrade boundary | selected distributed-edge recovery only; no Redis process kill/partition/failover unless explicitly reported |
| Perf reports | `target/perf-reports/*.json` | `runtime-proof` when release threshold contract is complete; otherwise `artifact-metadata` | `scale-contention-evidence` | scenario id, observation/config fields, threshold/trend data | release interpretation, proof line, gate type | scheduled/manual trend evidence unless promoted |
| Soak reports | `target/soak-reports/*.json` | `runtime-proof` when runtime-invariant oracle exists; otherwise `artifact-metadata` | `scale-contention-evidence` or `fault-recovery-evidence` by scenario | scenario id, runtime invariants, trace proof, duration/config fields | release interpretation, proof line, gate type | scheduled/manual confidence evidence unless promoted |
| Perf/soak release policy | `xa-mass-testing/proof/perf-soak-release-evidence.json` | `release-policy-guard` metadata | no direct proof count | stable scenario ids, threshold signals, trend signals | threshold evaluation in summary writer | policy metadata only; source reports carry runtime proof |

## Counting Rules

- `proofClassCounts` and `proofLineCounts` count only evidence with
  `evidenceRole` in `runtime-proof`, `deterministic-proof`, or
  `integrated-proof`.
- `source-guard`, `schema-guard`, and `release-policy-guard` evidence is counted
  under `guardCounts` and `guardProofLineCounts`.
- `support-proof` and `artifact-metadata` do not increase proof counts.
- `credentialCheckProofLineCounts` and `authorizedPositiveProofLineCounts` count
  executed operation-level credential evidence, not top-level suite proof.
  Checks with `not-run` or `not-confirmed` status remain visible in source
  artifacts but do not increase operation-level totals.
- A downgraded chaos/perf/soak artifact must keep its source metadata visible
  but must not add `fault-recovery-evidence` or `scale-contention-evidence`.

## Current Inferred Fields

| Field | Current source | Target |
| --- | --- | --- |
| Platform confidence `authorizedPositiveChecks` | runner-native when present; writer fallback from log paths for old summaries | runner-native operation manifest only after compatibility window |
| Default startup `authorizedPositiveChecks` | runner-native when present; writer fallback from health/login paths | runner-native operation manifest |
| Platform confidence negative proof line | writer assigns `authorization-no-bypass-safety` to `credentialChecks` | runner row plus writer normalization |
| Surefire proof class / proof lines | writer lookup by suite marker | keep writer lookup, guarded by output fixture |
| Chaos/perf/soak proof line | writer derives from report type and scenario id when scenario contract is complete | runner scenario contract plus writer normalization |
| Release threshold evaluation | writer reads release evidence policy | keep centralized release policy |

## No-Bypass Matrix Ownership

The representative no-bypass matrix belongs to
`xa-mass-testing/proof/authorization-no-bypass-matrix.json`.

- Rows owned by `platform-confidence` must be emitted by
  `run-platform-confidence-smoke.sh` as structured `credentialChecks`.
- Rows owned by `api-contract-health` are linked from the matrix and should not
  be duplicated in platform confidence.
- Rows marked `deferred` must name the reason they are not current proof.

## Mainline Baseline Slice

The first mainline baseline slice has started and now covers TPS-1/TPS-2
together:

1. platform confidence and default startup emit runner-native
   `authorizedPositiveChecks`
2. the bounded no-bypass matrix exists
3. the writer prefers runner-native positive evidence while keeping local
   fallback for old artifacts
4. guard/source/schema/release-policy counts stay separate from proof counts

Remaining closure work is runtime/CI observation and residue scanning, not broad
E2E expansion.

Do not start with broad E2E expansion, chaos volume, perf volume, or dashboards.
