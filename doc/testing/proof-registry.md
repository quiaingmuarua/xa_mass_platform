# Proof Registry

Status: current high-level proof ownership registry.

Owner and boundary tests remain next to their production Owners. This file
registers only high-level lanes whose process world, cost and claim need a
stable repository-wide identity.

## jvm_contracts

- **Primary owner:** each touched JVM module; CI only aggregates.
- **Claim:** Java contracts, architecture guards and deterministic Owner tests
  pass together.
- **Failure model:** local invariant, strict validation, controlled race or
  forbidden dependency.
- **World:** in-process test fixtures with no required infrastructure.
- **Workload:** minimal examples chosen by each Owner.
- **Mutation:** test-controlled method calls and interleavings.
- **Oracle:** Owner state, returned contract and architecture source guards.
- **Prerequisites:** Java 21 toolchain; Java 11 compatibility remains enforced
  by the modules consumed by Android.
- **Deliberate nonclaims:** Redis behavior, process boundaries and system
  convergence.
- **Command:** explicit non-Android module `build` tasks in Proof CI.
- **CI cost:** low.

## redis_owner

- **Primary owner:** Java Redis providers and their Server-owned registries.
- **Claim:** atomic owner operations preserve scores, resources, identities,
  bindings and result transitions against real Redis.
- **Failure model:** CAS conflict, cursor boundary, duplicate operation,
  concurrent transition and exact-fence mismatch.
- **World:** one Redis 7 instance and one exact `test_*` scope.
- **Workload:** bounded Owner calls, not a Server process.
- **Mutation:** controlled concurrent Redis operations.
- **Oracle:** owner returns plus exact Redis-visible state.
- **Prerequisites:** Redis 7.
- **Deliberate nonclaims:** HTTP, Adapter, Worker or process recovery.
- **Command:** `.\gradlew.bat :server_jvm:redisOwnerIntegrationTest`.
- **CI cost:** medium.

## runtime_boundary

- **Primary owner:** Server assembly over Kernel Pacer and Transport ports.
- **Claim:** one Java Server context closes the public Task, Result,
  DIRECT_CALL and Worker Serviceability boundaries through WebSocket, Socket
  and Polling witnesses.
- **Failure model:** contract drift, route loss, result correlation failure and
  serviceability polarity failure.
- **World:** one Server context, Redis and finite local Workers.
- **Workload:** minimal finite Tasks and calls.
- **Mutation:** explicit Worker shutdown/reconnect and Adapter evidence.
- **Oracle:** public Runtime APIs and final Task state.
- **Prerequisites:** Redis 7 and deterministic Owner tests.
- **Deliberate nonclaims:** fleet scale, Host restart, workload health and
  capacity.
- **Command:** `.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest`.
- **CI cost:** medium.

## worker_correctness

- **Primary owner:** `:integrations:worker-correctness`.
- **Claim:** the fixed 2x10 Scenario world has exact Lab-to-worker identity,
  Adapter route and Properties relationships; six extension names are
  reachable; 100 batch-call Items succeed; graceful Host restart preserves all 20
  worker IDs.
- **Failure model:** missing/duplicate identity, route mismatch, unreachable
  extension, missing/duplicate Result or restart identity drift.
- **World:** 2 Groups x 10 Workers, one WebSocket Adapter, one Server and one
  Scenario Host.
- **Workload:** one managed `items:call` batch per Group, 50 Items each; Event
  distribution per Group is `17/17/16`.
- **Mutation:** one graceful Scenario Host restart while Server and Redis stay
  running.
- **Oracle:** Lab files, Runtime Preview, Adapter Network, Direct Call, exact
  call-response message IDs and 100 `SUCCEEDED` statuses. Result payload is
  opaque.
- **Prerequisites:** Redis 7, Server profile and Scenario distribution.
- **Deliberate nonclaims:** capability-specific payload values, executing
  Worker, fault convergence, throughput and topology combinations.
- **Command:** `python integrations/worker-correctness/run_worker_correctness.py --redis-url redis://127.0.0.1:6379/15`.
- **CI cost:** medium.

## worker_convergence_health

