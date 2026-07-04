# Score-Band Scheduling Architecture

Status: agent-first target mechanism workspace, not current implementation
truth and not an implementation roadmap.

This directory gives future agents the target score-band scheduling context
before they write roadmaps or change runtime code. It is intentionally
separated from the current engine implementation: the existing engine can
provide failure modes, invariants, and proof targets, but it is not the model
to copy.

The design standard here is online-scale production scheduling, not the current
engine shape. Current engine concepts may be mentioned only to identify proof
gaps, failure modes, or migration pressure. They must not define the target
runtime memory model, owner split, or state-transition semantics.

Use this directory to align on:

```text
target owner split
score-band range semantics
task and worker state-transition discipline
runtime memory / Redis shape direction
which events may write score
which facts remain outside score-band
which proof boundaries a roadmap must include
```

## Purpose

Score-band compresses scheduling visibility into a linear numeric axis:

```text
taskId   -> task score
workerId -> worker score
```

The score-band mechanism answers bounded acquire questions:

```text
which task ids may enter a scheduling round now?
which worker ids may enter worker admission now?
```

It does not by itself own:

```text
task backlog
item claim / lease / retry frame
result finality
transport session
worker metadata lifecycle
worker capacity truth
```

Those facts belong to their own runtime owners and are validated after acquire.

## Reading Order

1. [Task Score-Band Scheduling](./task-score-band-scheduling.md)
   - target task active-acquisition score mechanism.
2. [Worker Score-Band Scheduling](./worker-score-band-scheduling.md)
   - target worker/resource eligibility score mechanism.
3. [Score-Band Resource Slot Scheduling Blueprint](./score-band-resource-slot-scheduling-blueprint.md)
   - broader resource-slot scheduling blueprint and demand/supply framing.
4. [Score-Band Worker Runtime Redis Shape](./score-band-worker-runtime-redis-shape.md)
   - first-slice Redis structure reference for worker score-band runtime.
5. [Score-Band Task Runtime Redis Shape](./score-band-task-runtime-redis-shape.md)
   - Redis structure reference for task score lanes plus adjacent work-item and
     result structures.

Roadmaps that execute this direction stay under [`../../roadmap/`](../../roadmap/).
This directory describes mechanisms for agent alignment; roadmaps decide
phases, acceptance, and residue cleanup.

## Owner Split

The target mechanism uses explicit owner planes:

```text
Task score-band
  owns task active-acquisition visibility

Work-item owner
  owns item readiness, claim, lease, retry frame, and claim compensation

Result owner
  owns result finality, recent-final barriers, and result read projection

Worker score-band / worker-runtime
  owns worker eligibility acquire, validation, admission, capacity, and score
  placement

Transport / adapter
  owns final-hop delivery, mailbox/session/freshness evidence, and opaque
  selected-worker delivery facts
```

No owner may use score absence as lifecycle proof. Score-band is an acquire
index, not a full state machine, storage model, or read model.

## Extension Scope

Future documents in this directory should cover mechanism-level questions such
as:

```text
runtime memory model
Redis key shape
state-transition rules
score rewrite discipline
task / worker acquire protocol
failure and stale-candidate handling
proof boundaries for score-band behavior
```

Do not put phase plans, implementation TODO lists, or migration instructions
here. Put those in `roadmap/` and link back to the mechanism document they
execute.

## Guardrails

- Do not backfill this directory from current engine shapes unless the text is
  explicitly labelled as a failure-mode or invariant reference.
- Do not make task score-band own item claim, item lease, retry-frame mutation,
  or result finality.
- Do not make worker score-band own transport sessions or raw heartbeat truth.
- Do not let transport identifiers become scheduling candidate truth.
- Do not add a second hot-path candidate index without naming its owner,
  lifecycle, update discipline, and deletion path.
- Do not turn a Redis shape note into public API or current implementation
  truth.
