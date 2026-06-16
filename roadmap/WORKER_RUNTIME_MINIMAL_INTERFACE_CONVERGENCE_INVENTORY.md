# Worker Runtime Minimal Interface Convergence Inventory

Status: WMI-0 baseline inventory plus current implementation notes for
`WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_ROADMAP.md`.

This inventory records the original owner classification and target for the
worker-runtime minimal-interface convergence. It is intentionally code-facing:
the target column names the slice that should remove, retarget, or allow each
usage. Rows under "Symbol Inventory" are baseline rows; consult the current
implementation notes below before treating a row as still present.

## Current Implementation Notes

- WMI-1/WMI-2 mainline has removed `WorkerResourceRuntime` as a production
  aggregate port and moved callers to narrow owner ports.
- `WorkerDeclarationRecord`, `WorkerResourceRecord`, `WorkerMeta`,
  worker-facing SDK/server registration DTOs, and default frontend worker
  views no longer carry `adapterNodeId` / `adapterId`.
- Candidate acquisition is WorkerGroup-scoped; runtime registry candidate
  contracts no longer accept `adapterNodeId`.
- NodeGroupBinding is topology/admin diagnostics in this roadmap and does not
  drive worker dispatch eligibility.
- Remaining legacy/base/diagnostic references such as base `Worker` historical
  fields and engine monkey snapshots are residue/follow-up surfaces, not WMI
  mainline contracts.

## NodeGroupBinding Decision

Decision: `NodeGroupBinding` is topology/admin diagnostics only for this
roadmap. It remains a relation between `AdapterNodeRecord` and
`WorkerGroupRecord`, with enabled/draining metadata available to topology/admin
surfaces. It does not own worker dispatch eligibility.

Reason:

- Scheduling is WorkerGroup and worker evidence based; adapter-node topology
  must not become a worker-selection selector.
- Keeping NodeGroupBinding as an operational dispatch gate would require a
  second internal membership truth for `(adapterNodeId, workerGroupId)`.
  This would preserve adapter-node lifecycle coupling while the roadmap's
  objective is to remove that coupling from worker declaration and selection.
- Existing worker state report and dispatch gate mechanisms already own
  worker-level drain/disable semantics.

Target:

- WMI-2.0 removes NodeGroupBinding dispatch-gate mutation.
- Adapter node and node-group binding APIs remain topology/admin APIs.
- Tests that assert NodeGroupBinding disable/drain blocks scheduling are
  retargeted to assert topology metadata only, or removed if they only prove
  the old dispatch gate.

## Symbol Inventory

