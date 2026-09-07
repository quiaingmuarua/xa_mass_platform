# Worker Runtime Redis Shape

Status: active Worker metadata, matching facts and Server identity Redis ABI.

## Namespace And Owners

Every key is rooted at the validated `xa_mass:<scope>` base. Worker data is
split by owner:

```text
Kernel WorkerResourceCatalog  WorkerGroup catalog and minimal Worker metadata
Kernel WorkerScoreCore        scheduling score
WorkerMatchingCatalog         Worker/Platform facts and Candidate Rules
Server Identity/Binding       external identity and endpoint address
```

No owner reads another owner's Redis key directly.

## Kernel Worker Resources

```text
xa_mass:<scope>:worker:groups
  HASH field = workerGroupId
  value       = canonical WorkerGroupDescriptor JSON

xa_mass:<scope>:worker:metadata:<workerGroupId>
  HASH field = workerId
  value       = {
    "endpointManagerId": "...",
    "workerGroupId": "...",
    "workerId": "..."
  }

xa_mass:<scope>:worker:id_owners
  HASH field = workerId
  value       = workerGroupId
```

WorkerGroup registration is create-only: equal declarations are idempotent and
different declarations conflict. The ID-owner Hash is an immutable
cross-Group fence for bounded explicit-ID reads; it is not a global discovery
index.

Worker upsert validates Group existence and identity ownership, then creates
immutable minimal metadata. A different existing endpoint coordinate conflicts. The exact metadata field set rejects
legacy Properties fields.

## Matching Facts

```text
xa_mass:<scope>:matching:worker:facts:<workerGroupId>
  HASH field = workerId
  value       = complete canonical Worker Properties JSON

xa_mass:<scope>:matching:worker:platform-properties:<workerGroupId>
  HASH field = workerId
  value       = complete canonical Platform Properties JSON

xa_mass:<scope>:matching:candidate:rules
  HASH field = candidateId
  value       = {"workerGroupId":"...","allocationRule":{...}}
```

Worker Prepare replaces the complete Worker-owned facts row and preserves the
separate Platform row. Platform patch requires an existing Worker facts row,
applies nullable field updates and uses bounded compare-and-set retries.

Candidate Rules are create-only. Equal content is unchanged; different content
conflicts. Orphan facts or Rules are inert. The resident Matching Runtime reads
Candidate Rules only for a bounded PRECOMPUTED Demand containing Kernel-held
Worker IDs.

ON_DEMAND uses no Matching key. Kernel validates the finite `workerSelector`,
stores only normalized explicit Worker IDs or an empty ANY target with the
TaskItem, and does not ask Matching to scan Group facts during dispatch.

## Worker Score

```text
xa_mass:<scope>:worker:score:<workerGroupId>
  ZSET member = workerId
  score       = opaque Worker score encoding
```

Only `WorkerScoreCore` interprets or mutates this score. Worker upsert
initializes HOT_ACQUIRE only when the score is absent and preserves every
existing score byte-for-byte. Matching facts and Rules never read or write the
score.

## Server Identity And Binding

```text
xa_mass:<scope>:worker:identity:<workerGroupId>
  HASH field = typed registration-key output
  value       = server-issued workerId

xa_mass:<scope>:worker:binding:<00..ff>
  HASH field = workerId
  value       = endpointManagerId
```

Identity registration establishes long-lived external identity. The Worker
kind selects only the Server-owned registration-key algorithm and never enters
the Redis key address. Binding establishes the persistent delivery route.
Neither is connection truth or a Kernel scheduling decision.

## Composition And Views

[Server Prepare](../../../server_jvm/README.md#workergroup-and-worker-preparation)
owns the ordered cross-owner use case. The
[Matching catalog](../../../worker_matching_jvm/README.md#persistent-catalog)
owns fact/Rule semantics, and the
[Worker resource model](../resource-model/worker-resource-model.md) owns Kernel
metadata. Runtime views join independent Owner reads; they are neither new
truth records nor atomic snapshots across these keys.

## Guardrails

- Do not restore `worker:properties` or Properties inside Kernel metadata.
- Do not place PRECOMPUTED Candidate Rules in Kernel Task JSON.
- Do not add ON_DEMAND Item Rule storage; only normalized Worker IDs belong to
  the Kernel TaskItem.
- Do not use identity ownership for Worker discovery.
- Do not let Matching interpret Score or let Kernel interpret Properties.
- Do not add dual reads or migration aliases for the retired layout.
- Do not infer Adapter connectivity from Binding, facts or score.
