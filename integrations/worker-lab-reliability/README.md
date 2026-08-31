# Worker Lab Reliability Integration

`worker-lab-reliability` is an external Java 21 convergence harness for the
standalone Scenario Worker Lab. It owns no Worker, Server, Adapter, Kernel,
Redis, scheduling, or lifecycle implementation. It calls only the loopback Lab
control API and public Runtime APIs.

The proof permits execution order, failure timing, and selected Worker
identities to vary. It fixes these relationships instead:

```text
initial-workers=none
-> start two replicas in each fixed Group
-> observe Server identity plus Adapter connection
-> schedule one Worker stop
-> observe local stop, route disconnect, and recovery/cold scheduling state
-> explicitly restart the same identity
-> replace one stopped Worker's persistent Properties
-> restart and observe the refreshed Runtime snapshot
-> stop the selected Group and wait for every registered Worker to reach
   recovery/cold before creating its parked Task
-> keep that Group unavailable while the other Group completes a Task
-> restore the matching Worker and observe the parked Task complete
```

The Harness never treats Lab state as Adapter or Kernel truth. It relates the
Lab snapshot independently to Runtime Preview, Network observation, Scheduling
observation, and finite Task exports. It does not assert Command order,
intermediate scores, exact convergence latency, or which Worker executes an
Item.

## Run

Start Redis and the checked `scenario-workers` Server profile, then launch the
Scenario Host against an isolated Lab root:

```powershell
.\gradlew.bat :scenario_workers_jvm:runScenarioWorkers `
  --args="--runtime-api-base-url=http://127.0.0.1:18082 `
  --sandbox-root=D:\proof\data\scenario-workers `
  --control-port=18086 --initial-workers=none"
```

Run the external Harness:

```powershell
.\gradlew.bat :integrations:worker-lab-reliability:runWorkerLabReliability `
  --args="--proof-id=local-worker-lab `
  --runtime-api-base-url=http://127.0.0.1:18082 `
  --lab-control-base-url=http://127.0.0.1:18086 `
  --endpoint-manager-id=scenario-websocket `
  --evidence-dir=D:\proof\evidence"
```

Optional finite bounds are:

```text
--maximum-wait-millis=120000
--request-timeout-millis=10000
--scheduled-stop-delay-millis=1000
```

The evidence directory contains:

```text
worker-lab-reliability-summary.json
worker-lab-reliability-timeline.jsonl
```

The timeline records only control actions, identities, projected states, and
times. It never records capability input or result payload. The Harness
acquires control only after all 20 Workers are observed `STOPPED` and all four
baseline Properties documents are loaded. It finally cancels scheduled stops,
waits for its four controlled Workers to stop, restores any JSON Properties it
may have changed, and verifies the restored files. A failed precheck performs
no Lab mutation. CI additionally uses an isolated Lab root and exact Redis test
scope.

```text
./gradlew :integrations:worker-lab-reliability:test
```
