# Task Initialization Policy

Status: current Java production Pacer policy. The Python executable spec is
frozen on the preceding INITIAL encoding.

## Purpose

Initialization is the one observable start-condition check between approval
and ordinary RUNNING scheduling:

```text
PRE_REVIEW
-> approve with fixed RUNNING INITIAL coordinate
-> due ACTIVE Item exists
-> exact promotion to RUNNING NORMAL
```

It is not a lifecycle band, admission framework, Worker allocation phase, or
capacity owner.

## Input

`TaskSchedulingMechanism` supplies only verified INITIAL observations through
the fixed Dispatch fan-out:

```text
band       = RUNNING_VISIBLE
timeMillis = 10_000
suffix     = 99 - priority
Descriptor identity matches Task id
```

`DispatchTaskBatchFanout` always asks the Mechanism for NORMAL first and asks
for INITIAL only with the remaining part of the fixed 100-Task source budget.
Approval normally keeps the RUNNING set near 100 via a soft precheck, but
concurrent approvals may exceed it. In that case INITIAL
waits for a later source round with NORMAL capacity. This is normal bounded
initialization policy under the supported small active-Task model. INITIAL is
not yet part of the NORMAL assignment working set, so this deliberate priority
does not weaken the work-conserving guarantee for already-NORMAL due work.

## Policy

The complete policy is:

```text
TaskSchedulingMechanism.observeInitializationReady(initial references)

for each observation:
  false -> no write
  true  -> onInitializationReady(task reference)
```

Promotion uses Redis TIME and resets the ordinary RUNNING suffix to zero.
`STALE`, `INVALID`, and `NOOP` are bounded no-ops. A provider exception fails
only that best-effort lane batch; Redis discovery retries unchanged INITIAL
Tasks in a later round.

## Concurrency

The opaque Task observation is not a lease. Close, pause, or any concurrent
score transition wins through exact CAS. A busy Initialization lane retains no
memory queue or hint; a future INITIAL scan is the liveness path.

## Non-Goals

- No Task/System admission policy registry.
- No capacity transaction; approve composes a RUNNING count precheck with an
  independent exact INITIAL transition.
- No priority-bucket recheck; priority is already encoded in the suffix of the
  fixed INITIAL slot.
- No Worker lease, Candidate Cache, Serviceability probe, or Item claim.
- No business-profile branching in Kernel policy.
