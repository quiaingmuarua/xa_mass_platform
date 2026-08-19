# Worker Serviceability Runtime Redis Shape

Status: active Kernel Redis ABI for the optional Worker Serviceability policy.

## Keys

```text
ws:{prefix}:adapter:{adapterId}:probe-requests
  HASH field = workerId
  value       = "1"

ws:{prefix}:adapter-evidence-results
  LIST item = canonical encoded DeliveryReport
```

The `{prefix}` hash tag keeps each operation on one owner key. No operation
spans Adapter request HASHes or combines a request and result key atomically.

## Request HASH

`offer_probe_requests` uses one Lua call per Adapter and accepts at most 100
explicit unique Worker ids. Existing fields return `ALREADY_REQUESTED`; empty
capacity accepts `OFFERED`; a full 10,000-field HASH returns `CAPACITY`. It
never replaces a field.

`consume_probe_requests` uses `HRANDFIELD key limit` followed by `HDEL` in one
Lua call. Consumption is unordered and destructive. A consumed request has no
in-flight record, deadline, retry, generation, or acknowledgement. A later
stale-score scan may offer the Worker again.

The Python Kernel implements offer and consumption. The Java Server provider
implements consumption only, because it is the bounded Adapter HTTP bridge.

## Result LIST

`append_adapter_evidence_results` accepts at most 100 standard
`ADAPTER -> KERNEL` DeliveryReports. A Route-change Report carries one Worker;
a periodic snapshot Report may carry up to 100. The append Lua script accepts
the prefix that fits under the 10,000-item limit and reports its count.

`consume_adapter_evidence_results` destructively removes at most 100 items from
the head. Corrupt or wrong-endpoint entries are discarded. There is no pending
batch, lease, replay, or result HASH.

The Java Server provider implements append only. Python Kernel owns destructive
result consumption and all interpretation or score policy.

## Failure Model

Both structures are best-effort evidence handoffs. Process failure after
destructive consume can lose work. Request or result loss does not mutate score;
the old score remains eligible for a future bounded scan. These keys are not
Worker connection, Binding, lifecycle, or scheduling truth.