- **Primary owner:** `:integrations:worker-convergence-health`.
- **Claim:** Adapter, Kernel scheduling and Task finality converge within a
  bounded wait after established Worker mutations, one Server restart and one
  execution-time Host loss.
- **Failure model:** stopped Worker remains HOT, restored Worker stays
  unavailable, changed Properties do not affect matching, Group outage leaks,
  Server restart loses due work, in-flight loss never recovers or final state
  is revoked.
- **World:** two isolated scenarios, each 2 Groups x 50 Workers and one
  WebSocket Adapter.
- **Workload:** ON_DEMAND managed batch calls only. State/server offers 700
  Items with 70 deterministic invalid inputs plus seven offered DELAY and FAIL
  Items each; in-flight loss offers 300 with 30 invalid inputs plus three
  offered DELAY and FAIL Items each. Fault Item counts are offered load, not
  observed execution counts.
- **Mutation:** deterministic stop/start, stopped-state Properties replacement,
  full Group outage, one Server restart with the directed Worker already
  stopped, and Scenario Host kill. After restart, 99 Workers recover before the
  stopped Worker is changed and explicitly started once.
- **Oracle:** established Lab local state followed by independent Network and
  Scheduling convergence plus passive `results:load` observation of named
  witnesses. Host-loss recovery requires all 99 active identities to reconnect
  unchanged and the explicitly targeted backup to become canonical and HOT; it
  does not require all 99 to be simultaneously HOT while due work exists.
  Non-witness outcomes and Result payloads are not assertions.
- **Prerequisites:** Redis 7, Server and Scenario Host; each scenario owns an
  isolated scope and Lab root.
- **Deliberate nonclaims:** exact intermediate order, latency SLA, retry count,
  absence of transient serviceability regression, all-offered success,
  background fault Result status or execution count, executing Worker, random
  coverage, throughput and soak.
- **Command:** `python integrations/worker-convergence-health/run_worker_convergence_health.py --scenario all --redis-url redis://127.0.0.1:6379/15`.
- **CI execution:** `state` and `task-fault` run as independent matrix jobs with
  isolated Redis services, scopes and artifacts; the job ID exposes one
  aggregate Proof Gate result.
- **CI cost:** medium per scenario; high when run locally through `all`.

## worker_websocket_scale

- **Primary owner:** `:integrations:worker-websocket-scale` and its separate
  workflow.
- **Claim:** one Java 21 Host prepares 10,000 identities and sustains at least
  9,900 connected-and-HOT Workers through a stable window, finite samples and
  one Server restart while native threads remain bounded.
- **Failure model:** connection ceiling, platform-thread explosion, FD or
  memory exhaustion and reconnect collapse.
- **World:** one Group, 10,000 WebSocket Workers, one Adapter and one Server.
- **Workload:** two bounded 100-Item samples.
- **Mutation:** one Server restart with Host retained.
- **Oracle:** paged Network/Scheduling intersection, worker-ID digest and Linux
  process resource samples.
- **Prerequisites:** Linux, Java 21, Redis 7.4 and `nofile >= 65536`.
- **Deliberate nonclaims:** exact 10,000 online, throughput, Handler
  concurrency, topology breadth and soak.
- **Command:** `python integrations/worker-websocket-scale/run_worker_websocket_scale.py --workers 10000 --minimum-converged 9900 --redis-url redis://127.0.0.1:6379/15`.
- **CI cost:** very high; nightly/manual only.

## android_host

- **Primary owner:** Android Worker and XA Android modules.
- **Claim:** Android library assembly, identity persistence, capability
  Definitions, local lifecycle, Control HTTP and Java proof clients remain
  compatible.
- **Failure model:** lifecycle race, callback ordering, persistence conflict or
  assembly drift.
- **World:** Robolectric, MockWebServer and JDK HttpServer.
- **Workload:** minimal deterministic calls.
- **Mutation:** lifecycle and network callback controls.
- **Oracle:** Android state, mock HTTP/WebSocket evidence and host contracts.
- **Prerequisites:** Android SDK 36.
- **Deliberate nonclaims:** real process, Doze, vendor policy and physical
  device behavior.
- **Command:** Android Debug tasks plus
  `:integrations:android-worker-proof:test`.
