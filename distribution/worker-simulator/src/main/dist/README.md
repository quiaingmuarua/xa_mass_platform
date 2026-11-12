# XA Mass Worker Simulator kit

This kit lets any machine act as an **external user** of an existing XA Mass Runtime,
without a repository checkout or a build:

1. `bin/xa-mass-worker-simulator` runs a Java Worker Host that connects to the Runtime's
   Worker entrance and executes the MD5 test event.
2. `acceptance/finite_round.py` runs one finite-Task round through the public API and
   checks every Result independently.
3. `acceptance/run_loaded.py` runs an explicitly requested external capacity campaign:
   15,000 Workers, then 10,000 retained Workers, with 50,000 Items measured at each scale.

It is a test and acceptance capability, not a production Worker. It starts no Server and
changes no deployment. The Runtime operator provides a registered Worker Group, a Worker
gateway credential and your access key; nothing here registers Groups, enables Pools or
creates users.

The Host and small finite round require Java 21 and Python 3.9 or later. The loaded
entry requires Linux, Java 21 and Python 3.11+ (standard library only). Use the kit
from the **same Release** as the Runtime you test (`manifest.json` schema 2 holds
its version, commit and three entries); other combinations are not supported.
The kit includes the compiled loaded Harness; consumers do not run Gradle or build source.

## 1. Start a Worker Host

Copy `examples/remote-host.json` to your own `host.json`. Set `runtimeApiBaseUrl` to the
Runtime's Worker address and the Group to the one you were given. Put the Worker credential
in `private/worker-access.json` as `{"keyId": "...", "secret": "..."}`, readable only by you.
The path is resolved next to `host.json`.

```sh
bin/xa-mass-worker-simulator --config host.json
```

On Windows use `bin\xa-mass-worker-simulator.bat`. The Host prints a
`WORKER_SIMULATOR_READY` line once started. It keeps its Worker inventory under
`data/scenario-workers` next to `host.json`; keep that directory so that restarts reuse the
same Worker identities. Run the Host under your own supervisor. A changed credential takes
effect only after an explicit restart.

## 2. Run one round

```sh
python3 acceptance/finite_round.py --runtime-url https://console.example.com \
  --access-key-file private/access-key.txt --group your-java-workers
```

| Option | Meaning |
| --- | --- |
| `--runtime-url` | Console/API origin. `https` is required except for a loopback Runtime |
| `--access-key-file` | File holding exactly one `xak_` access key |
| `--group` | The Worker Group your Host serves |
| `--project` | Project to create the Task in (default `default`) |
| `--items` | Items per round, 1..100 (default 3) |
| `--timeout-seconds` | Wait from approval to terminal state, 1..3600 (default 300) |

A round creates one raw finite Task, appends the Items, checks the saved descriptor,
approves it, waits until it is terminal, then checks every MD5 Result, the Item states,
the Result preview and the JSONL export. It never resubmits, closes or deletes a Task; the
Runtime's own retention removes finished Tasks later.

### Small-round outcome

stdout is exactly one JSON line:

| Field | Meaning |
| --- | --- |
| `outcome` | `passed`, `failed` or `unconfirmed` |
| `phase` | Where the round stopped: `create`, `append`, `descriptor`, `approve`, `wait`, `verify`, `export` or `done` |
| `requestId`, `taskId` | The round's creation request and Task, for later lookup |
| `httpStatus`, `errorCode`, `serverRequestId` | Present when the Runtime answered with an error |
| `reason` | Short text without any payload or Result content |
| `durationsMillis` | `approveToTerminal` and `total` |
| `kitVersion`, `runtimeUrl`, `startedAt`, `exportSha256` | Identification of the round |

| Exit code | Meaning |
| --- | --- |
| 0 | `passed` |
| 1 | `failed`: the Runtime rejected a request, an Item failed or a check did not match |
| 2 | Configuration error (no JSON line) |
| 3 | `unconfirmed`: a response was lost, the Runtime returned a server error, or the Task did not finish in time. The outcome is unknown, not negative |

### Running small rounds repeatedly

The kit has no scheduler. If you run rounds from your own scheduler, keep the rate low (for
example one round every 10 to 15 minutes) and only against a Runtime you are authorized to
use. Every round leaves one Task that stays until the Runtime's retention removes it.

## 3. Run an external capacity campaign

