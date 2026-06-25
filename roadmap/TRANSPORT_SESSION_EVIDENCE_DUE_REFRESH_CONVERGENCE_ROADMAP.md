# Transport Session Evidence Due Refresh Convergence Roadmap

Status: proposed direction document.

## Current Code Observations

Current WebSocket endpoint evidence refresh is local-session-list driven:

- `WebSocketSessionEvidenceRefresher` runs as a managed adapter resource and
  periodically calls `WebSocketSessionRegistry.activeSessionSnapshots()`.
- `WebSocketSessionRegistry.activeSessionSnapshots()` builds a full list of
  active `SessionSnapshot(workerGroupId, workerId, sessionHandle)` values by
  scanning `sessionsByChannel`.
- `AdapterSessionEvidencePublisher.heartbeat(...)` refreshes transport
  endpoint lease evidence only. Current-session disconnect is the only
  worker-runtime-facing signal from this evidence lane, and it uses
  `CurrentSessionDisconnectSink`.
- `TransportEndpointLeaseStore.refreshEndpointLease(...)` is session-scoped:
  the heartbeat must match `workerId + deliveryBucketId + endpointDriverId +
  sessionHandle + endpointLeaseId`.
- Redis endpoint lease storage already maintains a deadline sorted set per
  delivery bucket; `RedisTransportEndpointLeaseStore.pruneExpired(...)` reads
  from that zset to remove expired leases.

This means the current refresher has the right narrow purpose, but the wrong
read shape: it exposes and scans all active WebSocket sessions when the actual
question is only "which endpoint leases are due for a refresh soon?"

## Owner Review

Session evidence refresh is evidence hygiene, not scheduling or adapter
lifecycle truth.

- Transport endpoint lease store owns lease deadlines and may expose bounded
  due hints.
- Concrete adapter session registry owns current active session truth and final
  protocol session lookup.
- A refresher may combine lease due hints with current adapter session evidence
  to publish heartbeat evidence.
- Worker runtime owns the derived reachability and selected-worker mailbox
  projection.
- Engine owns retry, reassignment, and task timeout if a worker never produces
  a result.

The due hint is not truth. It is a scheduling hint for evidence refresh.
The current adapter registry remains the truth for whether a worker currently
has an active local session.

## Boundary Decision

Replace full active-session snapshot refresh with lease-due-driven refresh:

```text
endpoint lease due index
  -> bounded due worker hints
  -> adapter registry confirms current worker session evidence
  -> AdapterSessionEvidencePublisher.heartbeat(...)
```

Refresh may be worker-id level:

- A stale due hint may trigger a heartbeat for the current session of the same
  worker if that worker is still active in the same delivery bucket.
- The due hint must not carry or require the old session handle.
- The adapter registry must provide the current `sessionHandle` used for the
  heartbeat.

Disconnect/release remains session-scoped:

- stale disconnects must not revoke newer sessions
- stale releases must not revoke newer endpoint leases
- delete/disconnect semantics must continue to compare the current
  session/lease token before removing evidence

## Target Shape

Transport lease due contract:

```java
public record TransportEndpointLeaseDueHint(
        AdapterSessionIdentity identity,
        long leaseExpireAtEpochMillis
) {}

public interface TransportEndpointLeaseDueView {
    List<TransportEndpointLeaseDueHint> dueEndpointLeases(
            String deliveryBucketId,
            long dueBeforeEpochMillis,
            int maxItems);
}
```

Adapter-local current evidence contract:

```java
public record AdapterSessionEvidenceSnapshot(
        AdapterSessionIdentity identity,
        String sessionHandle
) {}

public interface AdapterSessionEvidenceSource {
    Optional<AdapterSessionEvidenceSnapshot> currentEvidence(
            AdapterSessionIdentity identity);
}
```

Refresher shape:

```text
AdapterSessionEvidenceDueRefresher
  inputs:
    TransportEndpointLeaseDueView
    AdapterSessionEvidenceSource
    AdapterSessionEvidencePublisher
    active delivery-bucket source or configured bucket scope
  loop:
    dueBefore = now + refreshWindow
    dueEndpointLeases(bucket, dueBefore, maxItems)
    for each due hint:
      currentEvidence(hint.identity)
      if present:
        heartbeat(current identity.workerId, current identity.deliveryBucketId, current sessionHandle)
      else:
        no-op; existing expiry/prune/release handles stale evidence
```

The class name can be adjusted during implementation, but the boundary must
stay: due hint from transport lease store, current active session evidence from
adapter registry, heartbeat through existing publisher.

## Semantics

### Refresh

Refresh is worker-id and delivery-bucket scoped:

```text
due hint(AdapterSessionIdentity(deliveryBucketId, workerId))
  + current active adapter session for same identity
  -> heartbeat current session
```

If a worker reconnects under the same delivery bucket before the old due hint
is processed, refreshing the current session is acceptable. Scheduling and
delivery identity are `workerId`; the session handle is only the current
adapter-local freshness token.

If a worker reconnects under a different delivery bucket, the old bucket's due
hint must not refresh the new bucket. The new connection should already have
claimed its own endpoint lease.

### Disconnect And Delete

Disconnect remains session-scoped:

```text
disconnect(workerId, deliveryBucketId, sessionHandle)
  -> release only if this is still the stored session/lease
```

This protects against async close events from an old connection arriving after
a new session has already connected.

### Expiry

When no current adapter session exists for a due hint, the refresher does not
emit heartbeat. The existing finite lease expiry and prune path remains the
self-cleaning mechanism.

## Non-Goals