| Symbol | Current Owner | Current Usage | Classification | Target |
| --- | --- | --- | --- | --- |
| `WorkerResourceRecord` | worker-runtime resource row | Mutation input, query output, SDK snapshot assembly, starter transport lookup | fat composite model | WMI-1/WMI-3/WMI-4 |
| `WorkerDeclarationRecord` | worker-runtime declaration store | Declaration row but still carries `adapterNodeId`, `adapterId`, `onlineStrategy`, timestamps | non-minimal declaration residue | WMI-1 |
| `WorkerResourceRuntime` | worker-runtime aggregate | Engine/starter/SDK config exposes broad query/declaration/topology/heartbeat port | broad aggregate port | WMI-1 |
| `WorkerResourceQueryRuntime#worker/workers` | worker-runtime query | Engine control, SDK inspection, starter transport lookup | over-wide read model | WMI-1/WMI-3/WMI-4 |
| `WorkerResourceDeclarationRuntime#addWorker/updateWorker` | worker-runtime declaration mutation | SDK/server registration writes full worker row | over-wide mutation contract | WMI-1/WMI-2.3 |
| `WorkerNodeBindingRuntime` | topology/admin | Adapter node and node-group binding mutation | valid topology/admin surface | Allow, WMI-5 docs |
| `TaskDispatchIntent#adapterNodeId` | engine scheduling intent | Reads `TaskSharedConfig.adapterNodeId` | invalid scheduling selector | WMI-2.1 |
| `ResolvedWorkerSchedulingPolicy#adapterNodeId` | engine resolved policy | Carries adapter-node selector into worker task selector | invalid scheduling selector | WMI-2.1 |
| `WorkerTaskSelector#adapterNodeId` | worker-runtime candidate selector | Candidate source selector | invalid scheduling selector | WMI-2.1 |
| `WorkerCandidateSamplingContext#adapterNodeId` | runtime-api candidate sampling | Sampling context for memory/Redis registry | invalid candidate-source dimension | WMI-2.2 |
| `WorkerRegistry#acquireCandidates(groupId, adapterNodeId, ...)` | runtime-api candidate acquisition | Memory/Redis bounded candidate source | invalid candidate-source dimension | WMI-2.2 |
| Redis node candidate bucket keys | runtime Redis keyspace | `group:{groupId}:node:{adapterNodeId}:bucket:{bucket}:...` | invalid hot-path candidate source | WMI-2.2 |
| `WorkerMeta#adapterNodeId` | runtime slot metadata | Registry slot and relationship gate lookup | declaration/topology coupling residue | WMI-2.0/WMI-2.2/WMI-2.3 |
| `WorkerSchedulingView#adapterNodeId` | engine worker-selection evidence | Copied from candidate row into scheduling view | dispatch/trace residue | WMI-2.4 |
| `TaskDispatchBinding#adapterNodeId` | base dispatch handoff | Bound by `SimpleTaskDispatchBinder` | dispatch handoff leak | WMI-2.4 |
| `SimpleTaskDispatchBinder` adapter-node evidence | engine dispatch binder | Writes binding and evidence map | dispatch evidence leak | WMI-2.4 |
| `TraceEventLogger` adapter-node attrs | engine trace attrs | Writes adapter-node worker-selection evidence | trace residue | WMI-2.4 |
| trace sink `ExecutionEvent.NodeContext#adapterNodeId` | trace sink diagnostics | Node context field | allowed only for transport/topology diagnostic events | WMI-2.4 allowlist |
| `MassSdkApplication#normalizeWorkerRegistration` | embedded SDK registration | Requires `adapterNodeId` when `workerGroupId` exists | public registration leak | WMI-2.3 |
| Java SDK `WorkerSpec#adapterNodeId` | public Java SDK | Worker registration request | public registration leak | WMI-2.3 |
| Java SDK `WorkerRegistrationResult#adapterNodeId` | public Java SDK | Worker registration response | public registration leak | WMI-2.3 |
| Java SDK `PollingWorkerSession` / `WebSocketWorkerSession` | public Java SDK worker session | Requires adapter node and bootstraps topology | mixed worker session/topology bootstrap | WMI-2.3/WMI-5 |
| `ExternalWorkerRegisterApiRequest#adapterNodeId` | server API request | External worker registration | public registration leak | WMI-2.3 |
| server external registration response `adapterNodeId` | server API response | Echoes topology id in worker registration | public registration leak | WMI-2.3 |
| `WorkerSnapshot#adapterNodeId` | embedded SDK API snapshot | Public worker inspection | worker read surface leak | WMI-3 |
| `WorkerSnapshot#lastHeartbeat/createTime/updateTime` | embedded SDK API snapshot | Public worker inspection | raw timestamp default view leak | WMI-3/WMI-6 |
| server worker list `adapterNodeId/lastHeartbeat/updateTime` | server internal API | Worker list/default view | worker read surface leak | WMI-3 |
| frontend `WorkerListItem#adapterNodeId/lastHeartbeat/updateTime` | frontend worker view | Worker list/detail display | worker read surface leak | WMI-3/WMI-5 |
| catalog `adapterNodeId` from topology snapshots | server/frontend catalog | Adapter node and node-group binding topology views | valid topology/admin surface if not worker default view | Allow, WMI-5 docs |

