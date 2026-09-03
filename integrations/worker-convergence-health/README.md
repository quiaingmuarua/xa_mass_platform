# Worker Convergence Health

This Java 21 and Python Integration is the Primary Proof for
Worker fault and scheduling convergence. It owns no Worker, Adapter, Server,
Kernel, Redis or lifecycle implementation. Java phases use only the loopback
Lab API and public Runtime APIs; Python owns isolated processes, Lab roots,
Redis scopes and phase transitions.

## Fixed World

Each scenario uses:

```text
2 WorkerGroups x 50 Workers
one WebSocket Adapter
ON_DEMAND_ITEM_RULE through managed batch items:call
independent Lab, Network, Scheduling and Task observations
```

The runner materializes the same canonical 2x50 Properties Inventory used by
Worker Correctness, but into a separate Lab root and Redis scope. Capability
assembly and all mutations remain owned by this convergence lane.

The Integration owns a Server catalog override and Scenario capability
assembly that add `extension.worker.lab.delay` and
`extension.worker.lab.fail` only to the existing String Group. Every String
batch offers one 10-second delay Item and one immediate Handler-failure Item as
non-witness background work. Phone batches and the 100-Worker topology remain
unchanged.

The Lab is a mutation source and local witness, not a reconcile coordinator.
Every action is sent once. A failed or ambiguous local operation fails that
phase and is not treated as downstream convergence evidence.

## State And Server Convergence

Seven waves submit 50 Items per Group, for 700 offered Items total. The first
Item in each batch is a named valid witness; every tenth Item has deterministic
invalid input. Managed ON_DEMAND waves use empty rules. A separate finite
PRECOMPUTED Task isolates the Property-matching oracle. The scenario proves:

- five stopped Workers per Group leave connected/HOT serviceability and recover;
- stopped-state `convergenceSlot` changes appear after explicit start;
- one finite PRECOMPUTED witness remains unmatched until a Worker with the new
  `convergenceSlot` is started, then converges through Task-rule matching;
- a full String Group outage does not stop Phone progress, and parked String
  witness work finishes after recovery;
- after the 100-Worker baseline, the directed String Worker is stopped before
  any workload and remains unavailable through the pre-restart part of wave
  six; all other Worker stop/start and Property mutations also finish before
  wave one. The full String outage stops and restores only the other 49 Workers
  while the unavailable observation still covers all 50. After the Server
  restart the other 99 identities reconnect and become HOT while that Worker
  remains stopped, then one stopped-state Property replacement and one explicit
  start restore its original identity and finish the witness.

Acceptance fixes `700 offered / 70 invalid` and convergence of all named
witnesses. The workload also offers seven delay and seven fail Items. These are
submission counts, not Handler invocation or observed Result counts, and the
scenario does not require every offered Item to succeed.

## In-Flight Loss Convergence

Three waves offer 300 Items with 30 deterministic invalid inputs. During wave
two, the String batch uses the target and backup worker IDs as its finite
candidate set plus the mutable `labSlot` condition. Only the initial target
matches before the mutation, and its first Item enters the Scenario-only
checkpoint. The synchronous connection path prevents the remaining String
Items from creating unrelated in-flight Worker executions before the runner
kills Scenario Host. The runner then waits for the entire Worker world to
become disconnected and unavailable,
changes one stopped backup Worker's `labSlot`, then restarts 99 Workers while
excluding the original target. The original checkpoint witness must finish on
the recovered world: all 99 identities must reconnect unchanged, while the
canonical backup Property and that explicitly targeted backup's HOT state are
checked before the checkpoint closes. The recovery-wave witnesses must then
succeed. The proof does not require all 99 Workers to be simultaneously HOT
while due work is present. After a second Host loss, the checkpoint's successful
Result must remain observable through `results:load`; this does not prove
TaskItem Score finality.

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

Multi-Worker outage checks issue one bounded Network observation for the
EndpointManager and one bounded Scheduling observation per WorkerGroup, then
write the existing per-Worker timeline rows only after the whole requested set
has converged. A `wave-6` timeout additionally records a best-effort bounded
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

See [`doc/testing/worker-proof-scenarios.md`](../../doc/testing/worker-proof-scenarios.md)
for the exact 100-Worker scenario contract.
