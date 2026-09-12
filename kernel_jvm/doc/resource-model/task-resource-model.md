# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel owns scheduling, claim, retry and finality. Matching owns Task-to-Rule
bindings, facts and Rule semantics. Kernel never stores a Rule ID, matching mode,
index key or per-Task candidate capacity.

```text
TaskDescriptor = taskId, workerGroupId, idleDisposition, config
TaskItem = messageId, eventCode, createdAtMillis, payload, priority,
           expireAtMillis, workerSelector
```

Task config contains exactly the string values `priority` (0 is highest) and
`maxRetryTimes` (initial Item budget). A new key requires a named scheduling
consumer. Config is not a container for Matching data.

## Cross-Owner Creation

Server first establishes an explicit Matching Task binding; omission selects
`worker.default`. It then creates the Kernel descriptor. Identical bindings are
idempotent; rebinding conflicts. A failed descriptor write may leave an inert
binding. These writes are not transactional and Matching has no Task lifecycle.

Before Item append or managed Call, Server captures the selector with Kernel's
bounded parser and validates it with the prepared Matching query. Kernel stores
the immutable Map unchanged. ANY is `{}`; explicit IDs use the sole key
`workerId`; other maps are Handler-owned property conditions, including bounded
multi-field AND queries. Kernel does not interpret operators or facts.

## Scheduling Handoff

Dispatch resolves at most 100 Task IDs/Groups through one `prepareTaskQueries`
call. Missing or unusable binding fails closed for assignment. A prepared query
returns bounded IDs and rechecks membership after initial hold; it carries no
Rule ID, index coordinate, cache or lifecycle into Kernel.

Default ANY and explicit IDs use Kernel's bounded HOT path. Named Rules constrain
all selectors through their index. Kernel retains HOT intersection, initial hold,
round uniqueness, exact clean confirmation, Item claim and Command construction.
Properties may invalidate a held candidate through dirty; no per-Task cache
invalidation is required. Unused holds expire naturally.

Task lifecycle is independent: finite Tasks use CLOSE_WHEN_IDLE and managed
Calls use PARK_WHEN_IDLE. Matching absence must not block Item exhaustion/expiry
or idle settlement. Item terminal outcomes remain terminal for scheduling while
accepting later monotonic observations, independently of Task closure.

## Redis Shape

Descriptors use exactly workerGroupId, idleDisposition and configJson in their
HASH. TaskItem JSON has the exact fields above; workerSelector is the Map itself,
not an envelope. Old descriptor mode/capacity fields, missing/null selectors and
old wrapper/array forms are rejected rather than interpreted as unrestricted.

This is a stop-and-rebuild cutover for an explicitly selected scope. No dual
reader, automatic data migration or cleanup of unspecified scopes is added.
Matching storage belongs to [its Owner](../../../worker_matching_jvm/README.md).
