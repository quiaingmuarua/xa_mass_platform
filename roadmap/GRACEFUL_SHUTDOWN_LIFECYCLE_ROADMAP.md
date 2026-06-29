# Graceful Shutdown And Lifecycle Coordination Roadmap

Status: proposed convergence roadmap.

This roadmap coordinates runtime shutdown order across SDK assembly, engine
kernel, transport runtime, worker runtime, Redis-backed runtime stores, and
server-local review materialization. It is not a general thread cleanup
roadmap. The correctness target is that shutdown is ordered, bounded,
idempotent, and reviewable.

The motivating production issue is that lifecycle-sensitive components own
their own executors and close hooks today:

- `TaskResultRepairPump`
- `LeaseExpireWatchdog`
- `RuntimeReadyDispatchPump`
- `WorkerCommandMaintenanceWatchdog`
- `ServerSessionManager`
- `TransportNodeRegistryHeartbeat`
- Redis-backed worker/task/transport runtimes
- server-local review report queues

Some of these independent threads are justified. The current gap is that their
stop order, drain behavior, and close ownership are spread across several
classes and are not protected by a single lifecycle contract.

## Current Code Facts

- `MassApplication.stop()` already has a partial top-level order: stop
  transport servers, stop managed transport adapters, stop transport node
  heartbeat, stop distributed transport channels/queues by role, stop result
  ingress drains, then stop `MassEngine`.
- Result ingress drain ownership now sits in `TaskResultIngressQueueDrain` over
  `TransportResultIngressQueue`; the old buffered result ingress channel has
  been removed.
- `MassEngine.stop()` stops `EngineRuntimeBridge`, then
  `EngineRuntimeKernel`, then calls `EngineConfig.shutdownTaskRuntime()`.
- `EngineRuntimeKernel.stop()` unregisters event listeners and wakeup callbacks
  before stopping `LeaseExpireWatchdog`, `WorkerCommandMaintenanceWatchdog`,
  `RuntimeReadyDispatchPump`, and `TaskAssignWorker`.
- `TaskManager.shutdown()` stops `TaskDispatchRequestService` and
  `TaskResultService` before shutting down `TaskResultRuntime` and
  `TaskWorkRuntime`.
- `TaskResultService.shutdown()` stops `TaskResultRepairPump`; the repair pump
  currently uses its own single-thread scheduled executor and does not await
  termination.
- `LeaseExpireWatchdog`, `RuntimeReadyDispatchPump`, and
  `WorkerCommandMaintenanceWatchdog` each create their own scheduler and use
  `shutdownNow()` during stop. Some await termination; behavior is not uniform.
- `TransportNodeRegistryHeartbeat.stop()` stops its scheduler and then marks
  the node offline.
- `ServerSessionManager` owns a route-owner refresh executor and shuts it down
  when the session manager stops.
- Redis runtime components such as `RedisWorkerRegistry`,
  `RedisTaskWorkRuntime`, `RedisTaskResultRuntime`, and
  `RedisTransportDispatchHandoff` own close/shutdown state, often guarded
  by `AtomicBoolean`.
- Existing lifecycle order is therefore real, but it is implicit and split
  across SDK assembly, engine kernel, task manager, transport runtime, and
  individual runtime implementations.

## Owner Review

1. **This is a correctness roadmap, not cosmetic cleanup.**
   Result drain, repair pump shutdown, lease expiry, redispatch, worker
   presence, and Redis connection close order can affect visible task state.

2. **Do not start by sharing all executors.**
   Independent schedulers are not automatically waste. They provide isolation
   between lease expiry, runtime-ready scanning, command retry, heartbeat, and
   result repair. Consolidating them before inventory can create hidden
   contention and harder failure isolation.

3. **The real owner boundary is phase ordering.**
   Components may keep local executors, but they must declare what phase they
   belong to: external ingress, dispatch production, result drain, repair,
   runtime store shutdown, or external resource close.

