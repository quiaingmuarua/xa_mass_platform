# Worker Proof Scenarios

Status: current deterministic Worker system scenarios.

These scenarios prevent Scale, Topology, Chaos and Workload dimensions from
forming a Cartesian product. Each scale tier fixes the other dimensions and
owns one primary claim.

## Correctness: 20 Workers, 100 Items

```text
World       2 Groups x 10 Workers
Topology    WebSocket, one Adapter, heterogeneous fixed Properties
Workload    2 managed items:call batches, 50 Items per Group
Mutation    one graceful Scenario Host restart
Oracle      exact identity/route/extension/Result closure
```

Initial phase:

1. Discover exactly 20 Lab addresses and 20 unique Server-issued worker IDs.
2. Relate each identity to Runtime Preview, connected Adapter route,
   Properties projection and one Direct Call.
3. Submit two 50-Item batch calls. Each Group's three Event Names receive
   `17/17/16` Items.
4. Require the response identity sets to equal the submitted sets and all 100
   statuses to be `SUCCEEDED`; do not inspect Result payloads.

Restart phase:

1. Stop only Scenario Host gracefully; retain Server, Redis and Lab files.
2. Restart the same Host.
3. Require all 20 Lab addresses to resolve to their original worker IDs and
   re-establish the same route/probe relations.

Capability-specific output values remain Owner-test claims.

## Convergence Health: 100 Workers, 1000 Items

Both scenarios use 2 Groups x 50 Workers, one WebSocket Adapter and isolated
Redis/Lab state.

Their Scenario assembly gives each current Endpoint a bounded 600-attempt,
500-millisecond reconnect budget. This exists so one deliberate Server restart
stays inside the same Worker run; it is a fixture boundary, not a reconnect SLA.

### State And Server Convergence: 700 Items

Two managed ON_DEMAND streams are fixed:

```text
Phone  x ON_DEMAND_ITEM_RULE
String x ON_DEMAND_ITEM_RULE
```

Seven waves submit 50 Items per Group with `items:call`. Item one is the named
valid witness and every tenth Item is deterministically invalid. Calls wait 250
milliseconds; `SUCCEEDED/NOT_OBSERVED` are immediate observations only.
String Item two is a deterministic 10-second `extension.worker.lab.delay` and
Item three is `extension.worker.lab.fail`; Phone Items are unchanged. These
seven DELAY and seven FAIL Items are background offered load, not additional
per-Item or Result assertions.
Directed witnesses use an explicit finite candidate identity set together with
their mutable Property condition. The other 49 Items keep empty rules, so this
scenario does not turn bounded candidate discovery into part of its oracle.

1. Baseline all 100 Workers connected and HOT.
2. Stop five Workers per Group; require disconnected plus recovery/cold, then
   restore them.
3. Stop two Workers per Group, replace `convergenceSlot`, start them, observe
   the refreshed snapshot and prove ON_DEMAND matching uses it.
4. Stop the entire String Group. Phone work completes while String work stays
   due; restore String and close it.
5. Complete a recovered baseline witness wave.
6. Submit a String witness for an unmatched slot, prove it remains unobserved,
   restart Runtime Server while Scenario Host stays alive, then introduce one
   matching Worker.
7. Require 100 stable worker identities, connected/HOT convergence, the parked
   witness and the final stable witnesses. Fix `700 offered / 70 invalid`, not
   700 successful Results.

### In-Flight Loss Convergence: 300 Items

Each of its three String batches uses the same Item-two DELAY and Item-three
FAIL background pattern, for three offered Items of each kind. The checkpoint
remains Item one and is the only execution Item promoted to a named oracle.

1. Baseline 100 connected/HOT Workers and complete both wave-one witnesses.
2. Arm one Scenario-only String checkpoint. Its witness rule names target and
   backup as the finite candidate set and requires the target's current
   `labSlot`; submit wave two and wait until the uniquely matched Handler enters
   it.
