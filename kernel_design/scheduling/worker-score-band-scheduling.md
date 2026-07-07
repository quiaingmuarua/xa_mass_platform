# Worker Score-Band Scheduling

Status: architecture mechanism note. This document describes the target worker
score-band scheduling mechanism. It is not current implementation truth and not
an implementation roadmap.

## Purpose

Worker score-band scheduling compresses worker/resource scheduling eligibility
onto one linear numeric axis:

```text
workerId -> score
```

The scheduler hot path does not interpret transport sessions, raw worker state,
or a full worker object. It only asks:

```text
which worker ids have score in the acquire range now?
```

Worker score answers:

```text
can this worker/resource be acquired as a scheduling candidate now?
```

It does not answer:

```text
is the transport session connected?
which task should use this worker?
is capacity currently admitted?
is worker metadata valid for a specific task?
```

## Evidence Owner Boundary

Adapter / transport owns final-hop delivery evidence:

```text
endpoint/session observation
heartbeat / keepalive freshness
adapter-local consumer availability
delivery mailbox evidence
disconnect / unavailable evidence
```

Worker-runtime owns scheduling eligibility:

```text
worker metadata validation
readiness / dispatch gate interpretation
reachability interpretation for scheduling
capacity / admission hold
score-band placement
verified reopen after recovery
```

Adapter observations may be positive, neutral, or negative evidence, but they
should cross into worker-runtime only as validation inputs. They should not
emit default worker-runtime wakeups. Raw `CONNECTED`, `WORKER_HEARTBEAT`,
`SESSION_KEEPALIVE`, and `TRANSPORT_REFRESH` remain transport evidence unless
worker-runtime explicitly reads them during validation. A worker becoming
schedulable is a worker-runtime-verified fact, not a raw transport fact or a
raw event side effect.

## Core Model

Worker score is an acquire index, not worker lifecycle truth.

Rules:

- score is one sortable value in a worker-runtime-owned bucket index;
- score absence has no business meaning to score-band;
- score-band does not explain service discovery or why a worker is absent;
- acquire is a bounded range query;
- acquired workers still require worker-runtime validation before admission;
- transport heartbeat, keepalive, connected, and freshness observations are not
  generic score-refresh triggers and do not emit default wakeups.

Worker score-band is the worker-side scheduling clock. Transport/session
observations may provide evidence for worker-runtime validation, but the worker
does not become schedulable because an event arrived:

```text
transport evidence observed
  -> worker-runtime may read it during validation
  -> policy maps validated truth to a worker score
  -> score-band decides the next acquire opportunity
```

Conceptual flow:

```text
worker score acquire
  -> worker metadata / gate / reachability / capacity validation
  -> worker admission / hold when needed
  -> optional score rewrite for contention, cooldown, or failed validation
  -> selected worker handle returned to scheduling
```

## Band Definitions

Worker score bands are resource-owner-defined. The current target model uses
four conceptual regions:

```text
PARKED_BAND
  intentionally outside active acquire and routine recheck
  explicit owner action or policy promotion required

LOW_RECHECK_BAND
  recoverable negative state
  not acquired by the hot path
  owner maintenance may recheck and rewrite score

ELIGIBLE_BAND
  currently acquire-visible
  acquired workers still need metadata, gate, capacity, and ownership
  validation

FUTURE_BAND
  temporary future unavailability such as cooldown, occupancy, or claim interval
  time-due by score interpretation
```

Worker score does not need to use the same ranges as task score. The shared
mechanism is the linear score axis plus range acquire, not identical band
meanings.

## Acquire Semantics

The worker-runtime acquire query is conceptually:

```text
ZRANGEBYSCORE worker:score:{homeBucketId}
  eligibleLowerBound
  now
  LIMIT 0 N
```

`homeBucketId` is the worker resource's primary runtime partition. First slice
may use:

```text
homeBucketId = workerGroupId
```

Acquire returns candidates only. It does not prove final admission.

After acquire, worker-runtime validates:

```text
worker exists in the expected home bucket
worker group / capability membership
approved scheduling metadata
reachability evidence
readiness / dispatch gate
capacity / admission availability
owner-approved placement attributes
```

Only after validation and admission may the worker become a selected worker
for a scheduling round.

## Kernel Scheduling Protocol

Worker score-band participates only in the worker side of a scheduling round:

```text
1. kernel scheduler obtains a task scheduling round from task score-band
2. policy compiles worker demand / placement constraints
3. worker-runtime acquires bounded worker candidates from score-band
4. worker-runtime validates metadata, reachability, readiness, and capacity
5. worker-runtime admits or rejects the worker
6. kernel scheduler dispatches only after work-item claim and worker admission both hold
```