4. **Shutdown must be bounded and observable.**
   Every phase needs a timeout, failure logging, and a decision on whether
   failure is fatal, warning-only, or best-effort cleanup.

5. **Engine should not own transport shutdown.**
   Engine owns runtime decision loops and result convergence. SDK/server
   assembly owns the order between transport ingress, buffers, engine stop, and
   infrastructure close.

6. **Redis/client close should be last for its dependent lane.**
   Pumps, schedulers, and repair loops must not keep running after the runtime
   store or Redis connection they call has been closed.

7. **Thread count should be justified by role, not minimized blindly.**
   A small number of named daemon schedulers is acceptable. A large number of
   per-session, per-worker, or unbounded scheduled executors is not. This
   roadmap must distinguish fixed service threads from scale-dependent thread
   creation.

## Boundary Decision

Lifecycle coordination belongs to assembly and owner modules, not to a generic
container framework.

```text
MassApplication
  owns cross-module start/stop ordering
  owns transport ingress, transport buffers, engine stop, and infra close order

MassEngine
  owns engine bridge/kernel/task-runtime stop ordering

EngineRuntimeKernel
  owns engine producer loop shutdown:
  lease watchdog, ready pump, assignment queue, command maintenance

TaskManager / TaskResultService
  own result repair before result runtime shutdown

Transport runtime
  owns adapter/session/heartbeat local stop behavior

Redis/runtime implementations
  own idempotent close of their own connections and stores
```

The target is an explicit lifecycle phase contract plus local stop behavior
that implements that contract. Introduce small lifecycle handles only where
they make phase ownership testable. Do not introduce pass-through facades whose
only purpose is to make the current code look smaller.

## Thread And Scheduler Policy

Classify every service thread or scheduler before changing it:

| Classification | Meaning | Default Action |
| --- | --- | --- |
| Fixed owner loop | One scheduler per process for a runtime owner, such as lease expiry or repair | Keep unless stop order or timeout is broken |
| Fixed transport loop | One scheduler per transport node/session manager, such as heartbeat or presence refresh | Keep if bounded by configured adapter count |
| Scale-dependent loop | One scheduler per task, worker, session, or item | High-risk; remove or retarget to a bounded executor |
| Burst async drainer | Queue drainer that protects transport threads from runtime work | Keep if drain order and timeout are explicit |
| Shared execution pool | Existing bounded or virtual-thread runtime executor | Prefer for ad hoc async work when owner isolation is not required |
| Redundant loop | Periodic loop duplicating another watchdog, pump, or event wakeup | Remove or merge after evidence |

Resource policy:

- Do not merge owner loops only to reduce the number of threads.
- Do merge or retarget when loops are scale-dependent, redundant, unnamed,
  unbounded, or cannot be stopped independently.
- Every retained loop must have a thread name, owner, phase, stop method,
  timeout behavior, and test or inventory evidence.
- `shutdownNow()` is acceptable only when the component can tolerate
  interruption. Otherwise use signal, drain, await, then force-stop.

## Target Shutdown Phases

These phases are the proposed baseline for GSL-0/GSL-1 review, not a claim
that the implementation already has this exact contract. GSL-0 must validate
or correct the phase placement for each current component. GSL-1 then turns the
corrected version into the accepted lifecycle phase contract.

The exact names can change during inventory, but the order must remain visible:

1. **Stop External Ingress**
   - stop transport servers from accepting new connections or requests
   - stop managed adapters from accepting new worker traffic
   - stop server-local materialization/report ingress if configured

2. **Stop Dispatch Producers**
   - stop worker availability wakeups
   - stop lease expiry redispatch production
   - stop runtime-ready dispatch scans
   - stop assignment queue workers
   - stop worker command maintenance delivery/retry scans

3. **Drain Accepted Result Ingress**
   - stop result ingress queue drains after accepted messages are handed to
     engine result convergence or bounded drain limits are reached
   - record unprocessed counts when bounded drain cannot finish

4. **Stop Result Repair And Runtime Services**
   - stop `TaskResultRepairPump`
   - stop dispatch request service
   - stop result service
   - stop task result and work runtimes after repair/result services are quiet

