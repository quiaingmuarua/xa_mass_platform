# Proof Registry

Status: current high-level proof ownership registry.

This registry assigns stable claim identities, not size tiers. Owner tests stay
beside production code. [TESTING](../../TESTING.md) owns commands, prerequisites
and CI selection, including the [shared Integration boundaries](../../TESTING.md#shared-infrastructure);
the linked Integration/Scenario Owners own world, workload,
fault sequences, thresholds and oracles. Counts and timing fixtures do not
expand a lane's claim. Evidence links identify assertions, not a fresh passing run.

## jvm_contracts

**Primary Owner:** each touched JVM module; CI aggregates their deterministic
contracts and architecture guards.

| Proven boundary | Conditions that distinguish the claim |
| --- | --- |
| Matching admission and correlation | Complete Catalog admission precedes resource access; immutable queries are normalized once and correlated in input order. Unsupported inputs fail, IDs remain call-local, and address/exclusion filtering does not trigger replacement takes. |
| Pool and function effects | Immutable occurrences include equal-value reinsertion; concurrent consumption/capacity, Group isolation and lazy expiry remain local. Earlier function/Pool effects survive later failure. Failed refill qualification admits no stock; assignment-window qualification follows a destructive take and cannot restore it. |
| Fixed Matching composition | Properties, Catalog and refill coordination retain distinct responsibilities, one stable interface pair and independently closed connection. No-Pool Identity/Phone need no startup I/O; failed startup never closes the Server-owned client. Literal Proof values, ALL/PAGED rotation and empty-demand cursor cleanup do not imply deduplication or background expiry. |
| Pacer assignment and budgets | Independent roots and single-flight Producers preserve raw-row charging, deficit bounds, round remainders and independent supply/recycling rotation. Candidateization precedes qualification, each successful candidateization is supplied once and admitted to at most one Pool, and no substitute discovery occurs. Strict/current acquisition remain separate; only TRANSITIONED with the returned fence permits Item claim, without fallback or compensation. |
| Observation handoff | Notification follows execution acquisition and Item claim but precedes publication; sink failure cannot control dispatch. Unmatched Group/event notices are rejected before queue admission/diagnostics. Bounded saturation, immutable routing, one enqueue and per-handler-instance batching preserve receipt order. |
| Projection and receiver lifecycle | Worker ordering, duplicate/unsorted times, local patch visibility and overlapping-field precedence survive grouping into bounded reads and one patch per Worker per drain. Read/compute/write/handler failures are isolated without replay. Duplicate registration, stop checks before later handlers/writes, close timeout and restart only after old-thread exit are tested; pure handlers own no lifecycle. |
| App Checks projection | Window arithmetic, invalid stored values, older observations and restart continuation preserve a lossy projection; it is neither an execution counter nor an admission quota. |

**Evidence:** [Matching proof](../../worker_matching_jvm/README.md#cost-failure-and-proof),
[Pacer assembly](../../kernel_pacer_jvm/doc/application-assembly.md#proof-boundary),
[Server observations](../../server_jvm/README.md#worker-allocation-observations),
[App Checks](../../scenarios/app-checks-jvm/README.md#装配与证明).
**Nonclaims:** Redis behavior, process boundaries and system convergence.

## redis_owner

**Primary Owner:** Java Redis providers and the Server Identity registry.
Real Redis establishes Owner-local atomicity, exact fences and bounded command
cost; it does not combine independent commits.

| Proven boundary | Conditions that distinguish the claim |
| --- | --- |
| Registration and Task descriptors | Cold NX registration, concurrent default-Endpoint selection and retry after partial Prepare stages preserve identity. Create-only descriptors and passive Project attribution/index creation commit together; first-creation ordering, bounded reads, concurrent Project isolation and corruption rejection remain explicit. |
| Worker generation and execution | Exact candidateization does not renew time; bounded aged heads progress without admitting the current slot. Properties invalidation advances past HOT and clears its mark, while cold registration, RECOVERY marks and an execution-first future hold remain protected. Pool/Direct races have one acquisition winner; stale stock can neither acquire nor release that hold. Both strict and current acquisition reject current/future HOT, RECOVERY, missing and corrupt entries. |
| Network transitions and recovery | Evidence applied to a stored current/future Score preserves its time/mark; polarity change alone grants no execution. Accepted past polarity changes advance generation and clear the mark, including same-slot evidence, while ordinary same-polarity Polling is unchanged. Below-floor activation needs post-floor evidence. Pause and relative targets retain representable bounds; Recovery has no age/attempt cutoff and uses the Owner clock. |
| Bounded heads and supply | Refill filters corrupt raw rows; Serviceability retains strict conversion and merges mark ranges before final raw-budget truncation. Neither supplements a bounded head. Actual Pacer/Matching composition proves deficit-bounded candidateization before qualification. Watermarks do not cap inventory or clip supplied qualified candidates; request budgets, capacity, TTL and exact fences still apply. |
| Pool generations and Direct queries | Reconnect can supply a new generation without aged recycling; later Pool demand cannot copy existing stock. Recycling may admit a new generation elsewhere while old stock remains stale. Entries have independent admission TTL and at-most-once consumption. Phone and qualified Messaging Phone bypass stock, require no Pool supply and send no acquisition notification; changed lookup values and corrupt Facts do not permit substitution. |
| Facts and indexes | Qualification reads only offered identities; Proof/assignment-window reads use bounded atomic Worker/Platform snapshots. Atomic Facts/property-HASH writes retain complete replacement, last-writer collisions and conditional deletion. Platform patches are independent. Retired keys are ignored; restart retains mappings without rebuild or Task demand. These commits remain separate from Score invalidation. |
| Consumption and read failure | Identity and ordinary Pool take/deficit observation add no Redis access; Phone lookup is bounded and non-consuming. Public Facts reads permit row-local outcomes, but malformed refill qualification fails before admission. Assignment-window reads occur after stock consumption, and failure cannot restore it. |
| Item outcomes and Results | Exact ACTIVE claim races, terminal-preserving NX, generic terminal tags, strict maximum promotion and corruption rejection remain distinct. Promotion is caller-bounded; state reads keep their independent bound. Result ordering uses tag then reported time, including same-slot updates. Missing/corrupt Items create no content; Result replacement and Score finality have independent maxima and commits. |
| Assignment composition | Admitted batches traverse qualification, address lookup, execution lease, Item claim and mailbox publication; expired/exhausted Items settle failure Result and Score. The instance assignment ceiling does not widen registration or Properties inputs. |
| Network timestamp persistence | Group isolation, equal/older evidence, chunk-wide corruption preflight, cross-chunk partial success and concurrent monotonic writes survive restart without Facts/Pool prerequisites. Pacer validates source/Binding before filtering and fails open without retry. A timestamp commit neither joins nor rolls back a later Score commit. |

**Evidence:** [Worker Score proof](../../kernel_jvm/doc/score/worker-score-band-scheduling.md#cutover-and-proof),
[Task resource](../../kernel_jvm/doc/resource-model/task-resource-model.md),
[Item Score](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md),
[Result Owner](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md),
[Matching proof](../../worker_matching_jvm/README.md#cost-failure-and-proof) and
[Server verification](../../server_jvm/README.md#verification).
Controlled timing tests may resample a missed window, never a wrong result
inside the window.

**Nonclaims:** HTTP/Adapter/Worker traversal, process recovery, cross-owner
rollback or loss repair.

## runtime_boundary

**Primary Owner:** Server assembly over Kernel, Pacer, Matching and Transport ports.

| Proven boundary | Conditions that distinguish the claim |
| --- | --- |
| Public Task/Result/Direct/Serviceability paths | One Java Server context traverses real WebSocket, Socket and Polling Workers. Prepare establishes identity/cold membership without Facts; network evidence and Properties have independent effects. Preview may expose identity before baseline, and explicit Any Polling executes without Facts. |
| Continuous Task observations | Actual Handlers return send success before later delivered/read/replied observations. Item-state queries, repeatable latest replies and subsequent execution establish the boundary through all three transports. |
| Matching traversal | Shared/different supply and query paths across Groups prove supplied-batch candidateization before qualification and finite execution. No-Pool identity queries and empty-supply Tasks retain real Worker traversal. |
| Lost-disconnect recovery | The DEFAULT witness first loses real disconnect evidence, then requires actual Adapter TASK expiry, correlated rejection and consumed network evidence to enter RECOVERY. Reconnect completes the same Item; a Probe, synthetic expiry Report or shortened recycle threshold cannot substitute for that sequence. |
| Filtering and HTTP completion | Still-fresh older network observations that Score time admission could accept are filtered across Server restart; corruption fails open without joining Score commits. Default, prestarted and virtual HTTP execution cover success, occupied-slot rejection, timeout, late-report rejection and shutdown, each with one servlet completion. |

**Evidence:** [Server verification and test entries](../../server_jvm/README.md#verification).
**Nonclaims:** fleet scale, Host restart, workload health/capacity, activation
guaranteed after evidence loss, atomic registration or Score/Result commits,
or observation replay.

## worker_correctness

- **Primary Owner:** [Worker Correctness](../../integrations/worker-correctness/README.md).
- **Boundary:** exact Lab/Worker identity, route, Properties, extension and successful Result closure.
- **Required witness:** running-Host file mutations reach Adapter and Server/Matching snapshots independently without another Prepare; graceful Host restart retains identity.
- **Evidence:** the Owner's workload and independent runner assertions, including process, control-record and Prepare-traffic preconditions before phase success.
- **Nonclaims:** capability payload values, executing Worker, fault convergence, reliable SYSTEM delivery under faults, production latency SLA, throughput or topology combinations.

## worker_dynamic_matching

- **Primary Owner:** [Worker Dynamic Matching](../../integrations/worker-dynamic-matching/README.md).
- **Boundary:** continuous query execution overlaps live Worker/Platform Properties changes without Prepare or process/Worker restart.
- **Required witness:** Adapter/Runtime snapshots converge independently; blocked witnesses remain unexecuted, eligible witnesses execute on actual target replicas, and every submitted Result closes with an execution witness.
- **Evidence:** the Owner's live-mutation workload and Result/Host-journal oracle. Handler construction fixes actual executor identity; tokens only correlate. Journal overflow or sequence gaps fail the proof, and raw records/correlation mappings stay outside uploaded artifacts.
- **Nonclaims:** atomic Facts/Score cutover, exact invalidation/acquisition order, cancellation of confirmed work, exactly-once execution, reliable SYSTEM replay, recovery, fairness, throughput, latency SLA or soak.

## worker_convergence_health

- **Primary Owner:** [Worker Convergence Health](../../integrations/worker-convergence-health/README.md).
- **Boundary:** Adapter/Kernel convergence after established state mutations and Server restart.
- **Required witness:** establish physical Host loss during execution before judging recovery; reconnect must recover named checkpoints/work, with named successful Results retained across later loss. Fleet Score samples after Host loss/Server restart are diagnostic; reconnect identities, the directed Worker's HOT check and named executions are mandatory.
- **Evidence:** the Owner's fault-establishment order and independent runner observations.
- **Nonclaims:** exact intermediate order/retry count, latency SLA, no transient regression, all-offered success, fleet unavailability from one lost disconnect notice, idle-fleet HOT without active roots, monotonic network evidence, background fault Result status/execution count, Item finality across interruption, executing Worker, random coverage, throughput or soak.

## worker_loaded_recovery

- **Primary Owner:** [Worker Loaded Recovery](../../integrations/worker-loaded-recovery/README.md), with a separate workflow.
- **Boundary:** sustained work after deterministic Worker contraction, repeated graceful/hard Server recovery, exact terminal exports and bounded process resource drift.
- **Required witness:** periodic resource sampling remains healthy through finalization. Sampler failure or a coverage gap fails the lane even if work/recovery passes; sparse checkpoints and partial maxima cannot prove stability. Confirmed process-exit read races must not mask errors on a live process.
- **Evidence:** the Owner's workload, recovery sequence and resource oracle.
- **Nonclaims:** every prepared/retained Worker online, fairness, fixed execution ratio/order, throughput/latency, Handler concurrency, topology breadth or soak.

## worker_call_performance

- **Primary Owner:** [Worker Call Performance](../../integrations/worker-call-performance/README.md), with a separate workflow.
- **Boundary:** open-loop Any, targeted and Direct latency/success, deep-backlog Task turnaround per Worker, and same-host path ratios with configurable resources raised so they do not bind.
- **Required witness:** every accepted Task Item reaches an observed Result. Cases distinguish passed, saturated, invalid and failed; only failed fails the lane. Unmet measurement prerequisites produce invalid, not a comparable result; in-flight-cap saturation reports admitted rate rather than a completion ceiling.
- **Evidence:** the Owner's rates/configuration, calibration, per-case attribution, trend and sequential A/B procedures. Optional JFR leaves incomplete coverage explicit and proves no Handler concurrency.
- **Nonclaims:** scarce-resource turnover, Group fairness, production SLA, larger-world correctness, recovery, actual executor, Handler concurrency or soak. Redis Owner separately owns command cost/mailbox races; measured performance is not a correctness threshold.

## android_host

- **Primary Owner:** [Android Worker](../../transport/android-worker/README.md) and [XA Android](../../xa-android/README.md).
- **Boundary:** library assembly, identity persistence, capability Definitions, local lifecycle, Control HTTP and Java proof-client compatibility.
- **Required condition:** local tests do not establish the non-blocking stop target; the [known Android stop difference](../../transport/android-worker/README.md#known-android-stop-difference) remains open.
- **Evidence:** the linked Owners' deterministic tests and assembly checks.
- **Nonclaims:** real process, Doze, vendor policy or physical-device behavior.

## android_emulator

- **Primary Owner:** [Android Worker Proof](../../integrations/android-worker-proof/README.md); shell owns external process choreography only.
- **Boundary:** Debug App lifecycle, route recovery, Handler-time process loss, endpoint exhaustion and explicit identity-stable recovery; fixed App Triad adds same-Group identity isolation, explicit Worker ID targeting and partial outage.
- **Required witness:** real process/route and targeted identity evidence, rather than Properties affinity inferred as scheduling identity.
- **Evidence:** the Owner's Debug App and Triad scenarios.
- **Nonclaims:** throughput, Handler concurrency, exact connection attempts/transient Scores, Item finality across process loss, UI, arbitrary replicas, dynamic Properties mutation end to end, multi-device compatibility, cached-process survival, Doze/OEM policy or physical-device background behavior.

## frontend

- **Primary Owner:** [Frontend](../../frontend/README.md).
- **Boundary:** Runtime projections, finite Task workbench and static API Reference.
- **Required evidence:** Owner lint, type, test and build checks; a successful build alone does not replace the other checks.
- **Nonclaims:** browser compatibility or visual regression.

## runtime_distribution

- **Primary Owner:** [Server Distribution](../../distribution/server/README.md) and [Worker SDK Distribution](../../distribution/worker-sdk/README.md).
- **Boundary:** publishable archives work outside the checkout and contain only declared runtime/publication boundaries.
- **Required evidence:** Owner archive/launch checks from extracted artifacts, without checkout fallback.
- **Nonclaims:** OCI deployment or Redis lifecycle.

## docs_contract

- **Primary Owner:** [Docs checker](../../.github/scripts/check_docs.py).
- **Boundary:** current entrypoints, local files/anchors, Overview navigation and selected retired vocabulary.
- **Required evidence:** checker plus [checker unit tests](../../.github/scripts/test_check_docs.py); semantic agreement still needs Owner/caller review.
- **Nonclaims:** implementation behavior.

## product_coexistence

**Primary Owner:** [Scenario Coexistence](../../integrations/scenario-coexistence/README.md).
[App Checks](../../scenarios/app-checks-jvm/README.md#装配与证明) owns its additional
finite scenario within this lane.

| Proven boundary | Conditions that distinguish the claim |
| --- | --- |
| Shared business execution | SMS and Messages share actual Workers and one platform resource set. SMS consumes Country Pool stock; directed Messages uses qualified Phone lookup with empty supply. Ordinary Messages US/ANY selection uses only Messaging Pool demand with managed Tasks still INITIAL, proving actual executor/country relationships without competing supply or a cross-Pool fairness claim. |
| Later observations and uncertainty | Receipts remain correlated after Task completion/closure; duplicate/reordered receipts and Worker restart preserve latest content and run isolation. Partial real append leaves submission uncertain without approval/recreation. A delayed execution Result cannot erase a newer recipient reply. |
| App Checks execution | Hashed registered/unregistered/throwing outcomes use actual App Workers across Groups, with Group correlation and independent recomputation. Late success, bounded Result preview and restart reads use the same public-API oracle in source and fresh ZIP. |
| Projection before Results | Handler barriers establish actual allocation identities/times and Platform Properties before success/failure/late Reports; properties survive restart. This is evidence of projection, not reliable execution counting. |
| Assignment-window qualification | A real nonempty qualification rejection must precede execution after a window transition and candidate recycling. Empty stock or a busy Worker is insufficient evidence. Deterministic functions own exact/delayed/missing-projection cases; Redis Owner owns snapshot budgets and read-failure consumption. |

**Evidence:** the linked scenario workloads, thresholds, deadlines and public
business/Runtime/Lab API oracles. [TESTING](../../TESTING.md) owns source/ZIP
selection and explicit larger-workload commands. Generic Score/Result transitions
remain Redis Owner/Runtime Boundary claims.

**Nonclaims:** reliable receipts across failure, business-state restart recovery,
third-party sending, chat history, maximum capacity, fixed throughput, per-Task or
cross-Pool fairness, starvation freedom under insufficient supply, reliable
projection counts or strict quotas. App Checks additionally makes no exact
distribution, fixed-executor or single-execution claim.

## sms_reception_preview

- **Primary Owner:** [SMS Reception acceptance](../../scenarios/sms-reception-jvm/README.md#检查与验收).
- **Boundary:** shared console enablement/navigation and business tab/polling state remain independent of Runtime truth; real Server/Host listening and later outcomes survive the named functional/lifecycle sequences.
- **Required delivery witness:** APIs, Groups and jobs remain profile-owned; Distribution serves unified assets and rejects embedded SMS assets. The SMS oracle submits SMS-only work through a freshly extracted Preview's packaged launcher, with Preview scenarios enabled and App Workers excluded, without build tools or checkout-launcher fallback.
- **Evidence:** SMS owns business assertions; [Distribution](../../distribution/server/PREVIEW.md) owns archive/launch lifecycle; [Frontend](../../frontend/README.md#sms-business-pages) owns page behavior. [TESTING](../../TESTING.md) selects the workflow and separate larger acceptance workload.
- **Nonclaims:** Mock SMS execution, authentication, business persistence across restart or new scheduling/capacity guarantees.
