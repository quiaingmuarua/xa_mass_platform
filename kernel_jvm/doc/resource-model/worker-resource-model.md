# Worker Resource Model

Status: active Java Kernel Worker scheduling metadata contract.

## Owner Boundary

Kernel stores only the identity and delivery coordinates needed after a Worker
has already been selected:

```text
WorkerDescriptor
  workerId
  workerGroupId
  endpointManagerId
```

Kernel does not store or interpret Worker or Platform Properties. Canonical
matching facts live in `worker_matching_jvm` and are joined by Server only for
public runtime views.

```text
Server Worker Prepare
  -> resolve or register workerId
  -> persist endpoint binding
  -> upsert complete Worker facts in WorkerMatchingCatalog
  -> upsert minimal Worker scheduling metadata in Kernel
  -> initialize or retain Worker score
```

The write sequence is intentionally not transactional across owners. A facts
record without a Kernel Worker is an inert orphan: Matching can return evidence
only after Kernel publishes a bounded demand, and Kernel still checks current
score and takes an exact lease before dispatch.

## Identity

One `workerId` is one scheduler-visible execution slot and belongs to exactly
one `workerGroupId`. `endpointManagerId` is the delivery address selected by
Server binding; connection state remains Adapter evidence rather than Worker
resource truth.

`WorkerGroup.eventCodes` are create-only catalog metadata. They do not prove
that a live Worker loaded a Handler and they are not consulted by Kernel
matching or dispatch.

## Commands

```java
WorkerRuntime.upsertWorker(new WorkerDeclaration(
        workerId,
        workerGroupId,
        endpointManagerId
));
```

An equivalent declaration is idempotent. A declaration that changes the fixed
WorkerGroup conflicts. Updating the endpoint manager changes only the delivery
coordinate; it does not change Properties or matching evidence.

Platform Properties patching is not a Kernel command. Server sends that
operation to `WorkerMatchingCatalog`, where Worker-reported and
Platform-reported namespaces are owned and combined for matching.

## Reads

Kernel reads expose only the minimal descriptor. Public Worker runtime views
are Server projections:

```text
Kernel Worker descriptor
  + WorkerMatchingCatalog facts
  + Adapter network and observation projections
  + Kernel score projection
  = public Runtime Worker view
```

No component may infer Properties from score or connection state.

## Redis Shape

The Kernel Worker catalog stores exact minimal metadata. Legacy
`workerProperties` and `platformProperties` fields are rejected rather than
silently retained. Matching facts use the independent keyspace documented by
[`worker_matching_jvm`](../../../worker_matching_jvm/README.md).

## Non-Owners

Kernel Worker resource code does not own:

- Worker or Platform Properties;
- allocation rules or constraint operators;
- connection truth or route verification;
- candidate enumeration;
- Worker selection policy, lease cadence, or result interpretation.
