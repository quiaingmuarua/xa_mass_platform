# Worker Lab Convergence Integration

`worker-lab-reliability` is an external Java 21 and standard-library Python
proof harness. It owns no Worker, Server, Adapter, Kernel, Redis, scheduling,
or lifecycle implementation. Java scenarios call only the loopback Lab API and
public Runtime APIs; the Python one-shot runner owns process lifecycle, isolated
Lab roots, startup plans, Redis test scopes, and evidence directories.

The module has three independent lanes:

```text
Worker State Convergence
Worker Task Fault Convergence
Seeded Convergence Campaign
```

Lab desired/runtime state, Adapter network state, and Kernel scheduling state
are independent observations. No lane promotes one projection into another
owner's truth. The maximum wait bounds correctness; recorded transition times
are evidence, not a performance SLA.

The Lab is a mutation source and a local witness, not a consistency
coordinator. Each action is issued once. A successful API response records an
established action; the actual local world is read independently before it is
related to Adapter or Kernel projections. A rejected or ambiguous action is
recorded as not established and is not used to judge downstream convergence.
The Harness does not retry, compensate, or keep changing the Lab until a
preferred result appears.

A nonzero lane exit means the proof was not established. It is not, by itself,
evidence that Kernel convergence is defective: the timeline must show an
established local mutation followed by a bounded downstream observation that
failed to converge before that conclusion is available.

## State Convergence

`runWorkerStateConvergence` expects a startup plan with two fixed replicas from
each Scenario Group and one startup scheduled stop. It proves:

- a stopped route eventually becomes `disconnected` and `recovery/cold`;
- explicit restart reuses the Server-issued workerId;
- a stopped Worker's atomic Properties replacement appears only after the next
  Prepare and participates in `worker.labSlot/$eq` matching;
- an unavailable String Group does not prevent an independent Phone Task from
  reaching an exported final Result;
- a parked String Task reaches an exported final Result after the matching
  Worker is restored.

The startup scheduled stop may fire before the Java Harness attaches. The lane
therefore requires the other three Workers to be connected, reads the stopped
Worker's stable identity from Runtime Preview, and then proves its local,
network, and scheduling convergence. It does not require observing a transient
four-Workers-connected instant.

Intermediate score order, exact latency, executing Worker, and capability
payload are deliberately not fixed. The isolated one-shot lane does not restore
Worker lifecycle or Properties after the proof; the Python runner owns the
temporary Lab root and process cleanup.

## Task Fault Convergence

`runWorkerTaskFaultConvergence` has four process-external phases:

```text
arm -> hard-kill Host -> down -> restart backup -> recover
    -> hard-kill Host -> finality
```

The target String Worker enters the fixed
`extension.worker.lab.checkpoint` Handler before the runner forcibly terminates
the Host. While the Host is offline, the lane waits for route and scheduling
convergence. The runner atomically gives one stopped backup Worker the same
`labSlot`, restarts only that Worker, and requires the original TaskItem to
produce one final exported Result. A second Host loss must not revoke that Task
finality. The export proof correlates the TaskItem identity; it does not inspect
or claim a particular Worker outcome code. Claim count, Adapter retry count,
and intermediate score path are not asserted.

## Seeded Campaign

`runWorkerConvergenceCampaign` defaults to `seed=20260831` and `rounds=20`.
The seed generates a finite sequence of start, stop, scheduled-stop, cancel,
Properties replacement, and Task attempts over four Workers and two campaign
slots. Each attempt is made once; an operation conflict or rejection is
recorded and excluded from downstream assertions instead of being repaired.

After injection stops, the Harness waits only for already accepted scheduled
stops to settle, reads the actual local Worker world, and evaluates applicable
invariants:

- stable local `STOPPED` Workers must converge to `disconnected` and
  `recovery/cold`;
- stable local `RUNNING` Workers must converge to `connected` and a HOT
  scheduling projection;
- a submitted Task must become final when the observed final world contains a
  compatible serviceable Worker; a Task without such final capacity may remain
  pending and is reported as such.

The Campaign does not install a deterministic final world, restart Workers to
cover every Task, or claim that every exported Result is successful. This is a
reproducible convergence proof, not a load, fuzz, random-fault, or soak claim.

## One-shot Run

Redis 7 must already be reachable. The runner builds the Server, Scenario Host
distribution, and Harness classes, then gives every lane an independent
`test_*` scope and process set:

```powershell
python integrations/worker-lab-reliability/run_worker_lab_proof.py `
  --lane all `
  --redis-url redis://127.0.0.1:6379/15
```

Use `--lane state|task-fault|campaign` for one lane. Campaign accepts `--seed`
and `--rounds`. The default output root is:

```text
build/worker-lab-convergence-proof/
  state/evidence/
  task-fault/evidence/
  campaign/evidence/
```

Each lane writes `worker-lab-<lane>-summary.json` and
`worker-lab-<lane>-timeline.jsonl`. Evidence contains actions, coordinates,
identities, public states, timestamps, seed, and assertions. It never records
complete Worker Properties or business payload. Mutation records distinguish
`operation-established` from `operation-not-established`; only the former may
anchor a downstream convergence assertion.

The exact Java entrypoints remain available for debugging an already
orchestrated environment. They mutate that Lab once and do not restore it; the
one-shot Python runner is the isolated proof owner:

```text
./gradlew :integrations:worker-lab-reliability:runWorkerStateConvergence
./gradlew :integrations:worker-lab-reliability:runWorkerTaskFaultConvergence
./gradlew :integrations:worker-lab-reliability:runWorkerConvergenceCampaign
```

Fleet 2x10 and Capability 60-Result acceptance remain separate exact proofs
without injected failures.

```text
./gradlew :integrations:worker-lab-reliability:test
python -m unittest integrations/worker-lab-reliability/test_run_worker_lab_proof.py
```
