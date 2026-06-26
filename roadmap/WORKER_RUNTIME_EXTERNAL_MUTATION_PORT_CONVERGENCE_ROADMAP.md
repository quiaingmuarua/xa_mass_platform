# Worker Runtime External Mutation Port Convergence Roadmap

Status: proposed direction document.

## Summary

This roadmap cuts off direct cross-module mutation of the worker-runtime state
machine.

The immediate goal is not to make every worker-runtime internal class perfect.
The immediate goal is to stop `embedded-sdk`, transport assembly, and engine
support wiring from directly opening, closing, or rewriting worker-runtime
eligibility state. Worker-runtime may still use its current internal gate,
score-band, registry, and selection owners while this roadmap moves all external
facts through a worker-runtime-owned signal ingress.

Target principle:

```text
external module observes a fact
  -> publishes a worker-runtime signal/request
  -> worker-runtime classifies the signal
  -> worker-runtime owns the state transition
```

This is an architecture boundary roadmap. It intentionally prioritizes owner
closure over feature behavior changes.

## Current Code Observations

- `MassApplication.createCurrentSessionDisconnectSink()` directly calls
  `engineConfig.getWorkerDispatchBlockRuntime().blockWorkerDispatch(...)`.
- `MassApplication.createCurrentSessionConnectSink()` directly calls
  `engineConfig.getWorkerDispatchRecoveryRuntime().recoverWorkerDispatch(...)`.
- `EngineConfig` exposes `getWorkerDispatchBlockRuntime()` and
  `getWorkerDispatchRecoveryRuntime()`, both backed by `WorkerManager`.
- `TransportAdapterBootstrapContext` injects `CurrentSessionConnectSink` and
  `CurrentSessionDisconnectSink` into `AdapterSessionEvidencePublisher`.
- `AdapterSessionEvidencePublisher.connected(...)` and `claimEndpoint(...)`
  call the connect sink after writing endpoint lease evidence.
- `AdapterSessionEvidencePublisher.disconnected(...)` calls the disconnect sink
  when the endpoint lease release proves the disconnected session was current.
- `WorkerManager` implements `WorkerDispatchBlockRuntime` and
  `WorkerDispatchRecoveryRuntime` directly.
- `WorkerManager.recoverWorkerDispatch(...)` currently allows controlled
  recovery sources and freshness-based recovery through
  `WorkerDispatchRecoveryMode.FRESHNESS_EVIDENCE`.
- `WorkerSelectionRuntime` is the current engine-facing worker selection seam,
  but it is too wide: it exposes lifecycle methods and accepts
  `SelectedWorkerEvidence`, an internal persisted observation shape.
- Current engine architecture guards still protect `WorkerSelectionRuntime` as
  the single selection port. This roadmap must not start by deleting that seam.

## Owner Review

Worker-runtime owns:

- worker declaration and group membership;
- dispatch eligibility;
- score-band slot state;
- worker selection;
- recovery, block, hold, park, and wakeup decisions;
- classification of external worker-runtime signals.

Transport owns:

- adapter/session/network evidence;
- endpoint lease evidence;
- best-effort final-hop delivery;
- result ingress and delivery outcomes.

Transport may publish bounded facts to assembly, but it must not own or call
worker-runtime state transitions.

Engine owns:

- task lifecycle;
- assignment and attempt lifecycle;
- result convergence;
- task-side decisions that may request worker-runtime release/final signals.

Engine may call the worker selection contract during assignment. It must not
reach around that contract to mutate worker-runtime gate, registry, score-band,
or admission state.

Embedded SDK/starter owns:

- assembly;
- dependency wiring;
- in-process adaptation between runtime ports.

It must not mirror worker-runtime lifecycle state or expose direct mutation
ports as convenience getters.

## Boundary Decision

External worker-runtime input must be message-shaped.

The first target contract is a worker-runtime-owned signal ingress:

```java
public interface WorkerRuntimeSignalIngress {
    boolean publish(WorkerRuntimeSignal signal);
}
```

Candidate stable value object:

```java
public record WorkerRuntimeSignal(
        String signalId,
        String signalType,
        String workerGroupId,
        String workerId,
        String reasonCode,
        long observedAtMillis
) {}
```