5. **Close Runtime Stores And Transport Infrastructure**
   - close Redis-backed runtimes, handoffs, inboxes, registries, and stores
   - close transport delivery service and worker presence store
   - stop runtime task executors

6. **Mark Offline / Final Cleanup**
   - mark transport node offline at the correct point for the runtime role
   - clear lifecycle callbacks and references
   - keep stop idempotent for repeated stop and failed-start cleanup

## Non-Goals

1. No change to task scheduling, matching, lease semantics, result convergence,
   terminal policy, or retry policy.
2. No generic service-container framework.
3. No global shared scheduler as a first step.
4. No conversion from scheduled loops to event-driven logic unless inventory
   proves the loop is redundant or scale-dependent.
5. No compatibility aliases for old lifecycle paths.
6. No server framework shutdown-hook redesign unless required to call the same
   assembly stop path.
7. No new DB-backed lifecycle state.
8. No attempt to make shutdown exact-once. The target is bounded, idempotent,
   ordered, and observable.

## Hard Rules

1. Do not start by deleting executors or replacing all schedulers with one
   shared pool. That would optimize the symptom before proving lifecycle
   ownership.
2. GSL-0 inventory and GSL-1 phase contract must land before scheduler
   consolidation or cleanup.
3. Every slice must compile and pass its targeted tests before the next slice
   starts. No "break now, repair later" intermediate state.
4. Result ingress that has already been accepted by the local runtime must be
   drained or counted before engine result runtime shutdown.
5. Dispatch-producing loops must stop before their runtime stores and Redis
   resources close.
6. No new scheduler, owned thread, or drainer may be added in core modules
   without lifecycle owner, phase, timeout, and shutdown proof.
7. Do not move transport lifecycle ownership into engine to simplify ordering.
   SDK/server assembly owns cross-module ordering.

Start with an inventory and phase contract. Then align stop order, add proofs,
and only then decide whether any scheduler is redundant or wasteful.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Engine kernel stop-order changes accidentally alter scheduling behavior | scheduling, lease expiry, or redispatch tests become timing-sensitive or regress | keep GSL-2 scoped to shutdown paths; add stop-state proof without changing active scheduling semantics |
| Result drain or repair ordering changes mask stale-result bugs | accepted results may be dropped or late repair may run against closed runtime state | GSL-3 must prove buffered accepted results drain before engine stop and repair stops before result runtime shutdown |
| Redis close ownership changes break soak or chaos-runner lifecycle assumptions | distributed runs may fail during restart, late replay, or transport handoff cleanup | GSL-5 must run Redis-backed lifecycle/e2e proof and document shared client/connection ownership |
| Scheduler consolidation changes timing | watchdog, runtime-ready, or command-maintenance tests can become flaky | defer consolidation to GSL-6 and require behavior-neutral targeted tests for each changed loop |
| Existing stop-order tests rely on reflection-injected mocks | tests may need adaptation when phase contracts become explicit | inventory current stop-order tests in GSL-0 and update tests to assert phases rather than private field names |
| Trace event additions become noisy or misleading | shutdown traces could look authoritative before phase semantics are correct | add trace events after GSL-1 contract; keep names phase-based and update `TRACE_CONTRACT.md` with current behavior only |

## Cross-Roadmap Touchpoints

- [`ENGINE_KERNEL_CONVERGENCE_ROADMAP.md`](../doc/archive/xa-mass-engine/2026-06-02_ENGINE_KERNEL_CONVERGENCE_ROADMAP.md):
  GSL-2/GSL-3 touch `EngineRuntimeKernel`, `TaskResultRepairPump`, and result
  convergence lifecycle. GSL must not reopen EKC owner decisions or introduce
  new SDK-facing engine internals.
- Java SDK worker-session lifecycle, documented in
  `sdk/xa-mass-java-sdk/README.md` and
  `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`, overlaps with GSL
  transport/session lifecycle. GSL-4 should record any public worker-session
  lifecycle assumptions before changing transport presence or session stop
  behavior.
