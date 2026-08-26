# Kernel Design Agent Handoff

Status: current local change contract for `kernel_design/`.

Read the repository [architecture entrypoint](../README.md), root
[AGENTS.md](../AGENTS.md), this workspace [README](README.md), and the relevant
owner document before changing Kernel behavior.

## Trust Order

1. `executable_spec/` code and focused tests.
2. Verified Redis behavior.
3. Current owner documents under `doc/`.
4. Workspace README and this handoff.
5. Historical tag material as failure-mode evidence only.

If code and a current owner document disagree, identify the drift before
editing. Do not normalize current implementation debt into the mechanism
contract merely because it is easier to test.

## First Read

Use the smallest path relevant to the change:

- Resource truth: `doc/resource-model/` and matching owner interfaces.
- Score behavior: the matching score-band document, score owner and tests.
- Assignment: `doc/scheduling/assignment-dispatch-scheduling.md`, the bounded
  Pacer implementation and integration proof.
- Result behavior: `doc/scheduling/result-routing-scheduling.md`, result owner
  and focused tests.
- Delivery boundary: `doc/scheduling/worker-delivery-dispatch.md`.
- Application lifecycle: `doc/kernel-application-assembly.md`.
- Redis shape: matching `doc/runtime-redis/` note plus real Redis provider and
  proof.

Do not start with Server, Transport or legacy code when the requested change is
a Kernel mechanism decision.

## Owner Rules

- Task record, Task score, TaskItem record, TaskItem score, Worker resource,
  Worker score, DeliveryCommand and result disposition remain distinct owners.
- Score is a scheduling coordinate, not a resource lock or general version.
- Keep score values opaque outside score-owner operations.
- Assignment Dispatch may orchestrate bounded owner reads and transitions; it
  does not merge owner truth.
- Result Routing owns retry/finality policy and accepted evidence disposition.
- Python Result Routing, Worker Serviceability and Assignment Dispatch remain
  mechanism oracles. Their fixed Java implementations are the only production
  Pacers. Do not add a managed Python mode, fallback, dual-consumer or
  dual-producer path.
- Worker Delivery carries already-assigned work and never selects a Worker or
  claims a TaskItem.
- DIRECT_CALL is a Server use case. Its generic non-overwriting Worker Command
  offer may use the delivery owner, but it must not enter Task scheduling,
  score encoding or Result Routing.
- Event and wake paths are best-effort accelerators. Owner scans and exact
  rechecks preserve correctness.

## Interface Change Gate

Before adding or widening an owner operation, record:

1. the owner and named invariant;
2. the bounded caller and input identities;
3. the key or keys touched;
4. atomicity, stale and failure semantics;
5. why caller composition or an existing operation is insufficient;
6. focused executable proof and, for Redis behavior, real Redis proof.

Reject or narrow operations that provide global discovery, cross-key fan-out,
owner-spanning aggregation, background coordination or mirrored DTOs without a
high-ROI invariant.

Prefer same-key aggregation and explicit primitives. Do not add a carrier
record merely to make a mechanical signature look smaller.

## Score Changes

- Preserve the documented exact integer range and Redis double precision
  proof.
- Preserve owner-specific tag, time and suffix semantics.
- Add boundary, ordering, illegal-transition and exact-CAS tests.
- A policy may choose a coordinate, but it does not own the score encoding.
- Do not expose raw score values through Server convenience APIs.
- Pause/release mechanisms must preserve polarity, lane and dirty unless the
  owner contract explicitly changes them.

## Redis Changes

- Redis keys and Lua belong to the matching owner provider. `RedisKeyspace`
  supplies only the fixed root plus validated scope; it is not a global
  business-key factory.
- Avoid cross-owner scripts and global scans.
- Use exact observed-value comparison for stale-sensitive transitions.
- Pipeline only caller-bounded independent owner operations.
- Prove concurrency and partial-result behavior against real Redis.
- Shape documents are implementation contracts, not public APIs by default.

## Application And Policy

- `kernel_pacer_jvm` application assembly owns production Pacer threads,
  startup order and bounded shutdown. Stable `kernel_jvm` owner contracts do
  not create background threads, and Server only adapts the aggregate runtime
  to Spring.
- Policies choose bounded inputs and legal owner operations; they do not become
  new truth owners.
- Task admission limits and fairness are best-effort policy unless a stronger
  invariant is explicitly approved.
- `PRECOMPUTED_TASK_RULE` and `DIRECT_ITEM_RULE` are Worker allocation
  mechanisms. `CLOSE_WHEN_IDLE` and `PARK_WHEN_IDLE` are independent idle
  dispositions. The public Server may expose only finite, proved combinations;
  do not turn the Kernel descriptor into an API profile enum.
- Idle park is a private RUNNING score coordinate. Ordinary Item append never
  wakes it. The bounded Task Call submission invokes the score owner's
  idempotent idle-park release before and after append; it must not recover
  Descriptor, allocation, idle-disposition, band, or ACTIVE-Item policy.
- Task close remains a policy over owner truth; do not add strong cross-owner
  consistency solely to eliminate bounded recheck.

## Python Naming And Contracts

- Public executable-spec names mirror the owning mechanism vocabulary.
- DTOs contain facts their owner can validate and construct.
- Keep storage implementation details out of public contracts.
- Do not preserve superseded names with aliases unless an external compatibility
  obligation is explicitly approved.
- The assembly CLI is a standalone Oracle entrypoint and exposes no network
  surface. It always assembles the complete Python mechanism and is not a
  production artifact or Java-managed child. Task commands remain directly
  testable through the executable application and are not Python HTTP routes.

## JVM Parity

- JVM parity is incremental and caller-driven.
- Do not widen Java scheduling merely because the Python mechanism exposes a
  broader test surface; Java parity remains production-caller-driven.
- A parity slice names the Python interface/DTO/enum, current caller, Java
  provider and proof it replaces or enables.
- Missing JVM operations remain explicit gaps; no remote fallback or default
  method may hide them.

## Proof Discipline

For every behavior change:

1. run the focused deterministic owner tests;
2. run the complete Python executable suite;
3. run `compileall`;
4. run real Redis proof when storage, CAS, ordering or concurrency changes;
5. update the owning document in the same change;
6. scan for old names, old paths and stale gap/status text.

Canonical commands:

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
```

Set `KERNEL_DESIGN_REDIS_URL` for Redis-backed proofs. The repository
[TESTING.md](../TESTING.md) owns cross-process and JVM lanes.

## Documentation Rules

- `doc/scheduling/README.md` is the only scheduling status matrix.
- Owner documents define detailed mechanism behavior.
- Workspace README is an entrypoint and axiom summary, not a second status
  catalog.
- Root architecture owns cross-module authority and stability classification.
- AGENTS files own change rules, not unique production facts.
- Historical notes remain historical and must not be linked as current truth.
