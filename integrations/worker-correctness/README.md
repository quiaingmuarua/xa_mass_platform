# Worker Correctness

This Java 21 Integration is the Primary Proof for XA Mass Worker vertical
correctness. It calls public Runtime and loopback Lab APIs and reads initial
inventory/evidence files; it
does not import Worker, Adapter, Server or Kernel implementations.

## Claim

The fixed world is:

```text
2 WorkerGroups x 50 Workers
one WebSocket Adapter
two managed items:call batches x 50 Items
eight rounds of live Properties replacement + 32 incremental updates
one graceful Scenario Host restart
```

The initial phase proves:

- exactly 100 checked Lab addresses map to 100 unique Server-issued worker IDs;
- Runtime Preview, Adapter Network, Properties observation and one Direct Call
  close over the same identities;
- each Group's three existing Event Names receive `17/17/16` Items;
- each batch response contains exactly the submitted message IDs and all 100
  statuses are `SUCCEEDED`.

The fixed phase order is `initial -> live-properties -> Host restart -> restart`.
The restart phase stops and restarts only Scenario Host while Server, Redis and
Lab files remain. All 100 Lab addresses must map to their original worker IDs
and re-establish the same live relationships.

Capability-specific Result payloads remain opaque here; their values belong to
Scenario capability Owner tests. The restart phase does not repeat the 100-Item
workload. This
lane does not claim fault convergence, throughput, executing Worker identity or
WebSocket/Socket/Polling topology breadth.

## Live Properties

The runner owns independent Server, Scenario Host and Harness processes and one
Redis scope. The Java Harness mutates only through loopback Lab HTTP:

```text
Integration -> Lab HTTP -> Scenario Host -> existing Worker SDK/connection
-> Adapter cache -> SYSTEM Report -> Server admission / Matching -> Runtime View
```

String Group record 1 is the target; record 2 in the same file and Phone Group
record 1 are controls. Initial evidence fixes their Server identities. Each of
eight rounds sends a full replacement baseline, then 32 consecutive PATCH
requests without observing Adapter or Server between those requests. Each
PATCH changes correlated fields and adds its own unique key. Adapter Properties
snapshot and Worker Runtime Preview must both reach the exact complete Map,
including every delta. Every observed target Map must equal a complete
submitted snapshot; intermediate states may be skipped, but hybrid fields and
lost keys fail. Replacements delete omitted fields; PATCH preserves untouched
fields, including empty strings. A final replacement retains only the immutable
Lab coordinates. There are 265 mutations: 9 replacements and 256 updates.

Each replacement and burst checkpoint has a five-second budget from sending
its last mutation request to independently observing the latest Map at both
surfaces. HTTP requests have a two-second maximum and Adapter Direct Calls wait
one second. The entire dynamic Harness process has a 120-second limit. Failed,
unaccepted or ambiguous mutations immediately fail; only temporary observation
transport errors, HTTP 429/502/503/504 or a Direct Call timeout permit polling
within the original deadline. These are fixed CI budgets, not production
latency SLAs or reliable SYSTEM delivery guarantees under faults.

Every checkpoint checks control file Properties, Adapter cache and Server
Properties against their initial baselines, and verifies Worker identity, local
run state, endpoint and connectivity. The runner also compares the controls'
physical JSONL record digests and verifies that the original Host PID remains
alive. No lifecycle operation is issued during this phase. A proof-only,
unbuffered, non-rotating Server HTTP access log contains only method, path and
status. It must first demonstrate successful initial Prepare traffic, then show
zero additional `workers:prepare` and `workers:prepare-batch` requests during
the dynamic phase, including failed attempts. The Harness evidence remains
`pending-runner-audit` until these independent checks pass.

## Run

Redis 7 must already be reachable. With Python 3.11 or newer, install the shared
proof dependency set once:

```powershell
python -m pip install -r .github/scripts/requirements.txt
```

The one-shot runner builds the Harness distribution and owns Server, Scenario Host, isolated Lab state,
a unique `test_*` scope and safe evidence. It explicitly materializes the
shared canonical 100-Worker Inventory rather than depending on Scenario default
seeding:

```powershell
python integrations/worker-correctness/run_worker_correctness.py `
  --redis-url redis://127.0.0.1:6379/15
```

Default output:

```text
build/worker-correctness-proof/
  evidence/worker-correctness-initial.json
  evidence/worker-correctness-live-properties.json
  evidence/worker-correctness-restart.json
  runtime-server.log
  scenario-host-initial.log
  scenario-host-restart.log
```

Evidence stores identities, relation sets, submitted/succeeded counts and Event
success counts. It does not store full Properties, Direct Call payloads or Task
Result payloads.

Live evidence additionally stores mutation/acceptance counts, snapshot digests,
two observation timings, control checks, process identity and Prepare request
deltas. CI uploads only these three named evidence files and the Server/Host
process logs. Lab files, the access log and private Harness stage logs remain
outside that whitelist. Any phase or runner audit failure fails Worker
Correctness and the selected Proof Gate. Output roots must be children of the
repository `build` directory.

Focused module tests:

```powershell
.\gradlew.bat :integrations:worker-correctness:test
python -m unittest discover -s integrations/worker-correctness -p 'test_*.py'
```

See [Proof Registry](../../doc/testing/proof-registry.md#worker_correctness)
for claim boundaries and [TESTING.md](../../TESTING.md) for lane selection.