- **Required CI prerequisite:** Android APK Assembly builds the Debug and three
  fixed Lab APKs independently. Host selection requires this job even when no
  Emulator artifact upload is needed.
- **CI cost:** medium.

## android_emulator

- **Primary owner:** `:integrations:android-worker-proof`; the shell owns only
  external process choreography.
- **Claim:** one API 33 Debug App proves exact ten-Item lifecycle Correctness,
  physical-route recovery, in-flight Handler process loss, endpoint exhaustion
  and explicit identity-stable recovery; three fixed Lab application IDs add
  same-Group identity isolation, identity-bounded Property matching and partial
  process outage.
- **Failure model:** Handler failure terminates a run, physical route loss or
  Android process death loses Task finality, endpoint loss auto-restarts, one
  App loss degrades its peers, serviceability remains stale, or identity drifts.
- **World:** one KVM Android Emulator with cached-app freezing disabled, Redis,
  Server, one Debug APK and three Debug-derived Lab APKs in the same
  WorkerGroup.
- **Workload:** ten sequential single-App DELAY Items, one process-loss DELAY,
  three Triad-targeted DELAY Items, plus named FAIL, Probe and recovery
  witnesses.
- **Mutation:** explicit stop/start, Adapter close-current, force-stop during an
  active DELAY, Server loss, Debug App process restart and one `lab2`
  force-stop/restart.
- **Oracle:** device-local state only establishes local mutations; independent
  Network, Scheduling, Direct Call, `items:call`, and `results:load` APIs prove
  system behavior. Result payloads remain opaque.
- **Prerequisites:** Linux KVM and the Android APK Assembly artifact. Emulator
  execution does not wait for Android Host tests unless that lane is selected
  independently.
- **Deliberate nonclaims:** throughput, Handler concurrency, exact connection
  attempts, transient Score sequence, UI behavior, arbitrary replica counts,
  dynamic Properties re-Prepare end to end, multi-device compatibility,
  cached-process survival, Doze/OEM policy and physical-device background
  behavior.
- **Command:** `Android Worker Proof` in Proof CI.
- **CI cost:** high.

## frontend

- **Primary owner:** `frontend/`.
- **Claim:** Runtime projections, finite Task workbench and static API Reference
  remain lint-clean, type-safe, tested and buildable.
- **Failure model:** schema, state, input, build or public-demo drift.
- **World:** Node test/build environment.
- **Workload:** finite mocked API interactions.
- **Mutation:** store and component actions.
- **Oracle:** tests, type checker and production builds.
- **Prerequisites:** Node and pnpm.
- **Deliberate nonclaims:** browser compatibility and visual regression.
- **Command:** `pnpm lint`, `typecheck`, `test`, `build`, `build:demo`.
- **CI cost:** low.

## runtime_distribution

- **Primary owner:** `distribution/server` and `distribution/worker-sdk`.
- **Claim:** publishable archives work outside the checkout and contain only
  declared runtime/publication boundaries.
- **Failure model:** missing artifact, dependency/POM drift, private runtime
  leakage or packaged profile failure.
- **World:** extracted distribution, Redis and external Android consumer build.
- **Workload:** finite packaged Server/Profile and SDK consumption calls.
- **Mutation:** process start/stop from extracted archives.
- **Oracle:** archive contents, public endpoints and external build result.
- **Prerequisites:** Java, Redis, Android SDK, Node and pnpm.
- **Deliberate nonclaims:** OCI deployment and Redis lifecycle.
- **Command:** distribution integration tests with
  `-PxaMassVersion=0.5.0`.
- **CI cost:** high.

## docs_contract

- **Primary owner:** `.github/scripts/check_docs.py`.
- **Claim:** current documentation entrypoints, relative links and retired
  vocabulary remain converged.
- **Failure model:** stale current link, missing entrypoint or retired current
  narrative.
- **World:** tracked Markdown and the source human overview.
- **Workload:** static scan.
- **Mutation:** repository documentation changes.
- **Oracle:** checked link/vocabulary rules.
- **Prerequisites:** Python standard library.
- **Deliberate nonclaims:** implementation behavior.
- **Command:** `python .github/scripts/check_docs.py`.
- **CI cost:** low.