`signalType` is a module-owned string tag catalog, not a public enum. The tag is
finite and worker-runtime-owned, but the public contract stays string-shaped so
future remote, queue, or out-of-process delivery does not require Java enum
coupling.

Worker-runtime internals may map known tags into finite transition classes such
as negative block, positive recheck request, controlled recovery, or ignored
evidence. External modules must not call those internal transition methods.

The initial implementation may be synchronous and in-process. This roadmap does
not require a queue, event bus, retry loop, poller, or lifecycle owner. The
interface is message-shaped so a queue can be introduced later without changing
external caller semantics.

## Target Shape

Adapter/session fact path:

```text
adapter session owner
  -> AdapterSessionEvidencePublisher writes transport endpoint lease evidence
  -> publishes transport current-session fact to SDK assembly
  -> SDK assembly translates to WorkerRuntimeSignal
  -> WorkerRuntimeSignalIngress.publish(signal)
  -> worker-runtime validates and mutates its own state
```

Connect semantics:

```text
current-session connected
  -> positive recheck request only
  -> worker-runtime may reopen only after validating worker declaration,
     group membership, platform blocks, holds, score-band state, and freshness
```

Disconnect semantics:

```text
current-session disconnected
  -> negative eligibility signal
  -> worker-runtime may close eligibility immediately as best-effort protection
```

Heartbeat semantics:

```text
session heartbeat / refresh
  -> transport freshness evidence only
  -> no worker-runtime signal in this roadmap
```

Engine/resource semantics:

```text
assignment final / release / cancel style facts
  -> worker-runtime-owned resource/selection contract
  -> not SDK-owned direct score/gate mutation
```

The exact resource-release contract is not the first slice of this roadmap, but
the same boundary rule applies: caller facts go through stable worker-runtime
contracts; worker-runtime owns the mutation.

## Interface Contract Rules

Cross-module worker-runtime ports should use only:

- primitives or stable value objects;
- explicit public contract DTOs where callers understand every field;
- functional interfaces only for true callbacks;
- opaque handles/refs that callers save and return without interpretation.

They must not expose:

- worker-runtime registry records;
- score-band observations;
- lease internals;
- persisted internal snapshots;
- same-module wrapper records that only exist to smuggle internal state through
  a public interface.

`SelectedWorkerEvidence` currently violates this direction when used as a
cross-module parameter. That cleanup is tracked here as a later phase, not the
first executable slice.

## Non-Goals

- Do not build a generic event bus.
- Do not add worker-runtime pollers, timers, or maintenance threads.
- Do not redesign score-band Redis shape in this roadmap.
- Do not remove `WorkerSelectionRuntime` in the first slice.
- Do not make transport depend directly on broad worker-runtime internals.
- Do not preserve old mutation ports through aliases or compatibility wrappers
  after in-repo callers move.
- Do not turn `embedded-sdk` into a second worker-runtime lifecycle owner.

## Do Not Start With

Do not start by refactoring `WorkerSelectionRuntime` or deleting
`SelectedWorkerEvidence`.

That seam is already too wide, but current engine mainline and guards rely on
it as the only worker-selection port. Starting there would mix selection
contract cleanup with the higher-ROI external mutation problem.

Start by removing SDK/transport direct access to worker-runtime recovery and
block mutation ports.

## EMP-0 External Mutation Inventory And Allowlist

Goal:

Classify every cross-module worker-runtime state mutation caller before adding
new contracts.

Scope:

- Inventory `sdk`, `transport`, and `xa-mass-engine` main-source callers of:
  - `WorkerDispatchRecoveryRuntime`
  - `WorkerDispatchBlockRuntime`
  - `WorkerDispatchGateRuntime`
  - `WorkerScoreBandSlotRuntime`
  - `WorkerRegistry`
  - `WorkerAdmissionRuntime`
  - `WorkerSelectionRuntime`
- Split production callers from test fixtures.
- Classify each symbol as:
  - allowed temporary selection seam;
  - worker-runtime internal mutation port;
  - assembly-only adapter;
  - stale pass-through;
  - test fixture.

Acceptance:

- The roadmap or a sibling inventory identifies all main-source external
  mutation callers.
- `WorkerSelectionRuntime` is explicitly marked as a temporary allowed engine
  selection seam, not a general worker-runtime mutation surface.