3. Kill Scenario Host, then require the 100-worker world to become disconnected
   and leave serviceable HOT state.
4. While Host is down, change one backup Worker's `labSlot`; restart 99 Workers
   while excluding the original target.
5. Require the original in-flight checkpoint witness to finish on the recovered
   world; submit wave three and complete its two named witnesses.
6. Kill Host again and prove the checkpoint Result remains final. Fix `300
   offered / 30 invalid`, not 300 successful Results.

PRECOMPUTED task-rule and WebSocket/Socket/Polling combinations remain Primary
Proof claims of Runtime Boundary. `results:export` remains an Owner/boundary and
bulk-transfer surface, not a polling oracle for these small high-level proofs.

Each Lab action is issued once. A failed or ambiguous local action is not
evidence against Adapter or Kernel convergence; the scenario fails at that
phase rather than retrying or constructing a preferred world.

## Android Emulator: One Worker And Fixed Triad

```text
World       one API 33 Emulator; one Debug App plus three Lab application IDs
Topology    one Android Worker per App, one Group, one WebSocket Adapter
Workload    10 exact DELAY Items, one process-loss DELAY, 3 Triad DELAY Items
Mutation    route loss, Handler-time process death, endpoint loss, lab2 outage
Oracle      local mutation evidence, Network, Scheduling, Direct Call and Task
```

The Debug App remains the Primary Proof for one Android Worker lifecycle:
explicit stop/start, App restart, Handler failure isolation, Adapter physical
route loss, process death while a DELAY Handler is active, Task recovery,
endpoint retry exhaustion, Server restart and explicit recovery. Local
`activeDelayCount` establishes only that the process-loss mutation reached the
Handler; Adapter Network and Kernel Scheduling APIs independently prove the
resulting outage and recovery.

The fixed Triad adds only the multi-process claim. Three application IDs create
three independent Android sandboxes and worker identities in the same Group.
Correctness directs one Item to each through a `workerId` candidate bound plus
the matching `worker.packageName` constraint. Convergence force-stops `lab2`,
requires `lab1` and `lab3` to remain serviceable, then
restarts `lab2` and requires its original worker ID. It does not generalize N,
repeat Server restart, or claim Android background survival. The disposable
Emulator disables cached-app freezing so platform process policy does not
invalidate this fixed topology.

The Android lane does not yet claim dynamic Properties re-Prepare across the
real Runtime boundary, Doze/OEM policy, multiple devices, Handler throughput or
an arbitrary number of Apps. Those are separate claims rather than missing
iterations of the fixed single-Worker and Triad scenarios.

## Capacity: 10,000 Workers

```text
World       1 Group x 10,000 Workers
Topology    WebSocket, one Adapter, one Java 21 Host
Workload    two finite 100-Item samples
Mutation    one Runtime Server restart; Host retained
Oracle      >= 9,900 connected-and-HOT plus Linux resources
```

The lane generates 100 JSONL files of 100 Workers, prepares all 10,000
identities, holds the threshold for a stable window, restarts Server once and
re-establishes it without another Prepare. It records worker-ID digest, RSS,
native thread and file-descriptor evidence.

This is a resource/capacity claim. It does not repeat the 20-Worker correctness
oracle, inject the 100-Worker fault matrix, or claim Task throughput.

## Excluded Products

The scenarios deliberately do not multiply:

- WebSocket, Socket and Polling across every scale; protocol Owners and Runtime
  Boundary own topology witnesses.
- single/multi Group and single/multi Adapter across every fault; only the
  topology required by the claim is used.
- every chaos action against every workload mode; deterministic mutations are
  assigned to one health scenario.
- 1,000 Workers as an intermediate scale; it adds neither the 100-Worker
  convergence claim nor the 10k resource boundary.
- TaskItem counts beyond 100/1000 for scale theater; Tasks construct sustained
  due work, while Worker count owns capacity pressure.
