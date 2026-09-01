# Redis Keyspace

Status: active physical Redis ABI and data-isolation contract.

## Boundary

Every current key has this hierarchy:

```text
xa_mass:<scope>:<domain>:<owner coordinates...>
```

`xa_mass` is fixed and cannot be configured. `scope` is the data-isolation
boundary and must match exactly one of:

```text
profile_[a-z0-9_]+
test_[a-z0-9_]+
```

Spring Profile selects application assembly. Redis scope selects persistent
data. Redis URL and DB number are connection coordinates and do not distinguish
tests from profiles. All Java production Pacers use the same configured URL and
scope. A second active Kernel runtime for the same scope is forbidden.

`RedisKeyspace` validates the scope and produces only `xa_mass:<scope>`. Each
Redis owner appends its own domain and business coordinates. There is no
global business-key factory and no owner may construct another owner's key.
No key uses a Redis Cluster hash tag; Cluster support requires a separate ABI
and atomicity review.

## Current ABI

```text
Task descriptor       xa_mass:<scope>:task:<taskId>:descriptor
Task score            xa_mass:<scope>:task:score
Task Items            xa_mass:<scope>:task:<taskId>:items
TaskItem score        xa_mass:<scope>:task:<taskId>:item_score
Task results          xa_mass:<scope>:task:<taskId>:results
Task success results  xa_mass:<scope>:task:<taskId>:results:success

WorkerGroup catalog   xa_mass:<scope>:worker:groups
Worker metadata       xa_mass:<scope>:worker:metadata:<workerGroupId>
Worker properties     xa_mass:<scope>:worker:properties:<workerGroupId>
Worker ID owners      xa_mass:<scope>:worker:id_owners
Worker score          xa_mass:<scope>:worker:score:<workerGroupId>
Worker identity       xa_mass:<scope>:worker:identity:<workerGroupId>
Worker binding        xa_mass:<scope>:worker:binding:<bucket>
Probe requests        xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests
Adapter evidence      xa_mass:<scope>:worker:serviceability:evidence_results

Delivery commands     xa_mass:<scope>:delivery:commands:<endpointManagerId>
Result routing        xa_mass:<scope>:result:routing:<outcomeClass>
Candidate workers     xa_mass:<scope>:dispatch:candidate:<candidateId>:workers
```

The structures and owner semantics behind these keys remain defined by their
resource, scheduling, delivery, and runtime-shape documents.

## Profile And Proof Scopes

```text
ordinary Server       profile_default
scenario-workers      profile_scenario_workers
one proof run         test_<lane>_<unique run token>
```

Profile scopes are persistent and production code never scans or deletes an
entire scope. A proof owns one exact generated `test_*` scope. Cleanup may use
cursor `SCAN xa_mass:<exactScope>:*` followed by bounded `UNLINK` batches only
when the held scope and run token match. It must never use `KEYS`, `FLUSHDB`,
or `FLUSHALL`. Forced process termination may leave an isolated test scope;
that residue cannot overlap a profile scope.

This ABI is a clean cut. Current components do not read, migrate, dual-write,
or automatically delete earlier short-prefix keys.