- [`INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md`](../doc/archive/integrations/2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md):
  SDK adoption and worker-pack runs may depend on current polling/WebSocket
  worker session stop behavior. GSL tests should avoid breaking those black-box
  paths without updating their verification lane.
- [`REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md`](../doc/archive/xa-mass-engine/2026-05-30_REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md):
  server-local review report queues are optional materialization infrastructure
  and should stay outside engine shutdown truth. GSL may coordinate their
  stop/drain only as server assembly behavior.

## GSL-0 Inventory And Thread Classification

Goal: produce a current lifecycle and scheduler inventory.

Scope:

- Create `roadmap/GRACEFUL_SHUTDOWN_LIFECYCLE_INVENTORY.md`.
- List every production `ScheduledExecutorService`, owned thread, drainer,
  runtime executor, `AtomicBoolean running`, `shutdown()`, `stop()`, and
  `close()` in:
  - `xa-mass-embedded-sdk`
  - `xa-mass-engine`
  - `xa-mass-server`
  - `transport`
  - `platform_infra`
  - `xa-mass-worker-runtime`
- For each component, classify:
  - owner module
  - start caller
  - stop caller
  - lifecycle phase
  - whether it accepts new work, produces dispatch/result work, drains
    accepted work, repairs state, or only maintains presence
  - whether thread use is fixed, scale-dependent, redundant, or unknown
  - stop behavior: signal, drain, await timeout, interrupt, close external
    resource
  - failure behavior during stop
- Mark each component as keep, retarget, merge candidate, or remove candidate.
- Record cross-roadmap touchpoints for components owned or planned by EKC,
  WSDK, SDK adoption, or review materialization roadmaps.

Acceptance:

- Inventory accounts for every production lifecycle component found by source
  search.
- No component is proposed for removal without a stated owner and replacement
  behavior.
- Inventory identifies whether current thread usage is fixed-cost or
  scale-dependent.
- Inventory identifies any active roadmap whose scope overlaps a lifecycle
  component before code changes begin.

## GSL-1 Lifecycle Phase Contract

Goal: define the shutdown phase contract before code movement.

Scope:

- Add a concise lifecycle phase section to this roadmap or a new
  `doc/LIFECYCLE_COORDINATION_BASELINE.md` if the contract becomes broadly
  reusable.
- Treat the `Target Shutdown Phases` section in this roadmap as a proposed
  baseline. GSL-1 either confirms it after GSL-0 or updates it with the
  inventory-corrected phase contract.
- Document start order and stop order for:
  - embedded runtime
  - engine-producer runtime
  - transport-consumer runtime
- Specify which phase owns:
  - transport server stop
  - adapter stop
  - transport node heartbeat/offline marking
  - task dispatch handoff pump stop
  - distributed result ingress queue drain stop
  - result ingest buffer drain
  - engine kernel stop
  - task runtime shutdown
  - Redis/client close
- Define timeout policy for each phase.
- Decide whether shutdown phase trace events are part of the baseline contract
  or a GSL-7 observability follow-up.

Acceptance:

- The phase contract states that accepted result ingress drains before engine
  result runtime shutdown.
- The phase contract states that dispatch producers stop before runtime stores
  and Redis resources close.
- The phase contract distinguishes fatal startup failure cleanup from normal
  graceful shutdown.
- The accepted contract explicitly says whether shutdown trace events are in
  scope and, if so, which phases emit them.
- The contract is referenced from `doc/README.md` or the owning owner README if
  it becomes a current baseline.

## GSL-2 Engine Kernel Stop Coordination

Goal: make engine-local shutdown order explicit and testable.

Scope:

- Keep `EngineRuntimeKernel` as the owner of engine producer loop shutdown.
- Verify and, if needed, adjust order:
  - unregister listeners and wakeup callbacks
  - stop lease expiry redispatch production
  - stop worker command maintenance
  - stop runtime-ready scans
  - stop assignment queue workers
