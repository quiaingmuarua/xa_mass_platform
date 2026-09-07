# Proof Registry

Status: current high-level proof ownership registry.

Owner and boundary tests remain next to their production Owners. This file
registers only high-level lanes whose process world, cost and claim need a
stable repository-wide identity.

Worker Correctness, Worker Convergence Health and Worker Loaded Capacity +
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

- **Primary owner:** Java Redis providers and their Server-owned registries.
- **Claim:** atomic owner operations preserve scores, resources, identities,
  bindings and result transitions against real Redis.
- **Deliberate nonclaims:** HTTP, Adapter, Worker or process recovery.
- **Contract:** [Server verification](../../server_jvm/README.md#verification).

## runtime_boundary

- **Primary owner:** Server assembly over Kernel, Pacer, Matching and Transport ports.
- **Claim:** one Java Server context closes the public Task, Result,
  DIRECT_CALL and Worker Serviceability boundaries through WebSocket, Socket
  and Polling witnesses.
- **Deliberate nonclaims:** fleet scale, Host restart, workload health and
  capacity.
- **Contract:** [Runtime Boundary owner](../../server_jvm/README.md#verification).

## worker_correctness

- **Primary owner:** `:integrations:worker-correctness`.
- **Claim:** exact Lab-to-Worker identity, route, Properties, extension and
  successful Result closure, with identity preserved across graceful Host restart.
- **Deliberate nonclaims:** capability-specific payload values, executing
  Worker, fault convergence, throughput and topology combinations.
- **Contract:** [Complete scenario](../../integrations/worker-correctness/README.md).

## worker_convergence_health

- **Primary owner:** `:integrations:worker-convergence-health`.
- **Claim:** Adapter and Kernel scheduling converge within a bounded wait after
  established Worker mutations, one Server restart and one execution-time Host
  loss; named successful Results remain observable across a later Host loss.
- **Deliberate nonclaims:** exact intermediate order, latency SLA, retry count,
  absence of transient serviceability regression, all-offered success,
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
- **Deliberate nonclaims:** every prepared or retained Worker online, Task fairness, fixed
  execution ratio, completion order, throughput, latency, Handler concurrency,
  topology breadth and soak.
- **Contract:** [Complete scenario](../../integrations/worker-loaded-recovery/README.md).

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
  Properties re-Prepare end to end, multi-device compatibility, cached-process
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