Use this only against an environment explicitly prepared for the campaign. The
Runtime operator supplies a dedicated Group, its MD5 event, `any` / `worker.any`,
Project permission and gateway admission. Stop any other Host owning the same
Group/inventory coordinates first; choosing another local directory does not
create distinct Worker identities. The tool starts its own Host and Harness, so
do not also start the Host command from section 1 for this Group. Exclusive Host
ownership is an operating precondition, not something the local inventory proves.
Before starting Workers the Harness point-checks the requested Group's registered
event, Pool/query and Project association; it does not create missing resources.

Before starting, arrange `nofile >= 65536` and at least 45,000 ephemeral ports on
the consumer. Runtime/gateway capacity is a separate operator responsibility;
the tool does not change either host. The version-scoped
[4.2 deployment contract](https://github.com/quiaingmuarua/xa_mass_platform/blob/main/distribution/server/RELEASE-4.2.md)
lists the initial resource budget and operating record.

```sh
python3 acceptance/run_loaded.py \
  --runtime-url https://console.example.com \
  --worker-runtime-url https://workers.example.com \
  --project your-project --group your-java-workers \
  --endpoint-manager runtime-websocket \
  --access-key-file /private/operator-access-key.txt \
  --gateway-credential-file /private/worker-access.json \
  --output-dir /data/loaded-run-001
```

All eight options are required. The two Runtime values must be HTTPS origins;
TLS and hostname checks stay enabled. Use a Project-granted operator access key,
not a Server administrator key. No SSH credential or Redis address is accepted.
The output directory must be new and outside the extracted kit.

The normal command fixes the full workload: prepare 15,000 identities, require
at least 14,800 stable connections, execute/export ten Tasks of 5,000 Items, stop
the final 5,000 local runs, require at least 9,900 retained connections, then
execute/export another ten Tasks of 5,000 Items. The same Host and retained
identities are used throughout. The two scales are reported separately, with
independent MD5 checks and complete terminal exports. This command never restarts
the Server or injects a Server fault.

`elapsedMillis` starts before the first approval and ends when all ten Tasks are
observed terminal; `observedItemsPerSecond` divides the verified Item count by
that interval. Preloading and subsequent Item/export verification are excluded.
Observed completion rates include approval, network, Worker execution and observation lag;
they are not maximum Server TPS or evidence that every Worker performed work.
Changing a count or timing requires `--diagnostic`; successful reduced fixtures
report `diagnostic-passed` / `diagnostic-only`, not the full capacity claim. The
original small-round `--items` range remains 1..100 and is not expanded.

The entry emits a short JSON status and writes `summary.json`, consumer-only
`process-resources.jsonl`, plus Java `evidence/summary.json`, `timeline.jsonl` and
`receipts.jsonl`. Full results are `passed`,
`failed` or `unconfirmed`; known Task/request identities remain available for
manual follow-up. It does not replay uncertain writes, delete Tasks or clean a
remote scope. On failure it point-checks only Tasks whose creation was confirmed
and makes one close attempt for each still-nonterminal Task; the cleanup outcome
is recorded without retrying uncertainty. It stops only its own Host/Harness and
removes temporary credential copies. Keep child logs, inventory and
`evidence/private/` identities out of shared evidence.
Forced termination, cancellation or an outer runner failure may bypass the
Harness's normal Task cleanup. Remote cleanup then remains unconfirmed; use the
retained receipts and known Task identities for explicit follow-up. A stopped
local process is not evidence that its remote Tasks are closed.
Remote Tasks remain until the Runtime's normal retention removes them; remote
Server/Redis/gateway resource evidence must be collected separately by its operator.

The existing loaded-recovery workflow can run this command with manual
`external-release` mode on `main` through its protected `release-preview-load`
Environment. Its scheduled/default `local-proof` mode remains the existing
four-stage local recovery proof. Public campaigns are not ordinary PR gates.

## Reporting a problem

Report findings as a GitHub issue in
[quiaingmuarua/xa_mass_platform](https://github.com/quiaingmuarua/xa_mass_platform/issues)
using the "Preview finding" form. Include:

- the kit version;
- the JSON lines of the affected rounds;
- when it started and how often it happens;
- whether your Host was running.

Never paste access keys, Worker secrets, `Authorization` values, or Item and Result content.

## Credentials

Keep both key files private and out of shell history, environment variables, logs and
shared folders. The round sends the access key only to the `--runtime-url` origin and does
not follow redirects. The Host sends the Worker credential only to its configured HTTPS/WSS
authority. Details are in the
[Worker Simulator documentation](https://github.com/quiaingmuarua/xa_mass_platform/blob/main/worker_simulator_jvm/README.md#protected-remote-runtime).
