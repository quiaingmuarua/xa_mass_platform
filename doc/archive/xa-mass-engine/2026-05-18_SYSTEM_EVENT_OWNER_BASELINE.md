# System Event Owner Baseline

Last updated: 2026-05-18

Status: SE-0 baseline. This is an owner-boundary document, not a worker command
or worker state-report implementation plan.

## Purpose

System events are useful as worker-side ingress and historical evidence, but
they must not become a hidden lifecycle owner. The engine scheduling kernel
should consume narrow read models and owner outputs, not raw operational events.

```text
system event channel
  -> transports facts from workers/adapters

owner
  -> validates, stores, projects, repairs, and defines lifecycle truth

read model
  -> exposes bounded evidence to scheduling or diagnostics
```

The current implementation only supports worker presence events through the
transport-neutral `WorkerSystemEventChannel`. Future worker command, worker
state-report, and worker capability self-report flows need separate owner
roadmaps before they mutate runtime state.

## Current Owner Map

| Input or surface | Current owner | Current effect | Must not own |
| --- | --- | --- | --- |
| `WorkerSystemEventChannel.publishWorkerOnline(...)` | transport/system-event ingress | publishes worker-online presence signal into the configured runtime channel | worker capability truth, task result finality, worker command lifecycle, worker state projection |
| `WorkerSystemEventChannel.publishWorkerOffline(...)` | transport/system-event ingress | publishes worker-offline presence signal into the configured runtime channel | worker capability truth, task retry/release, worker command lifecycle |
| `WorkerSystemEventChannel.publishWorkerHeartbeat(...)` | transport/system-event ingress | optional heartbeat signal; default no-op | scheduling load truth, worker state projection, worker capability truth |
| `TracingWorkerSystemEventChannel` | transport runtime trace decorator | emits canonical `WORKER_ONLINE` / `WORKER_OFFLINE` trace evidence while preserving channel behavior | reachability truth by itself, capability truth, task lifecycle mutation |
| `WorkerReachabilityView` | transport reachability read seam consumed by engine matching | exposes online/offline dispatchability evidence | worker device state, capability, load, command status |
| `WorkerLoadView` | engine scheduling resource read model | exposes active/reserved worker capacity evidence | reachability, worker state reports, device management, command lifecycle |
| `WorkerManager` / `WorkerRegistrySnapshot` | engine worker registration and capability snapshot owner | owns worker registration view and WorkerGroup candidate-source snapshot | transport reachability truth, raw device state, command lifecycle |
| `TaskResultService` / `TaskResultRuntime` | engine task result owner | consumes task work result payloads only | worker command ack/status, worker state report, operator-control response |
| trace/audit plane | historical evidence | records observed lifecycle and diagnostic facts | current runtime truth, repair, scheduling ownership |

## Future Owner Slots

Future features should grow as separate owner paths:

```text
Worker command request
  -> WorkerCommandLifecycleOwner
  -> command status read view
  -> trace evidence

Worker state report
  -> WorkerStateProjectionOwner
  -> bounded state projection / derived scheduling evidence
  -> trace evidence

Worker capability self-report
  -> WorkerCapabilityReportOwner
  -> worker registry / WorkerGroup snapshot refresh
  -> trace evidence
```

These owners may use a system-event ingress channel, but the channel itself
must remain transport/input plumbing. It must not decide task status, result
finality, dispatch eligibility, worker capability, or command status.

The worker command path is tracked separately in
`../roadmap/WORKER_COMMAND_LIFECYCLE_ROADMAP.md`. That roadmap is a future owner
plan, not current runtime behavior.

## Hard Boundaries

- Worker command ack/status must not be written to `TaskResultRuntime`.
- Worker state reports must not be treated as transport reachability truth.
- Raw worker state must not enter matching or ranking directly; scheduling may
  consume only bounded derived evidence from an approved projection owner.
- Worker capability self-report must not bypass `WorkerManager` /
  `WorkerRegistrySnapshot` / `WorkerCandidateIndex` ownership.
- `WorkerLoadView` remains runtime claim/final plus reservation evidence, not a
  device-state projection.
- `WorkerReachabilityView` remains transport presence evidence, not a generic
  worker health model.
- `WorkerSystemEventChannel` must not import engine scheduling packages or
  mutate engine lifecycle state directly.
- A shared runtime `UnifiedEventEnvelope` is still future-only; add it only
  after concrete command/state/capability owners exist and duplicate carrier
  shape becomes a real problem.

## Proof Surface

SE-0 is proved by architecture guards, not by new runtime behavior:

- engine result owners reject worker command/state-report drift
- reachability and load read models reject command/state/capability ownership
- transport system-event channels reject engine scheduling dependencies
- event metadata guards continue to prevent `TargetScope` or `ResponseMode`
  from opening hidden runtime paths

Future SE phases should add trace-observed scenarios only after they introduce
real behavior. For example, a worker command lifecycle phase should prove
command status through a command owner trace, not through task result rows.
