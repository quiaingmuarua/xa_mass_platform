# Task Evidence And Result Redis Shape

Status: active Java Kernel Task evidence and Result storage contract.

## Evidence Handoff

```text
xa_mass:<scope>:result:routing:success
xa_mass:<scope>:result:routing:failure
xa_mass:<scope>:result:routing:observation
```

Each key is a Redis LIST of deterministic `DeliveryReport` JSON.
`TaskEvidenceRuntime.appendTaskEvidence(type, reports)` performs one bounded
`RPUSH`; `consumeTaskEvidence(type, limit)` uses Redis 7 `LPOP key count`.
`EXECUTION_SUCCESS`, `EXECUTION_FAILURE` and `OUTCOME_OBSERVATION` select the
three keys respectively. Corrupt members are consumed and skipped. Appends to
different lanes are independent, and concurrent batch completion need not follow
FIFO consumption order.

The Runtime validates the type, bounded input and Report encoding only. Server
owns producer and exact event admission; Result Policy owns JSON and `forward`
interpretation. `diagnosticCode` never selects a lane. There is no pending/ack,
replay, repair scan, producer partition or cross-key transaction.

## TaskItem Result Projection

`TaskRuntime` owns one projection:

```text
xa_mass:<scope>:task:<taskId>:results
  HASH messageId -> encoded Result

success:
  {"code":"200","observedAtMillis":1001,"opaqueResultPayload":"reply","tag":9}
failure:
  {"code":"failed","opaqueResultPayload":"TaskItem ended without a successful result"}
```

Success writes accept `messageId -> TaskItemSuccessResult(tag, observedAtMillis,
opaqueResultPayload)`, at most 100 entries. Content retains the RPC contract of
a nonempty opaque string; the observation admission additionally rejects blank
content. Tags 2..9
and times 0..9_999_999_999_900 are legal mechanical ordering inputs; the caller
supplies their meaning. Ordinary execution uses the Server-supplied success tag
6 and the Result Policy's observed time. Business observations retain their
reported millisecond time and tag.

One single-key Lua validates existing values and conditionally writes the batch:

- a success may replace a failed Result;
- a higher tag replaces a lower tag, even with an earlier time;
- at the same tag, only a strictly greater millisecond time replaces content;
- equal or smaller targets do not write;
- corrupt or legacy success values fail as Owner data errors and are not overwritten.

Terminal failure remains a bounded `HSETNX` operation and cannot replace any
observed Result. Success is canonically encoded before storage. No companion
classification key, version counter, reply history or separate content queue
exists. `TaskItemResult(code, opaqueResultPayload)` remains the read projection;
ordering fields are validated by the Owner and are not exposed in Server Result
responses.

Point reads use one `HMGET`; scans use bounded `HSCAN COUNT 1000` pages. Missing
fields remain not observed. Reads neither consume content nor read Item Score.

## Execution And Observation Calls

Ordinary execution success keeps its existing order:

```text
LPOP EXECUTION_SUCCESS
  -> TaskRuntime conditional success Result write
  -> TaskItemScoreBandCore promotion to the supplied success tag 6
  -> Worker execution event exact-releases the original lease
```

`EXECUTION_FAILURE` only processes the correlated Worker lease. Dispatch alone
stores terminal failed Result before requesting tag 5 for exhaustion or TTL.
A failed Result write leaves that Item Score unchanged for another Dispatch round.

An observation follows a separate finite TaskItem event method:

```text
LPOP OUTCOME_OBSERVATION
  -> decode original Task/Item correlation and verify Worker source
  -> one per-Task batch Score promotion
  -> conditionally store available content for TRANSITIONED or NOOP Items
  -> finish without Worker lease or Task score operations
```

Batch reduction independently retains each Item's maximum state target and
maximum content-bearing observation. A content-free observation cannot erase
content. Missing, invalid or corrupt Item Scores do not create Result content.
`NOOP` still permits a Result comparison: two observations within the same 100ms
Score slot may have different millisecond content times. No Score confirmation
read is added. Up to 100 Items of one Task cost one Score Lua plus zero or one
Result Lua, excluding the evidence append and consume.

## Independent Commit Semantics

Item Score owns scheduling finality. Result owns observed content. Neither is
derived from the other, and both owners commit independently:

- loss after `LPOP` may lose the evidence;
- execution success interrupted after Result storage may leave content with
  ACTIVE or tag 5; there is no unconditional eventual promotion guarantee;
- observation interrupted after Score promotion may leave a newer state with
  older or absent content;
- execution success interrupted before Worker release relies on existing lease
  expiry/recovery; later business observations never release that lease again.

A terminal Task or Item can accept later observations without reopening
scheduling. State-only observations do not invent a successful Result payload.
No ACK, replay, Result-to-Score repair or cross-owner compensation is provided.

## Related Owners And Migration

[Result Policy](../../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
owns admission interpretation, grouping and semantic event publication.
[Pacer assembly](../../../kernel_pacer_jvm/doc/application-assembly.md) owns
shared capacity and lifecycle. [TaskItem Score](../score/task-item-score-band-scheduling.md)
owns encoding and legal Score transitions.

Deploy through the agreed stopped-scope rebuild: stop all users of the explicitly
selected scope, clear only that scope with `SCAN` plus `UNLINK`, and rebuild.
Old success JSON without ordering fields, legacy raw Results and retired
classification sets have no compatibility reads or background migration. No
non-test data is cleared without an explicitly named scope. Proofs use unique
`test_*` scopes.
