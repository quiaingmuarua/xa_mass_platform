# XA Mass Runtime Viewer

Vue 3 frontend for Worker and Task runtime observation plus a thin finite Task
file client. It is derived from Pure Admin Thin 6.2.0 and contains no login,
token, dynamic-permission, fake-user, or example-business path.

## Requirements and local run

- Node 22.19.x
- pnpm 11.9.0
- `server_jvm` on `127.0.0.1:18082` for API mode

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled. Explicit Mock mode
is available through `pnpm dev:mock` and never activates as a fallback.

Routes:

```text
/runtime/workers
/runtime/tasks
```

The sidebar also links to `/scalar` and `/overview.htm`. The overview source is
`frontend/public/overview.htm`; generated `dist` content is not committed.

## Worker observation

The Worker page first obtains a bounded, unstable WorkerGroup preview and then
loads Workers only for the selected Group. Adapter Network and Kernel Worker
Score remain separate observation axes:

```text
connected != bound != schedulable != executing
```

Network values are `connected`, `disconnected`, or `unknown`. Scheduling values
are bounded projections such as `hot-score-overdue`, `held-hot`, `paused`,
`recovery`, `cold`, or `missing`; the browser never receives raw Score. Each
axis refreshes independently and preserves only its own last successful value
as stale evidence. Neither Group nor Worker preview promises totals,
completeness, stable ordering, history, or complete matching.

The Worker table and detail drawer expose a single-target `Direct Debug` action
in API mode. Its searchable Event selector opens the current WorkerGroup Event
catalog while still accepting a custom full Event Name; the catalog remains an
input suggestion rather than an authorization list. Requests and responses are
shown as a compact chat and the latest 20 calls per Worker live only in the
current Pinia/browser memory. Closing the drawer or changing routes preserves
that diagnostic history, while a browser refresh clears it; no browser or
Server storage is used. Direct Debug remains best-effort, bypasses Kernel
scheduling, creates no TaskItem or Worker lease, and never proves that a Worker
is schedulable or executing. Mock mode disables this mutating action and does
not fabricate a response.

## Task page

`Configured Tasks` remains a read-only projection of Profile-owned long-lived
Tasks. `Finite Tasks` is a real API flow available only in API mode:

1. Validate a local UTF-8 `.txt` file (non-empty, at most 1 MiB and 10,000
   lines).
2. Select a configured WorkerGroup, advisory Event Name, and Payload key.
3. Create one ordinary finite Task through `POST /api/v1/tasks`.
4. Convert each line into one standard TaskItem and append chunks of at most
   100 Items.
5. Require explicit approval before calling the Task approve endpoint.
6. Export successful Results manually through
   `POST /api/v1/tasks/{taskId}/results:export`; `400/12010` is shown as not
   ready and never triggers automatic polling.

The browser records only confirmed stages: `Created`, `Items Appended`,
`Approved`, and `Export Ready`. It never simulates `RUNNING` or `TERMINAL` from
elapsed time. Append failure stops the flow before approval. Ordinary finite
Task records live only in the current browser session, so a refresh cannot
rediscover them until a future Task list/query API exists. Mock mode disables
the mutating flow and sends no Task request.

## Verification

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

The frontend owns no Task, Worker identity, scheduling, result, or file-storage
truth. It only composes public Runtime API calls and downloads the response
stream selected by the user.
