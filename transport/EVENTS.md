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
Control Call `messageType`, and Delivery Command `messageType` always carry the
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
| `platform.adapter.worker-connections.snapshot` | `{"workerIds":["..."]}` | `{"connectedByWorkerId":{...}}` | Observes current Adapter-local active Channels |
| `platform.adapter.worker-connections.close-current` | `{"workerIds":["..."]}` | `{"outcomeByWorkerId":{...}}` | Atomically removes and physically closes each observed current Channel |

Worker ID lists are unique, ordered, and bounded to `1..100`. A connected
result means only that route verification completed and the Adapter currently
has an active Channel. Closing the current Channel does not unbind, disable, or
pause the Worker; the existing Client policy may reconnect it.

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
| `worker.connection.identify` | `WORKER -> ADAPTER` Report | Declares the Worker identity for a newly opened physical connection |
| `worker.connection.close` | `ADAPTER -> WORKER` Command | Ends the current Worker run without producing a Delivery Report |

Their DTO and direction contracts are owned by the
[Worker Delivery Contract](worker-delivery-contract/README.md).

## Operations That Are Not Events

The following similarly named management surfaces are Owner operations or HTTP
use cases, not Transport events:

```text
Worker register and Endpoint bind
pause-scheduling and resume-scheduling
CONTROL_ONLY controls:call
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