- Do not make endpoint lease store a worker lifecycle owner.
- Do not make the refresher an adapter health monitor, reconnect loop,
  failover owner, takeover mechanism, or scheduling strategy.
- Do not expose a general list-all session API for diagnostics or operator
  reads.
- Do not add worker attribute/capability refresh to adapter session heartbeat.
- Do not change worker capability truth or scheduling policy.
- Do not remove session-scoped disconnect/release guards.
- Do not introduce a new lifecycle state machine around adapter sessions.

## Do Not Start With

Do not start by renaming `WebSocketSessionEvidenceRefresher` or deleting
`activeSessionSnapshots()`.

First add the due-hint contract and prove in-memory/Redis endpoint lease stores
can return bounded due hints. Then retarget the refresher. Deleting the old
snapshot API before the due path exists will force either a break-now/fix-later
state or another broad registry read method.

## SEDR-0 - Inventory Current Evidence Refresh Callers

Scope:

- classify all callers of `activeSessionSnapshots()`
- classify all endpoint lease deadline reads/writes
- confirm whether only WebSocket currently needs managed session evidence
  refresh
- confirm whether socket/polling already refresh via explicit heartbeat calls
  and should not be forced into this path

Acceptance:

- inventory in this roadmap or a sibling inventory records production and test
  callers separately
- `activeSessionSnapshots()` is confirmed as WebSocket-only before replacing it
- current Redis deadline zset shape and in-memory lease storage shape are
  recorded
- no socket or polling behavior is changed in this slice

## SEDR-1 - Add Bounded Endpoint Lease Due Hints

Scope:

- add a transport endpoint lease due read contract that returns
  `AdapterSessionIdentity + leaseExpireAtEpochMillis`
- implement it for Redis using the existing deadline sorted set with bounded
  range reads
- implement it for in-memory without exposing session scans as a production
  API
- keep existing claim/refresh/release semantics unchanged

Acceptance:

- due hint contains no `sessionHandle`, `endpointLeaseId`, adapter lifecycle
  status, worker attributes, or scheduling fields
- Redis due read is bounded by `maxItems`
- due read does not delete or refresh leases
- existing `pruneExpired(...)`, `refreshEndpointLease(...)`, and
  `releaseEndpointLease(...)` behavior remains unchanged
- tests cover empty due, bounded due, not-yet-due, and stale-expired behavior

## SEDR-2 - Replace WebSocket Full Snapshot Refresh

Scope:

- introduce adapter-local current evidence source:
  `currentEvidence(AdapterSessionIdentity identity)`
- change WebSocket registry to expose current evidence lookup instead of
  list-all active session snapshots
- replace `WebSocketSessionEvidenceRefresher` with a due-hint-driven refresher
  or retarget it to the shared due-hint implementation
- preserve connect/disconnect publishing in `WebSocketSessionRegistry`

Acceptance:

- WebSocket refresh no longer calls `activeSessionSnapshots()`
- WebSocket registry does not expose list-all session snapshots as a production
  mainline API
- due hint for an active worker refreshes the current session's transport
  endpoint lease evidence only; it does not refresh worker-runtime heartbeat
  evidence or request scheduling recheck
- due hint for a disconnected worker emits no heartbeat
- stale disconnect after reconnect still cannot remove the newer endpoint lease
- worker reconnect under the same delivery bucket may be refreshed by a stale
  due hint using the current session handle
- worker reconnect under a different delivery bucket does not refresh the old
  bucket's lease from the old due hint

## SEDR-3 - Guard Owner Boundaries And Docs

Scope:

- update `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  to state due-hint refresh semantics
- update `doc/PROOF_REGISTRY.md` endpoint/session evidence proof row
- add architecture guard against WebSocket production code exposing
  `activeSessionSnapshots()` or list-all session APIs for refresh
- add guard that due hints do not carry session handles or worker attributes

Acceptance:

- owner docs distinguish refresh from disconnect/delete semantics
- guards prevent list-all active session refresh from returning
- guards preserve session-scoped release/disconnect behavior
- roadmap status is updated to reflect landed slices

## Deferred Decision - Adapter-Scoped Due Index

Current Redis endpoint lease zsets are keyed by delivery bucket. If a single
adapter host serves very many delivery buckets, due refresh may still need to
iterate active bucket scopes.

Do not add an adapter-scoped due index in the first slice unless current
profiling or production caller evidence proves bucket iteration is the real
risk. If needed later, define a separate contract such as:

```text
dueEndpointLeasesForAdapter(adapterEvidenceOwner, dueBefore, maxItems)
```

That index must still return due hints only, not become adapter health,
worker lifecycle, or scheduling truth.

## Verification Candidates

Correct test names after SEDR-0 inventory if they drift.

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter -am test "-Dtest=TransportEndpointLeaseStoreContractTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/websocket-adapter -am test "-Dtest=WebSocketSessionEvidenceRefresherTest,WebSocketSessionRegistryTest,DispatcherInboundHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter -am -DskipTests compile
```

Strict completion proof should include a target-module run where the named new
tests exist and execute, not only a reactor run with
`-Dsurefire.failIfNoSpecifiedTests=false`.

## Roadmap Completion Criteria

- endpoint lease stores expose bounded due hints without session handles
- WebSocket evidence refresh is due-hint driven and does not full-scan active
  sessions
- adapter registry only exposes current evidence lookup for a specific
  `AdapterSessionIdentity(deliveryBucketId, workerId)`
- refresh may use current session facts, but disconnect/release remains
  session-scoped
- current owner docs and proof registry match the implemented behavior
- architecture guard prevents list-all refresh and session-handle due hints
- residue scan finds no production use of `activeSessionSnapshots()`