- `WorkerDispatchRecoveryRuntime`, `WorkerDispatchBlockRuntime`,
  `WorkerDispatchGateRuntime`, `WorkerScoreBandSlotRuntime`, `WorkerRegistry`,
  and `WorkerAdmissionRuntime` are marked forbidden for SDK/transport direct
  use after EMP-2.
- Tests that instantiate internal worker-runtime classes are separated from
  production dependency decisions.

## EMP-1 Worker Runtime Signal Ingress Contract

Goal:

Introduce the narrow worker-runtime-owned message ingress that external facts
can target.

Scope:

- Add `WorkerRuntimeSignalIngress` in worker-runtime ownership.
- Add `WorkerRuntimeSignal` as a stable public contract DTO.
- Add worker-runtime-owned string tag constants for the first signal set:
  - current session connected;
  - current session disconnected.
- Add a worker-runtime handler that classifies signals and delegates to
  existing internal block/recheck/recovery mechanisms.
- Keep implementation synchronous and in-process.

Acceptance:

- Signal ingress does not expose score-band records, registry records, endpoint
  leases, adapter session handles, or selection observations.
- Signal type stays string-based at the contract boundary.
- Unknown signal tags are rejected or ignored by worker-runtime, not interpreted
  by SDK/transport.
- The handler is the only place where external signal tags are translated into
  worker-runtime state-machine actions.
- No transport module imports worker-runtime signal classes directly unless an
  explicit dependency decision is made. The default path is SDK assembly
  adaptation.

## EMP-2 Replace Session Connect/Disconnect Mutation Bridges

Goal:

Move transport session connect/disconnect facts off direct worker-runtime
mutation ports.

Scope:

- Replace `CurrentSessionConnectSink` and `CurrentSessionDisconnectSink` with a
  neutral transport current-session fact sink, or keep them only as a temporary
  internal adapter during the same slice and delete them before completion.
- Update `AdapterSessionEvidencePublisher` so:
  - connected/current endpoint claimed publishes a request-only signal;
  - disconnected publishes a negative signal only when endpoint lease release
    confirms the disconnected session was current;
  - heartbeat only refreshes transport endpoint lease evidence.
- Update `TransportAdapterBootstrapContext` to expose the new neutral
  session-fact callback to adapter bootstrap code.
- Update `MassApplication` so it translates transport current-session facts
  into `WorkerRuntimeSignal` and calls `WorkerRuntimeSignalIngress`.
- Remove `EngineConfig.getWorkerDispatchRecoveryRuntime()` from SDK public
  assembly surface.
- Remove `EngineConfig.getWorkerDispatchBlockRuntime()` if its only production
  caller is the transport session bridge. If another production caller remains,
  classify and move it to signal ingress or worker-runtime-owned control.

Acceptance:

- `sdk/xa-mass-embedded-sdk/src/main` no longer calls
  `recoverWorkerDispatch(...)` for transport connect.
- `sdk/xa-mass-embedded-sdk/src/main` no longer calls
  `blockWorkerDispatch(...)` for transport disconnect.
- Transport session connect/disconnect reaches worker-runtime only through
  `WorkerRuntimeSignalIngress`.
- Session heartbeat does not publish a worker-runtime signal.
- Stale or replaced session disconnect still cannot block the new session:
  only current-session release success may publish the disconnect signal.
- Concrete adapters do not depend on worker-runtime mutation ports.

## EMP-3 Engine And SDK Mutation Surface Guard

Goal:

Prevent the old cross-module mutation shape from returning after EMP-2.

Scope:

- Add or update architecture guards so `sdk` and transport main sources cannot
  import or call:
  - `WorkerDispatchRecoveryRuntime`
  - `WorkerDispatchBlockRuntime`
  - `WorkerDispatchGateRuntime`
  - `WorkerScoreBandSlotRuntime`
  - `WorkerRegistry`
- Guard `EngineConfig` against exposing direct recovery/block getters for
  transport/session assembly.
- Guard transport session evidence code so heartbeat cannot publish
  worker-runtime signals.
- Keep worker-runtime internal controlled recovery allowed.
- Keep engine selection through `WorkerSelectionRuntime` allowed until EMP-4 or
  a dedicated selection-contract roadmap replaces it.

Acceptance:

- Guards fail if SDK/transport reintroduce direct recovery/block/gate/score
  mutation calls.
