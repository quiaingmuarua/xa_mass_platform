# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel owns Task configuration, scheduling, claim, retry and finality. The Task
descriptor is the single persistent source for its Rule name and resolved refill
declarations. Kernel checks their structure without interpreting parameters.
Matching owns facts, Rule semantics and Group/Rule stock; it has no Task-ID
configuration relationship. No Rule instance or index coordinate is stored in Task.

```text
TaskDescriptor = taskId, workerGroupId, idleDisposition, config, ruleId, refillTargets
TaskItem = messageId, eventCode, createdAtMillis, payload, priority,
           expireAtMillis, workerSelector
```

Task config contains exactly the string values `priority` (0 is highest) and
`maxRetryTimes` (initial Item budget). A new key requires a named scheduling
consumer. Rule configuration uses separate descriptor fields. Rule name is non-blank;
the immutable target list has 1..100 entries with counts 1..1000. Descriptor
equality includes both fields; Kernel neither supplies defaults nor resolves Rules.

## Cross-Owner Creation

Server selects the supplied Rule or `worker.default` and locally resolves complete
targets through Matching before creating the Kernel descriptor. Explicit targets
override Group/Rule defaults, otherwise ANY 100. Resolution has no Redis or stock
side effect. The complete descriptor is create-only; Task Score initialization and
descriptor writing retain their existing separate commit and retry boundaries.
No independent Matching write or dangling binding remains.

Saved targets are a creation-time snapshot. Managed registration compares the full
expected descriptor; ordinary lookup of an existing managed Task checks its fixed
ownership and Call attributes without resolving current defaults.

Before Item append or managed Call, Server captures the selector with Kernel's
bounded parser and validates it through Matching using the descriptor Group and Rule name. Kernel stores
the immutable Map unchanged. ANY is `{}`; explicit IDs use the sole key
`workerId`; other maps are Handler-owned property conditions, including bounded
multi-field AND queries. Kernel does not interpret operators or facts.

## Scheduling Handoff

Main shares its already-read immutable NORMAL Task descriptors with refill and
dispatch. No separate Matching configuration read or join remains. Pacer carries
Rule names without interpreting query fields.
The refill Producer concatenates Task targets by Group/Rule. Matching observes
shortages and admits candidates through separate named calls, normalizing targets
with MAX and preserving bounded paging. Pacer acquires the 1-second lease before
Matching reads eligibility; stock retains that fence and deadline without renewal.
Dispatch calls `WorkerMatching` with the explicit Group, Rule name and
messageId-to-query Map. Matching normalizes and groups queries, then returns the
held candidate for each fulfilled message ID. No
executable binding view or refill callback crosses the module boundary.

Kernel retains HOT/floor/exact initial acquisition, round uniqueness, execution
confirmation, Item claim and Command construction. Properties invalidate old
fences through mark; stock can overcount until consumed or expired. No per-Task
invalidation or compensation release is required.

Task lifecycle is independent: finite Tasks use CLOSE_WHEN_IDLE and managed
Calls use PARK_WHEN_IDLE. Matching absence must not block Item exhaustion/expiry
or idle settlement. Item terminal outcomes remain terminal for scheduling while
accepting later monotonic observations, independently of Task closure.

## Redis Shape

Descriptors use exactly `workerGroupId`, `idleDisposition`, `configJson`, `ruleId`
and `refillTargetsJson` in their HASH, written together by the create-only Lua.
Targets are a JSON array of `{query,count}` objects. Missing/newly required fields,
unknown target fields, null queries and non-integer counts fail strict decoding. TaskItem JSON has the exact fields above; workerSelector is the Map itself,
not an envelope. Old descriptor mode/capacity fields, missing/null selectors and
old wrapper/array forms and nested `{op,values}` property conditions are rejected
rather than interpreted as unrestricted. The shared EligibilityQuery contains only
string-list parameters; Rule normalization defines all meanings, including IDs.
Existing ANY and ID Maps retain their shape and remain readable.

Use a new scope to recreate Tasks. Old descriptors fail as corrupt; there is no
retired Matching lookup or default substitution. Old scopes remain untouched; no
dual reader, automatic data migration or cleanup is added.
Matching storage belongs to [its Owner](../../../worker_matching_jvm/README.md).
