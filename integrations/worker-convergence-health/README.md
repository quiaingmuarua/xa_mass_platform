# Worker Convergence Health

This Java 21 and Python Integration is the Primary Proof for
Worker fault and scheduling convergence. It owns no Worker, Adapter, Server,
Kernel, Redis or lifecycle implementation. Java phases use only the loopback
Lab API and public Runtime APIs; Python owns isolated processes, Lab roots,
Redis scopes and phase transitions.

## Fixed World

Each scenario uses:

```text
2 WorkerGroups x 500 Workers
one WebSocket Adapter
ON_DEMAND_ITEM_RULE through managed batch items:call
independent Lab, Network, Scheduling and Task observations
```

The runner materializes its canonical 2x500 Properties Inventory with the same
strict materializer used by Worker Correctness, but into a separate Lab root
and Redis scope. Capability assembly and all mutations remain owned by this
convergence lane. Each Endpoint receives a bounded 600-attempt, 500-millisecond
reconnect fixture so the deliberate Server restart stays within the same Worker
run. This is a scenario boundary, not a reconnect SLA.

The Integration owns a Server catalog override and Scenario capability
assembly that add `extension.worker.lab.delay` and
`extension.worker.lab.fail` only to the existing String Group. Every String
batch offers one 10-second delay Item and one immediate Handler-failure Item as
non-witness background work. The fixed 700/300 workloads remain unchanged as
the topology grows to 1,000 Workers.

The Lab is a mutation source and local witness, not a reconcile coordinator.
Every action is sent once. A failed or ambiguous local operation fails that
phase and is not treated as downstream convergence evidence.

## State And Server Convergence

Seven waves submit 50 Items per Group, for 700 offered Items total. Calls use a
250-millisecond immediate observation window. Item one is the named valid
witness, every tenth Item has deterministic invalid input, and String Items
two and three are the delay and fail background work. Managed ON_DEMAND waves
use empty Worker Selectors. A separate finite PRECOMPUTED Task owns the
Properties-matching witness; it does not change the ON_DEMAND mechanism.

The phase order is:

1. Establish all 1,000 Workers connected and HOT. Stop the directed String
   Worker before workload and keep it unavailable until after Server restart.
2. Stop five other Workers per Group, observe disconnected and scheduling
   unavailable, then restore them.
3. Stop two Workers per Group, replace `convergenceSlot`, explicitly start them,
   and observe the refreshed canonical Properties.
4. Stop the other 499 String Workers and observe all 500 unavailable. Submit
   wave one: Phone progresses while String witness work remains due. Restore
   only those 499 Workers and close the wave.
5. Complete waves two through five.
6. Reconfirm the directed Worker is locally STOPPED, disconnected and scheduling
   unavailable. Submit wave six and a separate finite PRECOMPUTED Task requiring
   `worker.convergenceSlot=C`. Require its Item to remain unobserved, then
   restart Runtime Server while retaining Scenario Host.
7. Require the other 999 stable identities to reconnect and become HOT while
   the directed Worker stays stopped. Replace its stopped-state slot and start
   it once. Require its original identity, canonical Property and connected/HOT
   observations; close the PRECOMPUTED witness, wave six and final wave seven.

Acceptance fixes `700 offered / 70 invalid` and convergence of all named
witnesses. The workload also offers seven delay and seven fail Items. These are
submission counts, not Handler invocation or observed Result counts, and the
scenario does not require every offered Item to succeed.

## In-Flight Loss Convergence

Three waves offer 300 Items with 30 deterministic invalid inputs. Each String
batch retains Item-two DELAY and Item-three FAIL background work. The first
String Item in wave two is the only checkpoint execution promoted to an oracle.

1. Establish all 1,000 Workers running, connected and HOT; complete both
   wave-one witnesses.