## Production Caller Mapping

| Caller | Current Port | Current Use | Target Narrow Port |
| --- | --- | --- | --- |
| `EngineRuntimeKernelConfig#getWorkerResourceRuntime` | `WorkerResourceRuntime` | exposes broad worker resource aggregate to engine kernel | split into runtime evidence, query/control, topology admin only where needed |
| `EngineRuntimeKernel` | `WorkerResourceRuntime` | passes broad port to control/assignment startup paths | runtime evidence + identity/group lookup; remove broad aggregate injection |
| `WorkerControlService` | `WorkerResourceQueryRuntime` | worker read/control lookup | identity/group lookup or diagnostic snapshot |
| `MassSdkApplication` | `WorkerResourceRuntime` | registration, query, topology admin, snapshot assembly, reachability support | declaration mutation + identity lookup + topology admin + runtime evidence + diagnostics |
| `MassApplication` | `WorkerResourceRuntime` / `WorkerResourceRecord` | transport binding lookup and raw side-channel support | transport runtime registry or explicit topology diagnostic lookup |
| `EngineConfig` | `WorkerResourceRuntime` | stores broad runtime config port | split config accessors by owner port |
| `EngineRuntimeBridge` | `WorkerResourceRuntime` | engine assembly bridge | pass only owner ports required by engine |
| `RuntimeEventBusEngineBridge` | `WorkerResourceRuntime` | event bridge assembly | declaration/query only if needed; otherwise remove |
| `WorkerRuntimePresenceIngress` | `WorkerResourceRuntime` | heartbeat/resource refresh | presence ingress / heartbeat refresh port |
| server controllers through SDK app | SDK worker operations | worker registration/list/catalog | public worker API contracts and topology admin contracts separately |

## Allowed `adapterNodeId` Surfaces

Allowed after convergence:

- `AdapterNodeRecord`, `AdapterNodeSnapshot`, `AdapterNodeRegistration`
- `NodeGroupBindingRecord`, `NodeGroupBindingSnapshot`,
  `NodeGroupBindingRegistration`
- topology/admin server routes and SDK operations for adapter node and
  node-group binding management
- transport/topology diagnostics
- tests for the explicit topology/admin surfaces

Not allowed after convergence:

- worker declaration mutation or result
- normal Java SDK worker session startup
- task scheduling intent or resolved worker scheduling policy
- worker candidate selector or registry candidate acquisition
- worker scheduling view, task dispatch binding, assignment evidence, or
  worker-selection trace attrs
- default worker list, worker snapshot, or catalog worker capability view

## Raw Timestamp Allowlist

Allowed:

- command requests/results that need a deadline or observed time
- lease/deadline evidence and runtime expiry internals
- API-key expiry
- task audit/detail/result/trace surfaces
- explicit diagnostic/audit snapshots
- topology/admin diagnostics where timestamps are part of the admin record

Not allowed by default:

- public/default worker list
- public/default worker snapshot
- default catalog worker capability view
- identity/group lookup records
- declaration mutation inputs

## Test And Proof Residue

Known tests/proofs that preserve old adapter-node worker-selection behavior:

- `WorkerManagerTest` NodeGroupBinding drain/disable scheduling assertions
- `WorkerCandidateIndexTest` adapter-node selector helper and cases
- `DefaultSchedulingPlaneResolverTest` adapter-node policy assertions
- `WorkerRegistrationTestSupport` auto-registers adapter node and binds group
  before worker registration
- `EngineSchedulingCoreArchitectureGuardTest` contains historical guards and
  expected worker resource/runtime shape references
- runtime registry contract tests assert adapter-node group candidate and
  dispatch-disable helpers

These tests must be retargeted in the slice that removes the behavior they
currently prove. They must not remain as compatibility proof for removed
worker-selection semantics.
