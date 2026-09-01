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
invalid input. A directed witness combines a finite explicit Worker candidate
set with its mutable Property condition; the other 49 Items retain empty rules.
This keeps candidate discovery probability outside the Property-convergence
oracle. The scenario proves:

- five stopped Workers per Group leave connected/HOT serviceability and recover;
- stopped-state `convergenceSlot` changes appear after explicit start and affect
  ON_DEMAND matching;
- a full String Group outage does not stop Phone progress, and parked String
  witness work finishes after recovery;
- Server can restart with Scenario Host retained, all 100 identities reconnect,
  Kernel state converges and an unmatched String witness finishes after a
  matching Worker appears.

Acceptance fixes `700 offered / 70 invalid` and convergence of all named
witnesses. The workload also offers seven delay and seven fail Items. These are
submission counts, not Handler invocation or observed Result counts, and the
scenario does not require every offered Item to succeed.

## In-Flight Loss Convergence

Three waves offer 300 Items with 30 deterministic invalid inputs. During wave
two, one String Item uses the target and backup worker IDs as its finite
candidate set plus the mutable `labSlot` condition, and enters the
Scenario-only checkpoint on the initial target. The runner kills Scenario Host,
waits for the entire Worker world to become disconnected and unavailable,
changes one stopped backup Worker's `labSlot`, then restarts 99 Workers while
excluding the original target. The original checkpoint witness must finish on
the recovered world, and the recovery-wave witnesses must succeed. A second
Host loss must not revoke the checkpoint Result.

Neither scenario fixes intermediate score order, absence of transient
serviceability regression, retry count, exact latency, executing Worker,
non-witness outcome or capability payload. `NOT_OBSERVED` is an immediate
observation, not a Worker failure. Witnesses are observed with
`results:load`; this lane does not poll `results:export`. In-flight loss also
offers three delay and three fail background Items. There is no assertion that
these background Items execute once, produce a terminal Result or determine a
specific Worker state. There is no random campaign, seed, round count, fault
DSL or automatic compensation.

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