- Add tests proving no new assignment or redispatch is produced after kernel
  stop begins.
- Add tests proving repeated `MassEngine.stop()` / `EngineRuntimeKernel.stop()`
  is idempotent.

Acceptance:

- Engine kernel stop order is covered by unit or integration proof.
- Stop clears wakeup callbacks before external worker availability events can
  resubmit work.
- Lease expiry scans cannot publish new redispatch after stop begins.
- `RuntimeReadyDispatchPump` does not leave dispatch executor work running
  after stop returns.

## GSL-3 Result Ingress Drain And Repair Ordering

Goal: protect accepted worker results during shutdown.

Scope:

- Verify `MassApplication.stop()` stops `TaskResultIngressQueueDrain` before
  `MassEngine.stop()` for runtime roles that ingest worker results.
- Verify distributed result ingress queue drains stop in the correct order
  relative to engine shutdown for engine-producer mode.
- If GSL-1 accepts shutdown trace events, emit or prepare event emission for
  result-drain phases such as `RESULT_BUFFER_DRAIN_STARTED` and
  `RESULT_BUFFER_DRAIN_COMPLETED`.
- Make `TaskResultRepairPump.shutdown()` bounded and observable:
  - signal stop
  - await termination
  - log unrepaired candidate count or timeout when available
- Add tests proving:
  - queued buffered results are forwarded before engine stop
  - repair pump stops before `TaskResultRuntime.shutdown()`
  - no repair scan runs after result runtime shutdown

Acceptance:

- Accepted buffered results are either applied or counted/logged as not drained
  before engine runtime shutdown.
- Repair pump stop order is tested against result runtime shutdown.
- `TaskResultRepairPump` has consistent timeout behavior with other engine
  watchdogs.
- Result-drain trace behavior is either implemented or explicitly deferred to
  GSL-7 according to the GSL-1 phase contract.

## GSL-4 Transport And Presence Lifecycle Coordination

Goal: align transport stop/offline behavior with engine and result drain.

Scope:

- Verify stop order for:
  - WebSocket transport server
  - socket transport server
  - polling worker paths
  - `ServerSessionManager`
  - `SocketSessionManager`
  - `TransportNodeRegistryHeartbeat`
  - worker presence store
- Decide when transport node should be marked offline:
  - before stopping external ingress
  - after stopping external ingress
  - after accepted result drain
- Document the role-specific answer for embedded, engine-producer, and
  transport-consumer modes.
- Add tests for server/session manager shutdown if current tests do not cover
  presence refresh executor termination and active channel close order.

Acceptance:

- Transport node offline marking cannot race with still-accepting transport
  servers in a way that misroutes new dispatch.
- Presence refresh executors are bounded by adapter/session-manager instances,
  not by worker count or session count.
- Transport stop paths are idempotent and tolerate partial startup failure.

## GSL-5 Runtime Store And Redis Close Ownership

Goal: prevent runtime loops from calling closed Redis/runtime resources.

Scope:

- Inventory all Redis-backed runtime stores, registries, handoffs, and inboxes
  with `shutdown()` or `close()`.
- Define close ownership for:
  - `RedisWorkerRegistry`
  - `RedisTaskWorkRuntime`
  - `RedisTaskResultRuntime`
  - `RedisTransportDispatchHandoff`
  - Redis transport inboxes and dispatch handoffs
- Ensure close happens after dependent pumps/watchdogs are stopped or drained.
- Verify shared client/connection ownership where components may close the same
  underlying resource.

Acceptance:

- No runtime loop remains capable of calling a closed Redis-backed runtime after
  normal shutdown completes.
- Close methods are idempotent.
- Shared external resource ownership is documented in inventory or owner
  README.

## GSL-6 Scheduler Resource Policy And Cleanup

Goal: reduce resource waste only after lifecycle ownership is clear.

Scope:

