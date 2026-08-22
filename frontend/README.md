# XA Mass Runtime Viewer

Vue 3 frontend for the read-only Worker and configured Task Runtime views plus
the Task Batch Lab and a browser-session finite Task interaction Mock. It is
derived from Pure Admin Thin 6.2.0 but deliberately
contains no login, token, dynamic-permission, fake-user, or example-business
path.

## Requirements

- Node 22.19.x
- pnpm 11.9.0
- `server_jvm` on `127.0.0.1:18082` for API mode

## Local run

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled. The Worker page
first obtains a bounded, unstable WorkerGroup preview and then obtains Workers
only for the selected Group. Long-lived Task coordinates and the Task Batch
selection catalog continue to come from the Server's Profile-configured
resource directory; neither path uses a frontend environment list.

Routes:

```text
/runtime/workers
/runtime/tasks
/runtime/task-batches
```

The sidebar also links to Server-hosted reference pages:

```text
/scalar
/overview.htm
```

`overview.htm` is maintained as the static source at
`frontend/public/overview.htm` and is copied to the frontend build root. It is
the human-facing projection of the repository
[architecture entrypoint](../README.md), not an independent mechanism
truth source. Generated `frontend/dist/overview.htm` is never committed.

The Task Batch page is available only in API mode with the real
`scenario-workers` Profile. It selects a configured WorkerGroup and advisory
EventCode, accepts one Payload key and `.txt` file, uploads it, runs the batch,
and offers the published JSONL as a manual download. The table is
browser-session memory only. Server input and output files remain under the
Lab directory after a page refresh.

The default tab at `/runtime/tasks` is an explicitly labelled finite Task Mock.
It models `PRECOMPUTED_TASK_RULE`, `CLOSE_WHEN_IDLE`, local TXT Seed review,
explicit approval, Admission/Dispatch visibility, idle close, and Mock JSONL
download entirely in browser memory. It sends no Task lifecycle or result HTTP
requests and disappears on page refresh. The adjacent `Configured Tasks`
tab remains the read-only directory of Profile-provided long-lived Tasks.

Use the fixed Mock data only when explicitly requested:

```text
pnpm dev:mock
```

Mock mode never activates as a fallback after a real API failure. It does not
simulate batch execution and never calls the Task Batch API.

## Worker status observations

The Worker page presents Adapter Network and Kernel Worker Score as two separate
observation axes. In API mode, Adapter Network groups the current Worker sample
by `endpointManagerId` and calls the bounded Adapter-scoped Runtime View
network-observation endpoint. Server reuses the existing Adapter DIRECT_CALL
correlation to execute `platform.adapter.worker-connections.snapshot`; the
browser receives only `connected`, `disconnected`, or `unknown` and never
parses an opaque Direct Call result. Kernel Worker Score comes from the bounded
Runtime View scheduling-observation endpoint. Java `WorkerSchedulingService`
uses the existing batch `WorkerScoreCore.getScoreStates` read and returns only
`hot-score-overdue`, `held-hot`, `paused`, `recovery`, `cold`, or `missing`, so
the browser never receives or decodes raw Score. `hot-score-overdue` is only a
past positive Score projection, not the Kernel's floor-aware candidate range.
In explicit Mock mode, both axes
remain deterministic Mock data and are labelled `MOCK`.

Network and Scheduling observations load independently after a Worker sample
appears. They can be refreshed without resampling Workers, and one failed axis
does not turn into a state on the other axis. A failed refresh keeps the last
successful value only as `Stale`. There is no combined online state and no
connection control:

```text
connected != bound != schedulable != executing
```

`WorkerStatusDataSource` keeps the axes independent. API-mode Network uses one
parallel request per Adapter represented in the current `1..100` Worker sample;
API-mode Scheduling uses one request for the whole sample. Both restore request
order and fail closed on identity or schema drift. A failed refresh keeps its
last successful value only as `Stale`; neither axis falls back to Mock data.

## Verification

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

Both the WorkerGroup tabs and the selected Group's Workers remain unstable,
incomplete samples. Refreshing the Group sample does not resample cached
Workers for Groups that remain visible; refreshing the Worker sample never
reloads the configured Task directory. The Mock Scheduling axis may label a
semantic lease projection, but it does not expose raw Score or prove execution.
The Task page keeps real Profile descriptors read-only and
separates them from the finite Task interaction Mock; Mock `Dispatch Visible`
is not an execution claim. Neither Runtime page infers Worker totals, a
combined online state, transport sessions, history, or complete matching
results. The Task Batch page mutates only through the public Lab API: it does
not create or expose Tasks, Worker identity, or scheduling truth.