- Guards allow worker-runtime internal calls and controlled recovery policy.
- Guards do not ban `WorkerSelectionRuntime` in engine mainline during this
  roadmap.

## EMP-4 Worker Selection Contract Narrowing Follow-Up

Goal:

Retire the next high-risk cross-module shape after mutation ports are closed.

Scope:

- Review `WorkerSelectionRuntime` as an engine-facing public contract.
- Replace methods that accept `SelectedWorkerEvidence` with either:
  - primitives/stable public DTOs;
  - an opaque selection/release handle;
  - a smaller command record that engine can own and construct safely.
- Separate selection acquire from resource close/final/release commands if that
  makes ownership clearer.
- Update engine guards that currently require `SelectedWorkerEvidence`.

Acceptance:

- Engine no longer needs to construct or interpret internal worker-runtime
  evidence records.
- `SelectedWorkerEvidence` becomes worker-runtime internal or a clearly stable
  public contract with caller-owned fields only.
- Engine still does not use `WorkerRegistry`, `WorkerScoreBandSlotRuntime`, or
  admission internals directly.

This is intentionally a later phase. EMP-1 through EMP-3 should land first.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-engine -am -DskipTests test-compile "-DtrimStackTrace=true"
```

Worker-runtime focused proof:

```powershell
.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=WorkerManagerTest,WorkerSelectionAtomicRuntimeTest" test "-DtrimStackTrace=true"
```

Transport/session bridge proof:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am "-Dtest=AdapterSessionEvidencePublisherTest,TransportConvergenceArchitectureGuardTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest,SocketSessionManagerTest,PollingSessionEvidenceDriverTest" test "-DtrimStackTrace=true"
```

SDK/engine assembly proof:

```powershell
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk,xa-mass-engine -am "-Dtest=MassEngineAssemblyBoundaryTest,EngineSchedulingCoreArchitectureGuardTest,SimpleTaskDispatchBinderTest,TaskWorkerAssignListenerTest,TaskResourceReleaseListenerTest,WorkerDispatchResourceReleaserTest" test "-DtrimStackTrace=true"
```

Residue checks after EMP-2:

```powershell
rg -n "getWorkerDispatchRecoveryRuntime|WorkerDispatchRecoveryRuntime|recoverWorkerDispatch\(" sdk transport --glob "*.java" --glob "!**/target/**"
rg -n "getWorkerDispatchBlockRuntime|WorkerDispatchBlockRuntime|blockWorkerDispatch\(" sdk transport --glob "*.java" --glob "!**/target/**"
rg -n "WorkerScoreBandSlotRuntime|WorkerRegistry|WorkerDispatchGateRuntime" sdk transport xa-mass-engine/src/main --glob "*.java" --glob "!**/target/**"
rg -n "CurrentSessionConnectSink|CurrentSessionDisconnectSink" transport sdk --glob "*.java" --glob "!**/target/**"
```

Expected after EMP-2/EMP-3:

- no SDK/transport main-source direct recovery/block mutation calls;
- no session connect/disconnect sink residue;
- no heartbeat-to-worker-runtime signal path;
- engine mainline still uses only `WorkerSelectionRuntime` for selection until
  EMP-4 starts.

## Roadmap Completion Criteria

- SDK and transport no longer receive or expose direct worker-runtime
  recovery/block/gate/score mutation ports.
- Session connect/disconnect enters worker-runtime through signal ingress only.
- Worker-runtime owns signal classification and state mutation.
- `EngineConfig` no longer exposes direct worker-runtime mutation getters for
  adapter/session assembly.
- Architecture guards protect the boundary.
- `WorkerSelectionRuntime` cleanup is either completed in EMP-4 or explicitly
  moved to a successor roadmap with guards that prevent broader mutation leaks.

## Relationship To Other Roadmaps

- `WORKER_RUNTIME_NETWORK_FRESHNESS_RECHECK_CONVERGENCE_ROADMAP.md` owns the
  detailed freshness and score-band recheck semantics. This roadmap owns the
  higher-ROI external mutation boundary that must exist before those semantics
  can stay contained.
- `WORKER_RUNTIME_POST_SCORE_BAND_RESIDUE_RETIREMENT_ROADMAP.md` owns removal
  of old post-score-band candidate/reservation residue. This roadmap should not
  merge that cleanup into the first slice.
