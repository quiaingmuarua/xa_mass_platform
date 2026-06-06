# Platform Scheduling Plane Public Vocabulary Checkpoint

Status: current classification for Scheduling Plane stabilization.

This checkpoint classifies ambiguous Scheduling Plane vocabulary before any
successor policy feature roadmap. It does not add new public fields.

## Classification

| Vocabulary | Current classification | Current effect | Successor rule |
| --- | --- | --- | --- |
| `profile` | Current public execution vocabulary, not an effective Scheduling Plane selector. | Preserved on task execution/API surfaces for caller ergonomics and compatibility with the execution spec shape. Current engine scheduling behavior is not selected by `profile`. | Keep public, but do not treat as policy selection unless a successor decision names concrete variants and caller-visible cost. |
| `foreground` | Current public execution vocabulary and preset input. | Feeds resolved `WorkerResourceMode`: `EXCLUSIVE` keeps the long-lived exclusive worker lock path; `CAPACITY` relies on process-local capacity reservation for stateless/background sharing. | Keep as explicit execution-mode vocabulary. Do not reframe it as a general worker policy product without successor proof. |
| `maxRuntimeSeconds` | Current public lifecycle vocabulary, not dispatch policy truth. | Configures non-terminal task runtime limit handling. It does not select worker policy, candidate universe, ranking, or dispatch budget. | Keep as lifecycle/runtime limit vocabulary. Do not merge it into scheduling policy selection. |
| `targetWorkerId` | Internal task shared-config convention and resolved worker-side input. | Narrows candidates inside an explicit WorkerGroup selector. It cannot bypass group capability, reachability, dispatch gate, load/capacity, lock, admission, or rule checks. | Keep internal until a public SDK/API caller and proof justify exposing it. |
| `adapterNodeId` | Worker declaration/node identity and internal resolved worker-side input. | Narrows candidates to a selected adapter node when combined with group selection. It is also visible in worker registration/catalog surfaces as node identity. | Do not promote adapter-node stickiness into public task policy without deciding whether this is an operational override or a stable policy contract. |
| Route attributes | Current public shared-config routing hints and internal resolved worker-side input. | Approved route attributes derive route bucket keys for Stage-1 candidate narrowing and matching evidence. Unknown or unapproved keys are not a policy product. | Keep as constrained routing hints. Any new approved key needs owner review and proof that it is not item payload policy. |
| Target attributes | Current public-contract builder vocabulary and internal rule/prefilter input. | Narrows candidates by worker attributes when present. This is still task intent and worker-selection evidence, not a user-configurable policy family. | Keep constrained. Do not expand into arbitrary policy DSL without successor proof and rule-context review. |

## Current Public Surface

- `TaskExecutionSpec` exposes `profile`, `workloadClass`, `batchSize`,
  `maxRuntimeSeconds`, `defaultMaxRetryCount`, and `foreground`.
- `TaskSharedConfigKeys` exposes `routingCode`, `routeAttributes`,
  `workerGroupId`, `workerGroupIds`, and `targetWorkerAttributes`.
- Server task API docs expose `executionSpec` and `sharedConfig.routeAttributes`
  as current task shell inputs. They do not expose `SchedulingPolicyCatalog`,
  `ProjectSchedulingBinding`, or policy ids as caller behavior.
- `adapterNodeId` is visible on worker registration/catalog surfaces. That
  visibility is worker resource identity, not task policy configuration.

## Successor Gate

No additional public Scheduling Plane vocabulary should be added until a
successor decision names:

- the caller that can select or configure it,
- the owner that stores it,
- the runtime owner that consumes the resolved value,
- at least two concrete variants with different caller-visible cost,
- the proof that current computed defaults are insufficient.
