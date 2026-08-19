# Transport Platform Event Catalog

Status: current human-readable projection of Transport-owned platform events.

This catalog answers which complete platform Event Names Transport itself
installs. It is not a registry, cross-module capability catalog, discovery
service, Server whitelist, WorkerGroup declaration, or scheduling input. The
immutable Handler map loaded by a running endpoint remains execution truth and
is observable through that endpoint's `events.snapshot` event.

Event Names have this shape:

```text
(platform|extension).(worker|adapter).<capability>
```

`platform` and `extension` identify the capability provider. They do not
identify the Command caller. `DeliveryCommand.src` remains invocation evidence
and is not part of Worker Handler lookup. Extension Hosts register a short
capability such as `string.md5`; TaskItem `eventCode`, WorkerGroup `eventCodes`,
Direct Call `messageType`, and Delivery Command `messageType` always carry the
full Event Name.

## Platform Worker Events

Java and Android Worker assemblies install these events before Host extension
Definitions. Their mechanism contract is owned by
[Worker Core](worker-core/README.md#message-path).

| Event Name | Input payload | Result payload | Normal use |
| --- | --- | --- | --- |
| `platform.worker.probe` | `null` | `{"reachable":true}` | Observe that the Worker Handler lane can execute |
| `platform.worker.properties.snapshot` | `null` | `{"properties":{...}}` | Read the current Host-provided properties |
| `platform.worker.events.snapshot` | `null` | `{"eventNames":[...]}` in lexical order | Observe the immutable Event Names loaded by this Worker process |

`probe` is not schedulability, idleness, Binding, or connectivity truth.
`properties.snapshot` does not update Worker resource or property-index truth.
`events.snapshot` includes itself and all Host extensions, but does not replace
WorkerGroup `eventCodes` or authorization.

## Platform Adapter Events

The Netty Adapter composition root installs one immutable map containing these
events. Their detailed semantics are owned by the
[Netty Adapter](netty-adapter/README.md#delivery-processes).

| Event Name | Input payload | Result payload | Effect |
| --- | --- | --- | --- |
| `platform.adapter.probe` | `null` | Adapter identity and reachability | Observation only |
| `platform.adapter.events.snapshot` | `null` | `{"eventNames":[...]}` in lexical order | Observes the immutable Adapter event map |
| `platform.adapter.worker-connections.snapshot` | `{"workerIds":["..."]}` | `{"stateByWorkerId":{"worker-1":"CONNECTED"}}` | Observes current Adapter-local route state |
| `platform.adapter.worker-connections.close-current` | `{"workerIds":["..."]}` | `{"outcomeByWorkerId":{...}}` | Atomically removes and physically closes each observed current Channel |
| `platform.adapter.worker-properties.snapshot` | `{"workerIds":["..."]}` | `{"propertiesByWorkerId":{"worker-1":{...}}}` | Observes Adapter-local cached Worker properties |

All Adapter events are callable through the ordinary `SYSTEM -> ADAPTER`
Direct Call path. The Kernel may call only
`platform.adapter.worker-connections.snapshot`, using `KERNEL -> ADAPTER`, for
the optional Worker Serviceability convergence policy. The resulting
`ADAPTER -> KERNEL` Report is ordinary Delivery evidence; Transport does not
interpret or write Worker score.

The Adapter also produces, but does not register as a callable Handler:

```text
platform.adapter.worker-connection.changed
  ADAPTER -> KERNEL
  payload={"workerId":"...","state":"CONNECTED|DISCONNECTED",
           "observedAtMillis":...}
  forward=worker-serviceability-evidence:v1

platform.adapter.worker-delivery.expired
  ADAPTER -> KERNEL
  payload={"workerId":"worker-1","observedAtMillis":...}
  forward=worker-serviceability-evidence:v1
```

One connection Report represents one exact Route availability transition. One
delivery-expired Report accompanies the ordinary 23002 TASK Report when a
TASK-to-Worker Command misses its Adapter delivery deadline. It does not claim
that the Channel is disconnected. Both carry no WorkerGroup, Binding,
Properties, generation, or score. Several Reports may be submitted together by
the existing Result Process. Queue pressure drops best-effort evidence without
closing the Worker Channel.
How Kernel weighs this evidence or maps it to a scheduling coordinate is
policy, not part of the Transport event contract.

Worker ID lists are unique, ordered, and bounded to `1..100`. Snapshot states
are `UNKNOWN` when this Adapter process has no verification evidence, including
while a first verification is pending, `CONNECTED` after verification with an
active current Channel, and
`DISCONNECTED` while retained verification evidence exists without an active
current Channel. Disconnected evidence is finite and becomes `UNKNOWN` after
its TTL or capacity eviction. These states do not imply Binding validity,
schedulability, writability, Worker idleness, or process liveness. Anonymous
physical Channels do not become Worker routes until identity succeeds. Closing
the current Channel does not unbind, disable, or pause the Worker; the existing
Client policy may reconnect it. Adapter restart clears this process-local
observation. There is no application heartbeat, so a silent half-open
connection converges only after the network stack, close, failure, or a write
detects it.

A properties entry reports Adapter-written `updatedAtMillis` and the complete
cached `properties`. Both fields are null when the Adapter has no visible
projection. Successive writes to a retained entry strictly increase the
millisecond value, but after route identity loss, capacity eviction, or Adapter
restart the next observation is a new baseline rather than a comparable global
version. The cache has no independent freshness window: retained route
verification evidence gates visibility, while a separate encoded-data budget
may evict properties without affecting the connection. This cache is neither
Worker resource truth nor evidence of Binding validity or schedulability.

Connection and properties are separate queries with no atomic join or common
version. `CONNECTED` does not prove properties exist or are recent, and cached
properties may remain while the route is `DISCONNECTED` but still verified. A
management caller that needs a combined view invokes both events and joins
their ordered workerId maps.

## Extension Boundary

Concrete `extension.worker.*` events are intentionally not enumerated here.
They belong to the Host or capability module that supplies their Definitions,
such as [Scenario Workers](../scenario_workers_jvm/README.md) and
[Android Capabilities](../xa-android/capabilities/README.md). Those Owners
define their input, output, semantics, and evolution. Transport only validates
the Definition shape, assembles the immutable Worker Handler map, and performs
exact full-name dispatch.

WorkerGroup `eventCodes` is a separate scheduling/resource declaration and may
lag the running process. `platform.worker.events.snapshot` is the observation
of extensions actually loaded by one Worker process. Adapter currently exposes
no public `extension.adapter.*` registration surface.

## Transport Protocol Messages That Are Not Handler Events

The long-connection protocol also uses the following `messageType` values.
They bypass the Adapter/Worker Event Definition maps:

| Message type | Direction | Transport meaning |
| --- | --- | --- |
| `worker.connection.identify` | `WORKER -> ADAPTER` Report | Declares the Server-issued `workerId` for a newly opened physical connection; payload is `null` |
| `worker.connection.close` | `ADAPTER -> WORKER` Command | Ends the current Worker run without producing a Delivery Report |

Their DTO and direction contracts are owned by the
[Worker Delivery Contract](worker-delivery-contract/README.md).

## Operations That Are Not Events

The following similarly named management surfaces are Owner operations or HTTP
use cases, not Transport events:

```text
Worker register and Endpoint bind
pause-scheduling and resume-scheduling
DIRECT_CALL /direct-calls
Adapter Command consume and Result append
Worker score lease, dirty, recovery, and Result Routing transitions
```

They must not be added to Handler maps merely to make this catalog appear
complete.

## Evolution Rules

- Compatible optional input or output fields may retain an Event Name.
- Incompatible input, output, semantics, or side effects require a new full
  name such as `.v2`.
- There are no aliases, dual lookup, wildcard or prefix routing, or fallback.
- Server passes Event Names through as opaque values and does not maintain this
  catalog as an execution whitelist.
- Update this projection in the same change that adds, removes, or renames a
  Transport-owned platform event.
