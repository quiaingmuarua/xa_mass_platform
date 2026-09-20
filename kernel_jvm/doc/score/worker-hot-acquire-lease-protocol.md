# Worker HOT_ACQUIRE Lease Protocol

Status: active Java Kernel candidate-generation and execution-lease contract.

[Worker Score](worker-score-band-scheduling.md) owns encoding and atomic rules.
This protocol owns opaque fence handoff; it adds no reservation store, attempt
registry or Matching acquisition callback.

## One Worker Slot

One WorkerId is one scheduler-visible execution slot. Physical parallel capacity
uses separate WorkerIds. One execution lease correlates one TaskItem and one
DeliveryCommand; business batching belongs inside its payload. Publication does
not permit early release to simulate slot reuse.

## Candidate Generation Before Matching

**Pool admission changes the candidate mark without advancing Worker time. Only
TaskItem assignment creates a future execution lease.**

```text
Main Group roots and NORMAL Task supply declarations
  -> Refill: recycle bounded old candidate generations, even without deficits
  -> if supply needed: observe due HOT mark=0 head
  -> exact candidateize HOT 0/T to HOT 1/T
  -> Matching qualifies supplied identities and caches the returned fence
  -> Pool take or direct identity/property query
  -> strict observed acquisition or atomic current identity acquisition
  -> HOT 0/executionDeadline -> exact Item claim -> Command -> ResultContext
```

Candidateize accepts only exact due ordinary HOT. Its T stays unchanged and may
be old; it is a generation coordinate, not an admission timestamp. Pacer attempts
at most 100 candidates per Group and 1000 per round. A separate equal-sized budget
recycles old mark=1 into HOT 0/RedisNow. Recycling advances generation to invalidate
old fences and prevent the same old head from immediately recycling again.
Both reads honor Assignment's optional startup floor, Group rotation and raw-row
budgets. No Worker cursor or new thread exists.

Default candidate age and Pool local TTL are each 60 seconds, owned separately.
Pool TTL starts at actual admission, not from T. Repeated supply of the same
Worker/fence neither counts again nor extends TTL. A requalified new fence replaces
the old entry/views even at full storage capacity; a changed fence which no longer
matches removes the old entry. Stale local selection references cannot consume a
later replacement. Expired entries cannot be taken; a previously taken candidate
is governed only by final exact and due checks, not by the local TTL.

One generation may populate several Pools. Each Pool independently qualifies and
expires it. Catalog rotates Pool attempts and counts actual accepted entries
against its 100-entry call budget; it does not promise all Pools fill in one call.
Matching reads no Worker Score and cannot renew, acquire, decode or substitute a
Worker. It receives Map<workerId, opaqueScore>, with no Worker lease deadline.

No match, capacity refusal, qualification failure, process loss or an ambiguous
return leaves a compensation obligation. The committed mark=1 eventually qualifies
for normal bounded recycling. This depends on Main continuing to supply its Group;
no global discovery or unlimited fairness guarantee is added.

## Execution Before Claim

At Redis execution time both operations require strictly past HOT, either mark.
Current/future HOT and RECOVERY are ineligible. The requested deadline itself must
be strictly future. Acquisition changes time and writes mark=0 in one atomic step.
It cannot take over an already active execution lease or PAUSE.

Pool functions return their nonzero candidate fence in WorkerCandidate. Pacer
returns it unchanged to acquireObservedHotScoreLeases. Identity/Phone functions
return expectedScore=0 hints, consumed by Pacer and sent as IDs to the separate
acquireCurrentHotScoreLeases operation. Kernel has no zero wildcard. Both operations
sample TIME once per bounded Lua, without pre-reading or conflict retry.
A strict candidate failure never falls back to current acquisition or another take.

Shared Pool copies and Direct assignment compete for one execution slot. Only
TRANSITIONED with a returned fence authorizes Item claim; merely reading a future
coordinate, or receiving a rejection that echoes one, confers no authority.
The returned execution fence enters ResultContext. Old Pool copies cannot commit
execution or release that new hold. A later independent failure does not roll back
an earlier acquisition; each nonempty partition and each split batch remains its
own atomic call. If Item claim or Command publication fails, execution expiry
restores scheduling eligibility without compensation.

## Properties And Network Evidence

