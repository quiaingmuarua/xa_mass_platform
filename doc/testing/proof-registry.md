# Proof Registry

Status: current high-level proof ownership registry.

Owner and boundary tests remain next to their production Owners. This file
registers only high-level lanes whose process world, cost and claim need a
stable repository-wide identity.

Worker Correctness, Worker Dynamic Matching, Worker Convergence Health and Worker Loaded Capacity +
Recovery Stability are claim identities, not size tiers. Their Worker and Item
counts are fixed World and Workload fixtures chosen for those claims.

Commands, prerequisites and selection belong to [TESTING.md](../../TESTING.md).
World, workload, mutation order and oracles belong to the linked scenario Owner.

## jvm_contracts

- **Primary owner:** each touched JVM module; CI only aggregates.
- **Claim:** Java contracts, architecture guards and deterministic Owner tests
  pass together.
- **Deliberate nonclaims:** Redis behavior, process boundaries and system
  convergence.
- **Contract:** [Selection and commands](../../TESTING.md#lane-index).

## redis_owner

- **Primary owner:** Java Redis providers and the Server Identity registry.
- **Claim:** atomic owner operations preserve scores, resources, identities,
  bindings and result transitions against real Redis. Worker registration adds
  cold NX, concurrent default-Endpoint selection, partial-stage retry and bounded
  client-command cost oracles. Candidate generation proofs establish high-mark
  encoding, exact candidateize without time renewal, bounded aged recycling,
  four 250-member heads advancing 100/100/50 and current-slot exclusions.
  Properties advances past HOT time and clears candidate mark; requalification
  needs no aged recycle and cannot restore the old fence. An execution-first
  future hold survives; RECOVERY mark and cold-registration protection remain.
  Shared Pool and Direct callers have one execution-acquisition winner and stale
  stock cannot release it. Pool tests fix per-entry admission TTL, independent repeated fences,
  capacity accounting, lazy cleanup and concurrent at-most-once entry consumption. Current/future network
  evidence preserves time/mark. An accepted past polarity change advances generation
  and clears mark, including a one-slot advance for same-slot evidence; ordinary
  same-polarity Polling is unchanged. Below-floor activation additionally requires
  post-floor evidence. Pause writes MAX/0; relative targets
  accept MAX and reject larger slots. Recovery keeps unlimited age/attempts and
  Redis-relative delay; Pacer owns timing and budget policy. Refill filters corrupt
  raw rows, while Serviceability retains strict fractional conversion; neither
  supplements a bounded head. Serviceability merges two mark ranges before final
  raw-budget truncation. Candidateize precedes qualification, with no substitute
  discovery. Facts/index writes and generation invalidation remain independent.
  Actual Pacer/Matching/Redis composition proves deficit-bounded candidateization:
  stock 99 and target 100 changes one of 100 ordinary Worker fences, leaving the
  other 99 available after normal inventory consumption without aged recycling.
  Stock 80 against target 100 similarly changes exactly 20 fences. Matching
  watermarks do not cap inventory or truncate supplied qualified candidates;
  batch budgets, real capacity, TTL and generation checks remain authoritative.
  The same production composition proves that reconnect refills consumed stale
  Pool stock at a new generation without waiting for the 60-second recycle.
  It also proves that later Pool demand cannot copy existing Country stock, while
  Direct Phone lookup can independently acquire the cached Worker without any Pool
  notification. Qualified Messaging Phone also uses this path, after one bounded
  current Facts read; it requires no Messaging Pool supply or inventory. Tests cover
  phone changes between reads, no-Pool configuration and strict corruption failure.
  Old Pool fences cannot acquire or release the execution hold;
  concurrent Pool/Direct acquisition has one winner. Recycling allows a new
  generation to enter another Pool while the stale entry remains. Local tests
  prove single-Pool admission, batch bucket consumption and poll-time age filtering. Scenario
  Coexistence retains its existing business assertions and deadlines.
  Matching qualification reads only offered Facts: Messaging uses one HMGET and
  Proof one atomic two-HMGET snapshot. Retired qualification keys, even with wrong
  types, do not participate in writes, startup or refill. Property HASH proofs cover
  atomic Facts updates, last-writer collisions, conditional deletion, one-HMGET
  lookup and restart retention without rebuilding. Focused tests cover name-independent batching, explicit
  rotation and literal-safe Proof views.
  TaskItem outcome proof covers
  generic tags 2..9, maximum-score promotion, exact ACTIVE claim races,
  corruption rejection, terminal-preserving NX, and one-command bounded
  promotion/state reads. Server state-query counting excludes Result reads.
  Ordered Result writes preserve maximum tag then reported milliseconds,
  including same-slot replies. Heterogeneous 100-Item observations cost one
  Score Lua plus zero or one Result Lua; missing/corrupt Items create no content.
- **Deliberate nonclaims:** HTTP, Adapter, Worker or process recovery.
- **Contract:** [Server verification](../../server_jvm/README.md#verification).

## runtime_boundary

- **Primary owner:** Server assembly over Kernel, Pacer, Matching and Transport ports.
- **Claim:** one Java Server context closes the public Task, Result,
  DIRECT_CALL and Worker Serviceability boundaries through WebSocket, Socket
  and Polling witnesses. Prepare establishes identity and cold registration
  without Matching facts; network evidence activates scheduling availability;
  text-protocol observations independently create facts, and Polling executes
  explicit Any Pool without them. Preview can expose an identity before a baseline.
  Actual Handlers return send success before reporting delivered/read/replied
  through all three transports; state queries, repeatable latest-reply reads
  and subsequent Item execution witness the continuous observation boundary.
  Group refill witnesses use actual Workers across two Groups and four Tasks,
  including shared and different Rules within one Group, to prove supplied-batch
  acquisition-before-qualification and successful finite execution. They do not measure throughput.
  An isolated DEFAULT-preset witness loses the first disconnect evidence, then
  requires actual Adapter TASK expiry, correlated rejection and consumed network
  evidence to drive RECOVERY; reconnect completes the same Item within the existing
  15-second wait, without a shortened recycle threshold. No Probe or
  synthetic expiry Report substitutes for that path. Safe bounded traces accompany failures.
- **Deliberate nonclaims:** fleet scale, Host restart, workload health and
  capacity, guaranteed activation after evidence loss, atomic registration,
  atomic Score/Result commits or observation replay.
- **Contract:** [Runtime Boundary owner](../../server_jvm/README.md#verification).

## worker_correctness

- **Primary owner:** `:integrations:worker-correctness`.
- **Claim:** exact Lab-to-Worker identity, route, Properties, extension and
  successful Result closure; running Host file mutations reach Adapter cache
  and Server/Matching Runtime Properties without another Prepare, followed by
  identity preservation across graceful Host restart.
- **Deliberate nonclaims:** capability-specific payload values, executing
  Worker, fault convergence, reliable SYSTEM delivery under faults, production
  latency SLA, throughput and topology combinations.
- **Contract:** [Complete scenario](../../integrations/worker-correctness/README.md).

## worker_dynamic_matching

- **Primary owner:** `:integrations:worker-dynamic-matching`.
- **Claim:** continuous Matching query execution overlaps live Worker and Platform
  Properties changes; independent Adapter/Runtime snapshots converge, blocked
  witnesses stay unexecuted, eligible witnesses execute on actual target
  replicas, and all submitted Results close with completed execution witnesses
  without new Prepare or process/Worker restart.
- **Deliberate nonclaims:** atomic facts/Score cutover, exact invalidation/acquisition
  ordering, cancellation of confirmed work, exactly-once execution, reliable
  SYSTEM replay, fault recovery, Task fairness, throughput, latency SLA or soak.
- **Contract:** [Complete scenario](../../integrations/worker-dynamic-matching/README.md).

## worker_convergence_health

- **Primary owner:** `:integrations:worker-convergence-health`.
- **Claim:** Adapter and Kernel scheduling converge after established state
  mutations and one Server restart. Execution-time Host loss establishes the
  physical outage, followed by checkpoint/work recovery after reconnect and
  retained named Results across a later Host loss. Host-down Score samples are
  diagnostic, not a gate requiring all unused offline candidates to leave HOT.
- **Deliberate nonclaims:** exact intermediate order, latency SLA, retry count,
  absence of transient serviceability regression, all-offered success,
  fleet-wide scheduling unavailability after one best-effort disconnect report,
  background fault Result status or execution count, TaskItem Score finality
  across the interruption window, executing Worker, random coverage,
  throughput and soak.
- **Contract:** [Complete scenarios](../../integrations/worker-convergence-health/README.md).

## worker_loaded_recovery

- **Primary owner:** `:integrations:worker-loaded-recovery` and its separate
  workflow.
- **Claim:** sustained loaded operation after deterministic Worker contraction,
  repeated graceful and hard Server recovery, exact terminal Task exports and
  bounded process resource drift.
- **Resource witness:** periodic sampling must remain healthy through finalization.
  Sampler failure or a coverage gap fails the lane even when workload/recovery
  assertions pass; sparse checkpoints and partial maxima do not establish resource
  stability. Confirmed process-exit read races are recorded without masking errors
  on a still-live process.
- **Deliberate nonclaims:** every prepared or retained Worker online, Task fairness, fixed
  execution ratio, completion order, throughput, latency, Handler concurrency,
  topology breadth and soak.
- **Contract:** [Complete scenario](../../integrations/worker-loaded-recovery/README.md).

## worker_call_performance

- **Primary owner:** `:integrations:worker-call-performance` and its separate workflow.
- **Claim:** fixed offered Task Call and caller-targeted Direct Call load,
  successful response and latency distributions, and saturation. Task Call also
  measures coexistence with one Pool-backed background Task and bounded Result
  follow-up; Direct Call retains timeout/rejection/unknown outcomes without
  inventing a persistent Result lookup. Generator limitations stay explicit.
- **Deliberate nonclaims:** absolute production SLA, larger-world correctness,
  fault recovery, actual executor, Handler concurrency, fairness and soak.
  Redis Owner separately proves client-command cost and mailbox concurrency.
- **Diagnosis:** fixed 30-second surge and 90-second sustained windows distinguish
  API responses from successful execution. Optional owner/JVM JFR explains cost
  and actual Server HTTP execution without asserting Worker Handler concurrency;
  limited windows and incomplete diagnostic coverage remain explicit.
- **Contract:** [Complete scenario](../../integrations/worker-call-performance/README.md).

## android_host

- **Primary owner:** Android Worker and XA Android modules.
- **Claim:** Android library assembly, identity persistence, capability
  Definitions, local lifecycle, Control HTTP and Java proof clients remain
  compatible.
- **Deliberate nonclaims:** real process, Doze, vendor policy and physical
  device behavior.
- **Contract:** [Android owners](../../xa-android/README.md).

## android_emulator

- **Primary owner:** `:integrations:android-worker-proof`; the shell owns only
  external process choreography.
- **Claim:** one Debug App proves exact lifecycle correctness, route recovery,
  Handler-time process loss, endpoint exhaustion and explicit identity-stable
  recovery. A fixed App Triad adds same-Group identity isolation, explicit
  Worker ID targeting and partial process outage.
- **Deliberate nonclaims:** throughput, Handler concurrency, exact connection
  attempts, transient Score sequence, TaskItem Score finality across the
  process-loss window, UI behavior, arbitrary replica counts, dynamic
  Properties mutation end to end, multi-device compatibility, cached-process
  survival, Doze/OEM policy and physical-device background behavior.
- **Contract:** [Complete scenarios](../../integrations/android-worker-proof/README.md).

## frontend

- **Primary owner:** `frontend/`.
- **Claim:** Runtime projections, finite Task workbench and static API Reference
  remain lint-clean, type-safe, tested and buildable.
- **Deliberate nonclaims:** browser compatibility and visual regression.
- **Contract:** [Frontend owner](../../frontend/README.md).

## runtime_distribution

- **Primary owner:** `distribution/server` and `distribution/worker-sdk`.
- **Claim:** publishable archives work outside the checkout and contain only
  declared runtime/publication boundaries.
- **Deliberate nonclaims:** OCI deployment and Redis lifecycle.
- **Contract:** [Server distribution](../../distribution/server/README.md) and [Worker SDK distribution](../../distribution/worker-sdk/README.md).

## docs_contract

- **Primary owner:** `.github/scripts/check_docs.py`.
- **Claim:** current entrypoints, local file and chapter links, Overview
  navigation and retired vocabulary remain converged.
- **Deliberate nonclaims:** implementation behavior.
- **Contract:** [Checker](../../.github/scripts/check_docs.py) and [unit tests](../../.github/scripts/test_check_docs.py).

## product_coexistence

- **Primary owner:** [Scenario Coexistence](../../integrations/scenario-coexistence/README.md).
- **Additional finite scenario:** [App Checks](../../scenarios/app-checks-jvm/README.md#装配与证明)
  owns hash-based registered/unregistered/exception outcomes on two App Groups.
  Four real App Workers prove Group correlation and independent recomputation;
  Boot also witnesses actual throwing Handler calls, late success, bounded Result
  preview and Server restart reads. Source and fresh ZIP use the same public API oracle.
  This makes no exact-distribution, fixed-executor or single-execution claim.
- **Claim:** SMS and Messages share actual Workers, one Server/Adapter/resource
  set and neutral Group declarations; finite message execution and later recipient
  observations remain correlated after Task completion or explicit closure.
  SMS selects Country Pool stock; phone-directed Messages uses qualified Direct
  lookup with empty supply. Ordinary Messages US/ANY selection is witnessed in a
  separate scope with only Messaging demand and INITIAL managed Tasks, retaining
  real execution identity and sender/recipient country assertions.
  Duplicate/reordered receipts and Worker restart preserve latest content and run
  isolation. Platform/preview executable composition and fresh unified Preview
  delivery remain consistent with public Console availability.
- **Failure witnesses:** partial real append retains uncertain submission without
  approval/recreation; a real delayed synchronous execution Result cannot erase
  a newer recipient reply. The process runner uses only business/Runtime/Lab APIs.
- **Deliberate nonclaims:** reliable receipts across failure, restart recovery,
  third-party sending, chat history, capacity maximum, fixed throughput, per-Task
  or cross-Pool fairness, or starvation-freedom under insufficient supply.
- **Selection:** each small scenario has 12 Workers. Source functional, Pool selection
  and lifecycle participate in Proof Gate; fresh ZIP repeats functional and Pool
  selection. Independent steps retain other outcomes after a proof fails, with
  every required failure still failing CI. Safe summaries retain completed stages
  and identify the failing stage without private message content. Fixed 1,000-Worker
  simultaneous SMS/message load is explicit local or workflow_dispatch acceptance.
  Generic Score and Result transition truth remains with Redis Owner/Runtime Boundary.

## sms_reception_preview

- **Primary owner:** [SMS Reception](../../scenarios/sms-reception-jvm/README.md#检查与验收).
- **Claim:** shared console navigation observes SMS enablement; page tabs and
  polling preserve business state without depending on Runtime stores. Distribution
  serves one frontend in either profile while SMS APIs, Groups and jobs remain
  profile-owned. Real Server/Host functional and lifecycle scenarios retain the
  listening and later-outcome business witnesses. Archive checks compare the
  current unified assets and reject embedded SMS assets. The source SMS oracle
  loads the shared Preview's packaged launcher from a fresh extraction and submits
  only SMS workload with the Preview scenarios enabled and App Workers excluded,
  without a build step or a fallback to the checkout launcher. Launch
  lifecycle and archive checks belong to Distribution; business witnesses stay here.
- **Deliberate nonclaims:** Mock SMS execution, product authentication, business
  persistence across restart or new platform scheduling/capacity guarantees.
- **Contract:** [Console](../../frontend/README.md#sms-business-pages) and the
  existing SMS Preview workflow; the fixed 1k workload remains separate acceptance.
