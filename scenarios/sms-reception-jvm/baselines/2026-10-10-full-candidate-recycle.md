# Full-candidate recycle experiment — 2026-10-10

Status: retained. Formal performance gates and the final affected local proof runs passed.

## Decision and mechanism

**Retain the 10-second full-deferral policy.** No rollback is required. The three predeclared SMS pairs meet the retention thresholds. Full deferrals that entered no Pool use a fixed 10-second, run-local hint; ordinary recycling remains 30 seconds. Matching reports identities, Pacer owns timing, and existing Kernel exact recycling applies the original fence. No execution lease, SMS business lease, Redis key or public HTTP contract changed.

One hint per Group/Worker holds its latest fence. Limits are 1,000 per Group and 10,000 per run; short and ordinary recycling share 100 per Group / 1,000 per round, with at most 50 short hints per Group. Loss, overflow and restart retain ordinary recovery.

## Fixed artifact comparison

Baseline commit: `1197ea7df06a1f002cc01cd7529c5ea9d236887b`. A and B directories differ only in the Server JAR. The same launcher, Host, frontend, configuration and workload were used. Each run has fresh processes, inventory and an isolated Redis `test_*` scope. Order: A1/B1, B2/A2, A3/B3.

Workload: 1,000 Workers (CN/US/GB 700/200/100), three apps, 60-second business leases, 30 acquisitions/sec for 180 seconds. Each formal run submitted and established all 5,400 acquisitions, with no HTTP errors or generator rejection. The client measures every acquisition and its initial point query; it does not poll every active lease each second.

| Run | Initial NOT_OBSERVED | Acquisition P95 ms | P99 ms | Server CPU s | Host CPU s |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1-A | 338 / 5400 | 3172.91 | 3798.96 | 142.19 | 19.91 |
| 1-B | 0 / 5400 | 215.91 | 268.82 | 279.14 | 32.89 |
| 2-B | 0 / 5400 | 202.27 | 233.49 | 147.59 | 23.38 |
| 2-A | 370 / 5400 | 3338.77 | 3863.80 | 149.36 | 22.19 |
| 3-A | 385 / 5400 | 3339.93 | 3905.30 | 160.22 | 22.08 |
| 3-B | 0 / 5400 | 201.33 | 224.16 | 145.27 | 21.81 |

Initial unobserved responses decreased by 100% in every pair. Median paired acquisition P95 improvement is 93.94%. B1 has a large CPU outlier; it is retained in the data, not discarded. The predeclared three-run median cost rule is used.

| Cost gate | A median | B median | Change | Allowed increase |
| --- | ---: | ---: | ---: | ---: |
| server.cpuSeconds | 149.36 | 147.59 | -1.18% | 20% |
| host.cpuSeconds | 22.08 | 23.38 | +5.87% | 20% |
| server.rssBytes | 596660224.00 | 591339520.00 | -0.89% | 20% |
| host.rssBytes | 467111936.00 | 466763776.00 | -0.07% | 20% |
| checkedCoordinates | 22170.00 | 20909.00 | -5.69% | 50% |
| acquisitionP99 | 3863.80 | 233.49 | -93.96% | 10% |

All formal runs observed zero duplicate allocations and zero lease-projection drops/failures. These observations do not establish strict global uniqueness or reliable notification delivery.

Artifact pins:

- A: `393d09bd288cb1a1d47223e6f5d205ee9a2f612c8fe2daf85c0c419fa6829e73`
- B: `c81beb49cc99a5907b66bab6efce9fde15f2aaef9fa309362a060f5e24ddef5b`
- Diagnostic repair / delivery candidate: `cb35a98068cc099da85b4f6317aee1b142ed9f8d39569f50b5f923d647a8da34`

After the formal experiment, a JDK 21.0.1 instrumentation failure was isolated to the exception handler inside the new JFR Event class. Moving that guard to the calling hint object restored recordings; enabled/default-off recording tests prove it. The scheduling policy bytecode is unchanged. The other changed production classes are the event/hint observation boundary and Kernel record line-number metadata after Javadoc edits. Formal six-run evidence remains pinned to its original artifacts.

## Independent diagnostics

Diagnostics are excluded from formal latency statistics. The initial B recording lacked hint events and was not accepted as queue evidence; the repaired candidate was rerun. Both accepted recordings report no JFR data loss.

| Score refill command mapping | A | Repaired B |
| --- | ---: | ---: |
| ZRANGEBYSCORE | 5810 | 5838 |
| EVAL | 2634 | 3083 |

These counts map recorded Pacer Score stages to their fixed one-command operations; they exclude Matching reads and other Redis traffic.

The last active hint snapshot records peak 698, admitted hints 5288, attempts/successes 5134/5134, drops 0, stale 0 and failures 0. Pending 154 is the last active-refill observation, not a shutdown-retention claim; empty-root cleanup and run isolation are separately exercised by Owner tests.

## Verification and proof corrections

- Matching focused: 127 tests; Pacer focused: 179 tests; real Redis Owner: 277 tests; Runtime Boundary: 23 tests; SMS Boot composition: 2 tests; full Scenario Boot composition: 22 tests. No failures or skips in the final recorded runs.
- Actual SMS functional/lifecycle, Coexistence functional/pool-selection/lifecycle, App Checks source/fresh-ZIP, fresh-ZIP SMS and Coexistence functional/pool-selection passed.
- Worker Convergence Health (state and task-fault), Worker Correctness (initial, live Properties and restart), Dynamic Matching (1,000 Workers / 150,400 Items), and all 11 API 33 Android emulator phases passed.
- Runtime ZIP, Worker SDK ZIP, Preview archive verification and packaged-launcher proofs passed. Artifacts remain local; no release was published.
- Boot App Checks witness was adapted from the old integer refill result to `RefillOutcome`. Its assertions were preserved and the full 22-test composition suite passed afterward.
- The Coexistence duplicate-execution witness no longer derives a Task from retired SMS country objects. It uses a separate finite generic Task, preserving the original-Reporter oracle.
- Heavy Coexistence validation passed for both A and B after repairing the proof driver. Each mixed run established 1,500 SMS acquisitions and verified all 12,000 messages with all four receipt facts. Each sending run verified 105,000 unique sends across 51 Tasks, including Result, terminal Item state and retained receiving evidence. Initial and isolated failures of both versions remain in the dataset; they were not counted as successful runs.
- The mixed driver previously waited for global queue emptiness in each input thread. Callback admission now uses one bounded 32-callback gate; final per-receipt, no-drop, one-attempt and latest-Result assertions remain unchanged. Sending observations retry only read-only transport failures inside the original 900-second phase budget, without replaying create/import/approve or changing the workload. These are proof-driver corrections, not Messages/App Checks business changes.
- Frontend lint/typecheck and 261 tests in 23 files passed. CI companion Python suites passed; loaded-recovery helper tests have one platform-specific skip on Windows, recorded in the dataset.
- Docs Contract (66 Markdown files / 6 overview sections), proof selection (11 lanes / 107 representative paths), SMS Python tests (11), Coexistence Python tests (24), affected-reference review and `git diff --check` passed. The nine path-selected lanes were exercised locally through their named owners/runners; no remote CI or release status is inferred.

The safe aggregate dataset is [2026-10-10-full-candidate-recycle.json](2026-10-10-full-candidate-recycle.json). Full client latency arrays, private recordings, logs, isolated failures and artifact directories remain under `build/full-candidate-experiment`. Real SMS devices, cross-Pool fairness under insufficient supply and platform maximum capacity are outside these claims.