Properties writes remain separate commits from Score invalidation. After APPLIED
Worker or Platform facts, Server best-effort calls advancePastScoreTimesToNow once
per Group. Past HOT atomically becomes mark=0 at Redis current time. Past non-cold
RECOVERY advances time while retaining its mark; polarity is preserved in both
cases. Cold RECOVERY, current, future and PAUSE coordinates are NOOP.
The cold exception keeps initial Properties from invalidating a pending CONNECTED
event before activation. An UNCHANGED facts retry does not replay
invalidation; failed invalidation keeps the successful facts result and diagnostic.

If invalidation wins first, an old Pool fence fails exact acquisition. If execution
acquisition wins first, the future hold and result correlation survive Properties.
Same-slot repeated invalidation cannot recreate an acquirable old fence, because
candidateize and assignment require strictly past time. Invalidation advances
generation as it clears HOT mark, so requalification cannot restore the old Pool
fence. After the current slot passes, normal Refill can observe the ordinary HOT
without waiting for age-based recycling; Group roots, deficits and budgets still
govern Pool admission. Direct assignment can independently acquire a due Worker.

Network evidence corrects polarity without changing mark. Current/future slots
accept validated evidence and retain time. Past coordinates retain their evidence
freshness check. CONNECTED only promotes coordinates below startup floor to that
floor, provided evidence reaches it; DISCONNECTED never advances time. Thus future
execution/recheck coordinates and PAUSE survive reconnect. Network evidence is not
a reliable ordered log, and execution evidence never infers connection polarity.

## Release And Result Association

Only the original signed fence authorizes ordinary release. Completed HOT release
additionally accepts its precise negative counterpart, allowing a disconnect after
execution acquisition without inventing another lease. Release retains mark and
samples time as specified by the [Score primitives](worker-score-band-scheduling.md#score-primitives).
No broader coordinate match or late result may release a newer generation/hold.

Execution events compose existing owners. Retryable failure releases only the
correlated Worker hold; SUCCESS stores Result before requesting TaskItem finality.
TaskItem movement, Result content and Worker release remain independent commits.
No missing context is reconstructed, and expiry cannot repair a lost Result or
provide replay/ACK guarantees. See the [Result Owner](../runtime-redis/task-result-runtime-redis-shape.md).

## Pause And Recovery

Pause atomically writes MAX time and mark=0 while preserving polarity. Repeated
pause is unchanged. Resume reads once and exact-releases the original paused
coordinate; deletion or change produces conflict without retry.

Serviceability retains HOT-first, then Recovery only after an empty raw HOT result.
Successful exact deferral writes RECOVERY mark=0 at Redis now + delay before Probe
offer. The 15-second delay is next eligibility, not guaranteed execution time.
Offer failure never rolls it back. Candidate recycling and Probe checks may compete
through exact CAS; no priority coordination is added. Excluded Endpoints retain the
existing two-step HOT toggle then cold park, without Probe or resource deletion.

## Failure Boundaries

| Stage | Outcome |
| --- | --- |
| Candidate CAS lost | No offer to Matching |
| Qualification/capacity/return lost | Committed generation waits for bounded recycling |
| Pool TTL expires | Cannot take inventory; no Score rewrite |
| Strict or current execution rejected | No Item claim, fallback, replacement take or refresh |
| Claim/publication interrupted | No compensation; independent expiry remains |
| Delivery lost | UNKNOWN is not trusted pre-execution rejection |
| Result missing, late or duplicate | Only the exact applicable fence can change Worker Score |

The [delivery boundary](../../../doc/kernel/worker-delivery-dispatch.md) and
[Serviceability policy](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
retain their separate authorities. Pool presence and HELD_HOT do not prove network
availability or that a particular caller successfully acquired execution.

## Deployment And Observation

Scores remain opaque to Pacer, Matching, Server and Transport. Scheduling state
classification, pause and resume remain Kernel operations; HTTP fields, state
names and error codes do not change.

The high-mark encoding requires a new Redis scope. No compatibility reader,
migration, version marker or automatic cleanup is provided. Local Pool stock is
process-local and discarded on restart; bounded recycling recovers retained
candidate generations in Groups supplied by Main. No allocator or rule-change
sweep is part of this mechanism.
