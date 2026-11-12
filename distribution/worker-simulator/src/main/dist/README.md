# XA Mass Worker Simulator kit

This kit lets any machine act as an **external user** of an existing XA Mass Runtime,
without a repository checkout or a build:

1. `bin/xa-mass-worker-simulator` runs a Java Worker Host that connects to the Runtime's
   Worker entrance and executes the MD5 test event.
2. `acceptance/finite_round.py` runs one finite-Task round through the public API and
   checks every Result independently.

It is a test and acceptance capability, not a production Worker. It starts no Server and
changes no deployment. The Runtime operator provides a registered Worker Group, a Worker
gateway credential and your access key; nothing here registers Groups, enables Pools or
creates users.

Requirements: Java 21 and Python 3.9 or later. Use the kit from the **same Release** as the
Runtime you test (`manifest.json` holds its version and commit); other combinations are not
supported.

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

## Outcome

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

## Running rounds repeatedly

The kit has no scheduler. If you run rounds from your own scheduler, keep the rate low (for
example one round every 10 to 15 minutes) and only against a Runtime you are authorized to
use. Every round leaves one Task that stays until the Runtime's retention removes it.

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