Worker score-band does not inspect task backlog, claim task items, or write task
score. It returns resource-side evidence for the scheduling round.

## Score Update Discipline

Worker score updates are intentionally narrow.

### Acquire-Range Rewrite

Workers already in the acquire range may be rewritten by the scheduling /
admission round that observed them:

```text
worker score due
  -> worker-runtime acquires candidate
  -> validation / admission / hold produces evidence
  -> worker-runtime may rewrite score
```

This rewrite is not required to make the worker schedulable; it was already in
the acquire range. The rewrite exists for:

```text
capacity contention
cooldown
failed validation
admission hold interval
admission fairness
anti-spin
```

### Direct Owner Command / Transition Writes

Workers outside the acquire range should not be periodically refreshed because
unrelated evidence changed. Their score changes through directly related owner
commands or owner-validated transitions:

```text
verified available
explicit disable / enable
drain / undrain
fast-close from direct negative evidence
verified reopen after recovery
group or worker policy change that directly affects eligibility
```

### Non-Triggers

These update their own owner truth and do not trigger generic score refresh or
default event emission:

```text
transport heartbeat
session keepalive
connection refresh
raw connected event
raw latency sample
read-model update
trace materialization
```

They may be read when an owner command, owner-validated transition, or acquired
admission round computes a new score, but they do not by themselves drive score
refresh.

## Input Write Taxonomy

| Input kind | May write worker score? | Notes |
| --- | --- | --- |
| explicit disable / enable | yes | direct eligibility owner command |
| drain / undrain | yes | direct dispatch eligibility command |
| verified available / reopen | yes | must validate owner facts first |
| fast-close negative evidence | yes | direct negative close path |
| worker admission round | yes | primary live rewrite path after acquire |
| heartbeat / keepalive | no | transport/freshness evidence only |
| connected / session refresh | no by itself | not equivalent to schedulable |
| read projection / trace | no | observability only |

## Transition Rules

Worker score transitions are owner-evidence-driven:

```text
direct worker-runtime owner command / transition
  -> mutate worker eligibility fact
  -> compute score
  -> write score

acquired admission round
  -> validate worker candidate
  -> admit, hold, or reject
  -> classify evidence
  -> rewrite score
```

Typical transitions:

```text
verified available and capacity free
  -> eligible score

explicit disable / drain / operator park
  -> parked score

recoverable disconnect / missing endpoint
  -> low-recheck score or direct fast-close score, depending on policy

capacity full / held / cooldown
  -> future score

admission failed stale metadata
  -> low-recheck or parked score, depending on owner policy

admission hold succeeded
  -> future score if the worker should not be immediately reacquired
```

## Atomicity Boundaries

Score updates must be atomic with the worker facts they protect when stale
intermediate state would allow wrong admission.

Important boundaries:

```text
disable / drain / fast-close
  gate evidence + score write

verified reopen
  validation evidence + score write

admit/hold
  capacity/admission mutation + score rewrite when needed

release/final
  capacity truth update only; score rewrite belongs to a later acquired
  admission round or an explicit direct owner policy, not generic positive
  refresh
```

Do not add a broad distributed lock around worker scheduling by default.
Worker-runtime admission and score transition should carry the concurrency
boundary.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

- score due but worker row missing: reject candidate, clean opportunistically;
- score due but worker disabled/draining: reject candidate and write direct
  out-of-range score if the owner fact is current;
- score due but reachability/readiness validation fails: reject and rewrite to
  low-recheck, parked, or future according to policy;
- score due but capacity is full: reject or admission-fail and future-score;
- stale positive event cannot reopen a worker directly;
- transport connected is not enough to write eligible score.

Stale handling must be bounded. Do not scan all workers to repair score.

## Policy Seams

Score-band mechanism should stay stable while policy remains replaceable.

Policy owns:

```text
eligible score placement
cooldown duration
admission hold interval
low-recheck priority
failed recheck threshold
park / unpark rules
capacity contention delay
fast-close reason mapping
verified reopen rules
```

Mechanism owns:

```text
linear score axis
bounded acquire range
owner validation after acquire
atomic transition boundaries
no broad refresh from low-value transport observations
```

## Guardrails

- Do not use score absence as worker lifecycle proof.
- Do not let transport heartbeat, session keepalive, or raw connected event
  write eligible score.
- Do not create per-task worker candidate keys.
- Do not fan out score across placement-tag buckets in the first slice.
- Do not store transport/session evidence in worker scheduling metadata.
- Do not make score replace capacity/admission validation.
- Do not let read projections or trace materialization drive worker score.
- Do not force task score ranges onto worker-runtime bands.