- Use GSL-0 classification to identify:
  - fixed owner loops to keep
  - scale-dependent loops to remove or retarget
  - redundant loops to merge or delete
  - loops that can use an existing bounded `RuntimeTaskExecutor`
- For retained loops, standardize:
  - thread names
  - daemon setting
  - start idempotency
  - stop idempotency
  - timeout behavior
  - logging
- Only introduce a shared scheduler if inventory proves several loops have the
  same owner phase, no isolation value, and compatible timing requirements.

Acceptance:

- Every retained scheduler has an explicit justification.
- No scale-dependent scheduled executor remains unless intentionally documented
  with a bound.
- Resource changes do not alter scheduling, lease, command, or result behavior.

## GSL-7 Guards, Proof Registry, And Docs

Goal: keep lifecycle coordination from regressing.

Scope:

- Add architecture/source guards where practical:
  - no new production `ScheduledExecutorService` without lifecycle inventory or
    owner classification
  - no new direct `new Thread(...)` / virtual-thread drainer without owner and
    shutdown proof
  - no engine result runtime shutdown before result repair shutdown in tests
- Update:
  - `doc/PROOF_REGISTRY.md` if new lifecycle proof lanes are added
  - `doc/TESTING_INDEX.md` if lifecycle suites are created or moved
  - `doc/TRACE_CONTRACT.md` if shutdown phase events are added
  - `xa-mass-testing/VERIFIED_RUNBOOK.md` if shutdown verification commands become part of
    standard regression
- Add shutdown phase trace events if accepted by GSL-1, with candidate event
  names such as:
  - `ENGINE_SHUTDOWN_STARTED`
  - `ENGINE_SHUTDOWN_COMPLETED`
  - `TRANSPORT_DRAIN_STARTED`
  - `TRANSPORT_DRAIN_COMPLETED`
  - `RESULT_BUFFER_DRAIN_STARTED`
  - `RESULT_BUFFER_DRAIN_COMPLETED`
- Run residue scan for stale lifecycle claims once implemented.

Acceptance:

- Guard tests fail on unclassified new service schedulers in core modules.
- Lifecycle proof entries identify representative engine, server, and
  transport tests.
- Shutdown trace events, if added, are documented in `TRACE_CONTRACT.md` and
  have trace-observed or unit proof appropriate to their owner.
- Documentation distinguishes current implemented lifecycle behavior from
  proposed future cleanup.

## Suggested Implementation Order

1. GSL-0 inventory and thread classification.
2. GSL-1 lifecycle phase contract.
3. GSL-2 engine kernel stop coordination.
4. GSL-3 result ingress drain and repair ordering.
5. GSL-4 transport and presence lifecycle coordination.
6. GSL-5 runtime store and Redis close ownership.
7. GSL-6 scheduler resource policy and cleanup.
8. GSL-7 guards, proof registry, and docs.

GSL-2 and GSL-3 are the highest correctness ROI. GSL-6 should not begin until
GSL-0 and GSL-1 are complete.

## Verification Candidates

Inventory and source scans:

```bash
rg -n "ScheduledExecutorService|newSingleThreadScheduledExecutor|scheduleAtFixedRate|scheduleWithFixedDelay|new Thread|Thread.ofVirtual|AtomicBoolean running|shutdown\\(|shutdownNow\\(|close\\(|stop\\(" xa-mass-embedded-sdk xa-mass-engine xa-mass-server transport platform_infra xa-mass-worker-runtime -S
```

Targeted tests after implementation will be refined by GSL-0. Initial
candidates:

```bash
mvn -pl xa-mass-engine,xa-mass-embedded-sdk,xa-mass-server,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,platform_infra/mass-runtime-redis -am "-Dtest=*Lifecycle*,*Shutdown*,*Watchdog*,*RuntimeReady*,*TaskResultRepair*,*TransportNode*" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Full regression candidate for lifecycle-sensitive changes:

```bash
mvn -pl xa-mass-engine,xa-mass-embedded-sdk,xa-mass-server,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,platform_infra/mass-runtime-redis,xa-mass-testing -am test
```
