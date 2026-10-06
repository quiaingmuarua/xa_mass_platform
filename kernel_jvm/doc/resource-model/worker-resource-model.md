# Worker Resource Model

Status: active Java Kernel Worker registration and persistent Binding contract.

## Owner Boundary

`WorkerResourceCatalog` owns the Group directory, persistent Worker Binding,
registration and bounded resource reads. `WorkerScoreCore` owns registered
members and their scheduling coordinates. Server owns external identity
resolution, the configured Endpoint directory and Prepare orchestration.
Adapter owns current Channels and verified Routes; Server observes actual
Polling requests. Kernel interprets that network evidence into serviceability.

`WorkerDescriptor(workerId, workerGroupId, endpointManagerId)` is a Binding read
snapshot. Worker ID identifies an execution slot; Group is its unique resource
ownership and Endpoint is its current delivery address. Endpoint migration is
not implemented. A changed default cannot overwrite an existing Binding, and
a connection to a different Adapter is rejected. The address is not a permanent
identity constraint.

Kernel does not store or interpret Worker/Platform Properties or Matching queries.
Prepare does not write Matching facts. An admitted Adapter Properties
observation may create the first Matching facts later. Identity and Any need no
Facts; Country, Messaging and Proof qualify offered Facts, while Phone functions
use an independent property HASH. Their resource and qualification requirements
belong to the [Matching Owner](../../../worker_matching_jvm/README.md).
Every candidate still requires eligible Worker Score coordinates before assignment.
`WorkerGroup.eventCodes` is create-only directory metadata, not proof of loaded handlers.

## Registration

```java
workerCatalog.registerWorkers(workerGroupId, workerIds, defaultEndpointManagerId);
```

This is the only Worker registration entry: 1..100 unique nonempty IDs for one
Group and a nonempty default Endpoint. Invalid batch shape fails before Redis.
The returned Map preserves ID order. Each `WorkerRegistrationResult` carries
`status`, actual `endpointManagerId` on success, and optional `reason`.

```text
one bounded Binding Lua:
  Group must exist
  absent Binding -> create Group + default Endpoint
  existing same Group -> retain and return actual Endpoint
  other Group -> CONFLICT; malformed Binding -> INVALID
one Score Owner batch for accepted IDs:
  initialize absent registered members at the fixed cold coordinate using NX
  retain all existing scores unchanged
```

The Binding and Score stages commit independently. Infrastructure failure
throws and may leave any accepted subset partially complete. Retry fills
missing stages without rollback, completion markers, confirmation reads or
background repair. Score membership means Kernel registration exists; Binding
alone does not prove membership. Neither means online, idle or deliverable.

| Status | Meaning |
| --- | --- |
| `OK` | Binding or Score membership was created |
| `NOOP` | Binding and membership already exist; actual Endpoint is returned |
| `CONFLICT` | Worker belongs to another Group, or a Group declaration differs |
| `INVALID` | Stored Binding or Group declaration is invalid |
| `NOT_FOUND` | Worker registration requires an existing Group |

Group registration retains `RegistrationResult(status, reason)` and produces
only the first four statuses. Infrastructure errors have no registration
status; Server maps them to its existing unavailable response.

## Reads And Cost

`getWorkerDescriptors(workerIds)` and `getWorkerDescriptorsAsync(workerIds)`
read caller-bounded IDs across Groups with one HMGET on the same Binding HASH,
including a 1000-candidate Dispatch call. The 100-ID registration guard belongs
only to registration and is not repeated by address reads.
Missing or corrupt entries map to null. Empty reads return an empty Map without
Redis. Address reads, connection verification and Direct Call do not read Score
to check registration completeness. Callers check the requested Group against
the returned Group where required.

`sampleWorkerDescriptors(group, limit)` accepts `1..1000` and asks Score Owner
to sample registered members once, then reads their Bindings in batches of at
most 100 (at most ten HMGET calls). Missing or wrong-Group
Bindings map to null; address-only rows do not appear in the sample. Group
directory reads retain their bounded HMGET and HRANDFIELD behavior.

Normal Catalog registration is two Redis client commands, regardless of batch
size. Normal Prepare is four: Group HMGET, Identity Lua, Binding Lua, Score Lua.
One Binding lookup is one HMGET. These are command budgets, not measured
throughput or latency guarantees.

## Network Activation

Every Worker starts cold, including Polling. The Score Owner uses
RECOVERY_RECHECK / timeSlot=1 / mark=0 (score -1), outside
ordinary allocation, stale-HOT and recovery-recheck scan ranges.

All Pacer presets consume network evidence. Verified Adapter connections and
valid Server Polling observations request HOT through `WorkerServiceabilityEvents`.
Pacer's network Policy reads Binding once, verifies the producing Endpoint and
obtains Group before calling Matching's best-effort timestamp prefilter. The
three Kernel events receive only explicit Group and Worker-to-observation-time
Maps, then call Score Owner. Kernel neither stores network evidence history nor
depends on Matching. Filtering and Score conversion are separate commits; a
saved timestamp does not mean conversion succeeded. The mechanism does not create
missing Workers or shorten leases/PAUSE; existing time-slot, polarity and mark
rules remain in Score Owner.

Activation is best-effort. Lost evidence leaves a Worker cold until new valid
evidence arrives. There is no activation ACK, replay or cold-member scan.
Periodic probes and the HOT eligibility floor remain preset-controlled;
DEFAULT installs RECOVERY-only rechecks without HOT probes or a floor. Execution Result events and opaque lease references
retain their separate responsibilities.

Storage and rebuild procedure: [Worker Redis shape](../runtime-redis/worker-runtime-redis-shape.md).
Policy: [Worker Serviceability](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md).
