# Worker Correctness

This Java 21 Integration is the Primary Proof for XA Mass Worker vertical
correctness. It calls only public Runtime APIs and reads Scenario Lab files; it
does not import Worker, Adapter, Server or Kernel implementations.

## Claim

The fixed world is:

```text
2 WorkerGroups x 50 Workers
one WebSocket Adapter
two managed items:call batches x 50 Items
one graceful Scenario Host restart
```

The initial phase proves:

- exactly 100 checked Lab addresses map to 100 unique Server-issued worker IDs;
- Runtime Preview, Adapter Network, Properties observation and one Direct Call
  close over the same identities;
- each Group's three existing Event Names receive `17/17/16` Items;
- each batch response contains exactly the submitted message IDs and all 100
  statuses are `SUCCEEDED`.

The restart phase stops and restarts only Scenario Host while Server, Redis and
Lab files remain. All 100 Lab addresses must map to their original worker IDs
and re-establish the same live relationships.

Capability-specific Result payloads remain opaque here; their values belong to
Scenario capability Owner tests. The restart phase does not repeat the 100-Item
workload. This
lane does not claim fault convergence, throughput, executing Worker identity or
WebSocket/Socket/Polling topology breadth.

## Run

Redis 7 must already be reachable. With Python 3.11 or newer, install the shared
proof dependency set once:

```powershell
python -m pip install -r .github/scripts/requirements.txt
```

The one-shot runner builds and owns Server, Scenario Host, isolated Lab state,
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
  evidence/worker-correctness-restart.json
  runtime-server.log
  scenario-host-initial.log
  scenario-host-restart.log
```

Evidence stores identities, relation sets, submitted/succeeded counts and Event
success counts. It does not store full Properties, Direct Call payloads or Task
Result payloads.

Focused module tests:

```powershell
.\gradlew.bat :integrations:worker-correctness:test
```

See [Proof Registry](../../doc/testing/proof-registry.md#worker_correctness)
for claim boundaries and [TESTING.md](../../TESTING.md) for lane selection.
