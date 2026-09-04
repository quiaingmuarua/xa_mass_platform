# Worker Proof Scenarios

Status: current deterministic Worker system scenarios.

These scenarios prevent Scale, Topology, Chaos and Workload dimensions from
forming a Cartesian product. Each scale tier fixes the other dimensions and
owns one primary claim.

## Correctness: 100 Workers, 100 Items

```text
World       2 Groups x 50 Workers
Topology    WebSocket, one Adapter, heterogeneous fixed Properties
Workload    2 managed items:call batches, 50 Items per Group
Mutation    one graceful Scenario Host restart
Oracle      exact identity/route/extension/Result closure
```

Initial phase:

1. Discover exactly 100 Lab addresses and 100 unique Server-issued worker IDs.
2. Relate each identity to Runtime Preview, connected Adapter route,
   Properties projection and one Direct Call.
3. Submit two 50-Item batch calls. Each Group's three Event Names receive
   `17/17/16` Items.
4. Require the response identity sets to equal the submitted sets and all 100
   statuses to be `SUCCEEDED`; do not inspect Result payloads.

Restart phase:

1. Stop only Scenario Host gracefully; retain Server, Redis and Lab files.
2. Restart the same Host.
3. Require all 100 Lab addresses to resolve to their original worker IDs and
   re-establish the same route/probe relations.

Capability-specific output values remain Owner-test claims.

## Convergence Health: 1,000 Workers, 1,000 Items

Both scenarios use 2 Groups x 500 Workers, one WebSocket Adapter and isolated
Redis/Lab state. They use a convergence-owned canonical Inventory produced by
the same strict materializer as Correctness, then apply lane-owned mutations
and capability additions.

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
All managed ON_DEMAND wave Items use empty rules. Property interpretation is
proved separately by one finite PRECOMPUTED Task so the two allocation
mechanisms are not blended into one witness.

1. Baseline all 1,000 Workers connected and HOT, then stop the directed String
   Worker before any workload and keep it unavailable through wave six.
2. Stop five other Workers per Group; require disconnected plus recovery/cold,
   then restore them.
3. Stop two Workers per Group, replace `convergenceSlot`, start them, and
   observe the refreshed Properties projection.
4. Stop the other 499 String Workers and observe all 500 unavailable. Submit
   wave one: Phone work completes while String work stays due; restore only
   those 499 and close it.
5. Complete waves two through five on the recovered world.
6. Reconfirm the slot-C String Worker is locally STOPPED, Adapter disconnected
   and Kernel scheduling-unavailable. Submit one finite PRECOMPUTED Task whose
   PRECOMPUTED Candidate Rule requires `worker.convergenceSlot=C`, prove its Item remains
   unobserved, then restart Runtime Server
   while Scenario Host stays alive.
7. Reconnect the other 999 stable identities and require them HOT while the
   directed Worker remains stopped. Replace its slot, start it exactly once,
   require its original identity plus connected/HOT/canonical Property, then
   close the parked witness and final stable wave. Fix `700 offered / 70
   invalid`, not 700 successful Results.

### In-Flight Loss Convergence: 300 Items

Each of its three String batches uses the same Item-two DELAY and Item-three
FAIL background pattern, for three offered Items of each kind. The checkpoint
remains Item one and is the only execution Item promoted to a named oracle.

1. Baseline 100 connected/HOT Workers and complete both wave-one witnesses.
2. Arm one Scenario-only String checkpoint. The complete wave-two String batch
   names target and backup as its finite candidate set and requires the target's
   current `labSlot`. Only the target initially matches; wait until its first
   Handler enters the checkpoint before killing the Host. This keeps unrelated
   String executions out of the process-loss boundary.
3. Kill Scenario Host, then require the 1,000-Worker world to become disconnected
   and leave serviceable HOT state.
4. While Host is down, change one backup Worker's `labSlot`; restart 999 Workers
   while excluding the original target.
5. Require all 999 identities to reconnect unchanged, then require the canonical
   backup Property and that explicitly targeted backup's HOT state. The proof
   does not require every recovered Worker to be simultaneously HOT while due
   work is present. Finish the original in-flight checkpoint witness, submit
   wave three and complete its two named witnesses.
6. Kill Host again and prove the checkpoint's successful Result remains
   observable through `results:load`. This is Result retention, not TaskItem
   Score finality. Fix `300 offered / 30 invalid`, not 300 successful Results.

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
Correctness directs one Item to each through an exact `workerId` target.
Convergence force-stops `lab2`,
requires `lab1` and `lab3` to remain serviceable, then
restarts `lab2` and requires its original worker ID. It does not generalize N,
repeat Server restart, or claim Android background survival. The disposable
Emulator disables cached-app freezing so platform process policy does not
invalidate this fixed topology.

The Android lane does not yet claim dynamic Properties re-Prepare across the
real Runtime boundary, Doze/OEM policy, multiple devices, Handler throughput or
an arbitrary number of Apps. Those are separate claims rather than missing
iterations of the fixed single-Worker and Triad scenarios.

## Capacity: 15,000 Identities / 10,000 Active Workers

```text
World       1 Group x 15,000 identities; 10,000 active after contraction
Topology    WebSocket, one Adapter, one Java 21 Host
Workload    four stages x ten same-Group Tasks x 5,000 Items
Mutation    loaded 5,000-run stop, one SIGTERM, then two SIGKILL; Host retained
Oracle      40 terminal Tasks + 200,000 exact exports,
            >= 9,900 active connected-and-HOT after drain, plus Linux resources
```

The shared Inventory materializer generates 150 JSONL files of 100 records.
The lane prepares all 15,000 identities, holds at least 14,800
connected-and-HOT for a stable window, and stops the final 5,000 coordinates
without deleting identity while ten fully seeded 5,000-Item Tasks are active.
Three later loaded stages cross one graceful and two hard Server restarts. Each
mutation is admitted only while all ten Tasks are nonterminal, 1..25,000 Items
have succeeded and at least 25,000 remain unresolved. During work the lane
requires the 9,900 connection threshold but not HOT outside an intentional
Server-down/reconnect interval; after each drain it re-establishes
connected-and-HOT and rejects connected, HOT or missing state for the stopped
baseline set. Hard recovery must expose backlog in its first Result observation
and then make new progress. No stage starts Workers or performs Prepare. The
lane records Task progress, three identity-set digests, RSS, CPU, native-thread and
file-descriptor evidence, including stable resource drift across all recoveries.

This is a resource/capacity claim. It does not repeat the 100-Worker correctness
oracle, inject the 1,000-Worker fault matrix, or claim Task fairness, completion
order, throughput or latency.

## Excluded Products

The scenarios deliberately do not multiply:

- WebSocket, Socket and Polling across every scale; protocol Owners and Runtime
  Boundary own topology witnesses.
- single/multi Group and single/multi Adapter across every fault; only the
  topology required by the claim is used.
- every chaos action against every workload mode; deterministic mutations are
  assigned to one health scenario.
- treating the 1,000-Worker convergence world as a resource or throughput
  result; Linux resource boundaries remain owned by the separate 10k lane.
- large TaskItem counts in Correctness or Convergence merely for scale theater;
  only the dedicated capacity lane owns the four 50,000-Item loaded operations.