2. Explicitly stop the backup before arming the checkpoint and observe it
   STOPPED, disconnected and scheduling unavailable. Arm the target's
   Scenario-only checkpoint. The complete wave-two String batch uses an
   explicit Worker ID selector containing only the target and backup IDs.
   There is no `labSlot` condition or backup Properties mutation. With the
   backup unavailable, wait for the target Handler to enter the checkpoint.
3. Kill Scenario Host and require the entire 1,000-Worker world to become
   disconnected and scheduling unavailable.
4. Restart 999 Workers, including the backup, while excluding the original
   target. Require the 999 identities to reconnect unchanged and the explicitly
   targeted backup to become HOT before closing the original checkpoint
   witness. This does not require all recovered Workers to be simultaneously
   HOT while due work is present.
5. Complete both wave-three recovery witnesses.
6. Kill Host again, observe the recovered world disconnected, and require the
   checkpoint's successful Result to remain observable through `results:load`.
   This is Result retention, not TaskItem Score finality.

Acceptance fixes `300 offered / 30 invalid` and five named successful witnesses,
not 300 successful Results. The synchronous checkpoint path bounds the
in-flight String execution before the first Host kill; it does not establish
exactly-once execution or the identity of a later successful executor.

Neither scenario fixes intermediate score order, absence of transient
serviceability regression, retry count, exact latency, executing Worker,
non-witness outcome or capability payload. `NOT_OBSERVED` is an immediate
observation, not a Worker failure; `FAILED` is a Result state but not a
successful witness. Witnesses are observed with
`results:load`; this lane does not poll `results:export`. In-flight loss also
offers three delay and three fail background Items. There is no assertion that
these background Items execute once, produce a terminal Result or determine a
specific Worker state. There is no random campaign, seed, round count, fault
DSL or automatic compensation.

Multi-Worker outage checks page Network and Scheduling observations at the
public 100-ID boundary, then write the existing per-Worker timeline rows only
after the whole requested set has converged. Runtime Worker preview remains a
100-entry random sample per Group; it checks sampled canonical identity while
the paged Network/Scheduling observations cover the exact fleet. A `wave-6`
timeout additionally records a best-effort bounded
snapshot of witness status, managed Task score band, target Network/Scheduling
and local states, and whether the canonical slot equals C. Snapshot failure is
suppressed under the original proof failure. Diagnostics do not record Worker
Properties, score values, Redis keys, Task payloads or result payloads.

## Run

Redis 7 must already be reachable. With Python 3.11 or newer, install the shared
proof dependency set once:

```powershell
python -m pip install -r .github/scripts/requirements.txt
```

Then run:

```powershell
python integrations/worker-convergence-health/run_worker_convergence_health.py `
  --scenario all `
  --redis-url redis://127.0.0.1:6379/15
```

Use `--scenario state` or `--scenario task-fault` for one isolated scenario.
Proof CI runs those two values as independent matrix jobs; `--scenario all`
remains the local complete entrypoint. One runner invocation reuses a Gradle
daemon with a five-minute idle timeout across artifact build and Java phases;
Server, Host, Redis-scope and mutation ownership are unchanged.
The default five-minute observation bound covers the fixed serviceability
recovery budget at this 1,000-Worker topology; it is not a latency SLA.
Default evidence root:

```text
build/worker-convergence-health-proof/
  state/evidence/
  task-fault/evidence/
```

The Java phase entrypoints are available for an already orchestrated world:

```text
./gradlew :integrations:worker-convergence-health:runWorkerStateAndServerConvergence
./gradlew :integrations:worker-convergence-health:runWorkerTaskFaultConvergence
```

Focused tests:

```powershell
.\gradlew.bat :integrations:worker-convergence-health:test
python -m unittest integrations/worker-convergence-health/test_run_worker_convergence_health.py
```

See [Proof Registry](../../doc/testing/proof-registry.md#worker_convergence_health)
for claim boundaries and [TESTING.md](../../TESTING.md) for lane selection.
