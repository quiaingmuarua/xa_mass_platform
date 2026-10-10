# XA Mass Console

Vue 3 console for Worker and Task runtime observation, a thin finite Task
file client, SMS business pages and public Demo. It is derived from Pure Admin Thin 6.2.0 and contains no login,
token, dynamic-permission, fake-user, or fabricated-user path.

## Requirements and local run

- Node 22.19 or newer, below Node 25
- pnpm 11.9 or newer, below pnpm 12

The checked `packageManager` and CI still execute pnpm 11.9.0; `engines` only
describes the supported local and hosting range.

- A [Server runtime](../distribution/server/README.md#source-launch) on `127.0.0.1:18082` for API mode

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled. Explicit Mock mode
is available through `pnpm dev:mock` and never activates as a fallback.
For live business pages, start [Scenario Preview](../distribution/server/PREVIEW.md#source-launch)
and set `VITE_RUNTIME_PROXY_TARGET=http://127.0.0.1:18500` before starting Vite.
The same `/api` proxy carries Runtime and business requests.

The Code Dictionary is a current-build projection, so generate it before a
local Vite session. The OpenAPI Reference is a committed build-time projection;
regenerate it whenever the public Server API changes:

```powershell
.\gradlew.bat :distribution:server:generatePlatformDiagnosticCodes
.\gradlew.bat :server_jvm:exportOpenApiSnapshot
Set-Location frontend
corepack pnpm dev
```

Vite serves only the exact
`/reference/platform-diagnostic-codes.json` path from that build output. It
returns `404` when the projection is missing and never exposes the surrounding
Gradle build directory. Mock mode does not fabricate a dictionary.

The public Vercel deployment is built with `pnpm build:demo` and therefore uses
that explicit Mock mode. It is a current UI and architecture demonstration, not
a hosted XA Mass Runtime: platform Task and Direct Debug mutations remain disabled,
and no request is proxied to a local or remote Server. Real Runtime data is
served only by the frontend bundled with the Server Runtime on the same origin.
The public `/api-reference` (and Vercel-only `/scalar` alias) renders the
committed `/reference/openapi.json` snapshot with request execution disabled.
The live Server continues to own `/scalar` and `/v3/api-docs`.

Routes:

```text
/runtime/workers
/runtime/tasks
/api-reference
/reference/error-codes
/sms
/sms/metrics
/messages
/messages/tasks/:taskId
/app-checks
/app-checks/tasks/:taskId
```

The same navigation is available in a drawer on narrow screens.
The sidebar also links to the current origin's `/scalar` and `/overview.htm`.
On Vercel, the SPA maps `/scalar` to the same static API Reference; on the Java
Server, its MVC Scalar route takes precedence and stays live. The OpenAPI
snapshot is committed at `frontend/public/reference/openapi.json`. The
dictionary JSON is available at
`/reference/platform-diagnostic-codes.json`. The overview source is
`frontend/public/overview.htm`; generated `dist` and dictionary content are not
committed.
Changes to the [human overview](public/overview.htm) preserve its existing
information architecture and navigation. Complete mechanism, API, capacity and
fixture contracts remain in their Owner and proof documents.

## Build ownership

`pnpm build` writes the unified console to `dist`; `pnpm build:demo` writes
`dist-demo` for public hosting. There is one dependency graph and theme. The
console owns navigation and page layout; Runtime configuration and Store lifetime
belong only to the Runtime route container. SMS and Reference pages do not depend
on Runtime configuration or stores.

## SMS business pages

`src/sms/` owns `/sms` and `/sms/metrics` under the shared Console. The
[SMS Owner](../scenarios/sms-reception-jvm/README.md) owns API and reception semantics.
One validated catalog observation controls enablement; failure/retry and public Mock
Demo gating stay independent of Runtime configuration. Theme uses the shared preference.

The workbench calls `numbers:lease`, shows phoneNumber/messageId/leaseUntil and reads
`messages/{messageId}`. The selected reception refreshes every second while active,
including after its first SMS; manual ID lookup/refresh remains available afterwards.
`NOT_OBSERVED` is not failure, and expiry retains the last SMS. At most 100 records
are kept in browser session storage; a Server run change does not discard their IDs.
There is no server-side order list, cancellation UI or call to the Host API.

Navigation retains local selection; leaving the workspace aborts requests and stops
polling without resubmitting. Public Mock Demo hides the entry, makes no SMS requests
and explains direct visits; a platform-only Server serves the shared page with the
scenario unavailable. Metrics report acquisition/query observations and bounded lease
projection counters rather than scanning all business orders.

## Messages business pages

`/messages` is the desktop Task workbench: one loaded list, one operation menu per
row and a 760px result preview drawer. `/messages?view=create` is a single creation
page. `/messages/tasks/:taskId` opens the same drawer over the list; adding
`?view=create` opens it over the retained draft. Opening/closing previews does not
reload the list or discard its filters, table position or focus. Browser history
and direct links use the same routes; no new Server forwarding path is needed.

The business list excludes managed Tasks without fetching replacements. It retains
ordinary Tasks with missing metadata and shows unavailable values. The newest
loaded window is capped at 100; state tabs, application/country/date filters and
counts only describe that window. Both running bands display as sending, while
unknown states appear only in All. Tables scroll within bounded areas, with no
pagination, infinite loading or automatic polling.

### Applications and API boundary

`MessageTaskSource` supplies application choices. API consumes the ordered
Catalog `applications` list; Preview and Mock provide Demo (`demo-sim`), App A
(`app-a-sim`) and App B (`app-b-sim`). A deployment may expose a subset; one choice
is read-only. Invalid/missing Catalog data blocks creation, never selecting Mock.

New creation explicitly serializes appId, requestId, name, recipientCountry,
senderCountry and body. It rejects unlisted applications before submission and
never sends senderPhone or a client workerGroupId. New Tasks use inputVersion=3;
labels require the saved appId and actual Group to agree with Catalog. Historical
Tasks without appId show their Group without inventing an application identity.
Old senderPhone is read-only; non-v3 Tasks support preview/closure, not import or
approval. API and Mock use the same version boundary.

### Creation, import and management

Names are automatic, include application and sender/recipient country and time,
and never depend on imported count. Applications/countries and optional recipients
and Template content are the ordinary inputs. Template content is submitted
unchanged as `body`; the frontend checks only nonblank text and the existing
4096-character bound, without interpreting JSON or expanding template variables.
New drafts start with empty content. The generic hint states that the selected
application's Worker interprets the unchanged text. Lab JSON guidance is folded
and explicitly applies only to Workers configured as `lab-json`; there is no
frontend JSON parser or automatic receipt behavior inferred from the body.
Preview defaults to text sending. Creation acceptance is still separate from
Worker execution and external delivery. Mock examples use ordinary text and
later receipt progression remains an explicit demonstration action.

Empty creation and create-then-import both stop in review. UTF-8 TXT/paste uses a
browser worker, optional `+` normalization, duplicate skipping, country-prefix
validation and whole-input rejection on errors. The Catalog limits remain
100,000 unique recipients / 10 MiB per import. No CSV or number-detail table is
added. Independent import reuses the same editor. Only the latest confirmed import
receipt/source summary is retained in the console session, never persisted.

Request identity, application, generated name and wire payload freeze at first
submission. A definitive creation rejection before any known Task unlocks the
same draft for correction, retaining recipients, file source and the unused request
identity. Identity conflicts keep their original request for explicit resolution.
Creation uncertainty requires explicit reconciliation with the original
request; reconciliation never auto-imports or approves. A rejected reconciliation
keeps the earlier uncertainty and frozen input. Once a Task ID is known, import
failure cannot create another Task. Explicit re-import and refreshed actual
count recover the existing Task. Ending an attempt clears local input only. Drafts
survive local navigation within the console session; reload loses them. Numbers
are never stored in localStorage or other persistent browser storage.

Review menus offer import, count-confirmed approval and cancellation. Running menus
offer preview/closure; ended and unknown Tasks offer preview. Old input versions
retain their existing restrictions, and managed or incomplete business Tasks cannot
be mutated. Every management entry reads a fresh Task first; approval confirms that
observed count. Conflict requires a new read/confirmation. Closure preserves partial
progress and later receipts. Busy/stale state disables mutation, with independent
list/drawer reads and generation/focus guards against late responses.

### Preview and Mock evidence

Review Tasks show actual recipient count and read-only Template content directly;
empty execution metrics are hidden. Approval also displays the latest retrieved
template unchanged as text, with bounded keyboard-accessible scrolling for long
content. Other Tasks show at most 100
produced Results with preview-local number/status filters, explicit truncation and
matching counts. Whole-Task counts come from the source, never those rows. Sent,
delivered, read and replied are cumulative stages, not disjoint quantities. Terminal
scheduling is explicitly separate from later receipt updates; replies show the
latest content, without fabricating a reply history. Failures, malformed content,
missing business answers and empty previews remain distinct.

Task/Worker identities, actual sender phone and original configuration are collapsed
technical details. Missing import history is stated as unavailable. No operation
history, Messages export, pause/resume, copy-configuration or mobile redesign is
introduced. Read failures retain the previous snapshot/time; first failure offers
retry. Only initial reads, explicit actions and manual refresh make API requests.

Mock uses the same views and bounded windows with zero platform, product, Lab or
export requests. Its three application bindings are immutable per created Task and
part of request comparison. Explicit controls in the technical area advance sending,
finish scheduling, deliver/read/reply after terminality or fail the next read.
Refreshing itself never advances simulation, and no background timer is installed.

```powershell
cd frontend
pnpm dev:mock --host 127.0.0.1 --port 18501
```

Backend Group binding, HTTP contracts, input version and delivery capabilities are
unchanged. Real multi-application assembly and environment migration are deferred;
Mock behavior must not be reported as proof of those capabilities.

## App Checks Task workspace

The App Checks workbench is the first business Task workflow sample. It keeps
the existing Console theme/navigation and does not change the Tasks, Messages
or SMS workspaces. Its Catalog probe independently controls availability:
404 means disabled; transient errors allow explicit retry.

The desktop workspace uses a Task list, one action menu per row and a 760px
result-preview drawer. `/app-checks/tasks/:taskId` opens that drawer over the
list; there is no separate detail workspace. Full-page creation remains
`/app-checks?view=create`, so existing Boot page forwarding is sufficient.
Known Task links in submission recovery add `?view=create` to the Task path,
keeping the draft mounted behind the drawer. Opening, closing and navigating
between previews retain the background, filters, scroll position and draft,
without reloading the list. Closing restores the trigger's focus; closing a
direct Task link returns to the list. A Task read by known ID is not appended to
the loaded collection or counted in its state tabs. The Console context retains
drafts and filters for the current session; refreshing the application clears
them. Phone inputs are not persisted to browser storage.

### List and creation

The list shows finite business Tasks from the existing bounded 100-Task response.
Search, App/country/date filters and state-tab counts operate only on
this loaded set, never the whole Project. The table has bounded scrolling, a fixed
header and a fixed action column. Truncated reads are marked explicitly; scrolling
never loads additional Tasks. The header shows the last successful read time;
failed refreshes retain that time and mark the snapshot stale. Refresh replaces the snapshot. There are no page
controls, cursors or history traversal. Internal managed Tasks and entries
without business metadata remain outside this workspace. State tabs show all,
pending review, processing and ended Tasks; unavailable state belongs only to
all. Dates sit in collapsed advanced filters, with an active-filter marker.
List and drawer share one action menu: pending review can start or cancel,
processing can preview or stop, and ended Tasks can preview or export. Unknown
state offers preview only. Clicking a Task name opens the same preview.
Pending-review Tasks also allow additional number imports through the same form. Startup, cancellation and stopping require confirmation with App, country and
number count. In-flight actions reject duplicate submission; failures retain
the observed state. Unsupported API operations show their unavailable reason.

Creation is one page containing App, country and number import, with one submit
action. Names are generated by the server at first creation without an input-count dependency; there is no editable name or separate review step. Mock startup
confirmation remains a separate action after creation. Successful creation
returns to the list, refreshes its bounded snapshot and preserves filters. A
visible new Task is highlighted; a dismissible success notice also links its
preview when the current filters or read range exclude it.
UTF-8 TXT, CSV and pasted lines share existing number/country validation. CSV
supports BOM, escaped quotes and quoted newlines, with explicit header/column
selection. A dedicated module Web Worker performs parsing and validation; new
inputs and leaving the page cancel old work. Switching the input-method tab does
not replace the current numbers: the source label always names the actual file or
pasted content. Reading/revalidating replacements blocks submission; invalid input
never submits a valid subset. Creation displays only counts and
a concise error, with no number-detail table or problem-file export.

Empty lines and surrounding whitespace are removed, and duplicate numbers are
automatically collapsed. A leading '+' is optional in App Checks input:
plain country-code digits and their '+'-prefixed form deduplicate to one number.
Validation and submission retain the canonical '+'-prefixed string used by the
existing JSON API and Worker protocol; no country code is guessed or added.
Templates and input examples omit '+'. The summary shows read count, duplicates and final
submission count. Any invalid format or country-prefix mismatch rejects the
whole batch with an error count and first physical error line; no valid subset
is submitted. Users correct and re-import the source. The server still performs
its own admission validation. Original input coordinates remain available only
during validation; accepted Mock import history stores the source file and summary,
without copying raw rows or retaining an input-detail browsing path.
Rejected file replacement preserves the previous content, which must be
revalidated before use.

API and Mock now share independent creation/import/approval boundaries. The
create request contains requestId, App, country and the existing simulation
configuration, with no name or numbers. Simulation remains hidden from the
business form. The UI creates once, retains the returned Task ID, then uploads a
normalized UTF-8 number file (`text/plain`) for server validation and bounded
append. Catalog supplies the per-import 100,000-number/10 MiB limits. A failed
import never creates another Task. Pending-review menus allow additional imports;
existing numbers are skipped and older input-version Tasks explain why import is
unavailable. Starting sends the quantity shown in the confirmation dialog; the
server rejects a changed/empty quantity and active imports.

Unknown creation freezes its original request identity and known Task link.
Unknown import retains the target Task and offers explicit re-import of the same
numbers; neither action retries automatically. The recovery panel reads the known
Task independently. Users may close the local draft after acknowledgement;
association records retain only request/Task identities and input summaries for
the current session, never another phone-list copy. A later draft starts empty.
Successful import receipts and source summaries are likewise session-only; after
refresh the UI reports unavailable history rather than reconstructing it from
execution counts. Shared Messages and finite Task file limits are unchanged.

When the API returns `inputUnavailableReason` for a Task using retired supply,
the import and approval menu entries show that reason and stay disabled.
Preview, closure and terminal export remain available. The UI does not infer
supply compatibility from the number input version. Pool settings are not editable here.

### Preview drawer, management and results

The drawer shows identity, observed state, last successful read time and the
shared action menu, with preview itself omitted. Progress stays in the list row.
Pending-review Tasks initially show the import summary; other Tasks show result
previews. Import summary, activity and technical information are expandable
sections, with auxiliary data read only when expanded. Task state, Item Score counts and Result content remain
independent. Registered and unregistered answers are both successful execution;
failures have no registration answer. Invalid content and missing answers have
their own result filters. Closing a Task does not force progress to 100%.

Entry/manual refresh reads snapshots; no automatic polling is added. Failed
refreshes preserve known data, visibly mark it stale and disable state mutations.
Navigation invalidates late detail/import/verification responses and prevents old
detail reads from stealing focus in the newly opened Task. Mock and API
both show at most 100 Results returned by the current `loadTask` snapshot in a
bounded scrolling table. Result filters are computed locally from that snapshot;
there is no separate result-query method or pagination. The UI shows preview and
matched counts beside the result filters, honors the truncation flag, and scopes empty searches to the
preview. Task progress and counts still come from Task observations, independently
of preview filters. Refresh replaces, rather than accumulates, Results.

Import information contains only the source file, read count, duplicate count and
submitted count. Missing summaries are unavailable and are never reconstructed
from Task counts or Results. API exposes no extrapolated whole-Task registration
totals or fabricated import/activity history. Known creation time remains visible.

Technical information retains Task/Worker identities, simulation, salt and manual
Web Crypto SHA-256/BigInt verification of up to 100 snapshot Results. It compares
business answers, Group and configured delay, not scheduling or attempts. Missing
evidence is unavailable; verification is invalidated by refresh. There is no
remote hashing fallback.

### Explicit Mock product prototype

Mock supports 100,000 numbers/10 MiB, separate creation and startup confirmation,
cancellation before startup, and stopping after startup. Stopping preserves
results and unfinished counts; only Mock metadata supplies completion,
cancellation and stop reasons. Terminal Tasks cannot restart. There is no
pause/resume or configuration-copy capability.

Demo controls live inside the drawer's collapsed technical section: advance a
batch, finish remaining queries, and observe one previously started execution
only for stopped Tasks with unfinished Items. Nothing progresses on a
timer. The task list and result preview use the same 100-record read bounds as
API. Complete local results remain private to demo execution and export. Recent
actual Mock operations (up to 100) and available import summaries appear on the
expanded drawer sections without continuation.

Terminal-only CSV exports include all valid successes, registered successes or
unregistered successes. The dialog reads complete local counts, generates a
file and exposes its download link. Export excludes failures, invalid content
and missing answers, uses the complete fixture independently of preview size,
search or filters, and protects spreadsheet text cells.

The API source connects approval, closure and CSV export to the App Checks
application endpoints. Export has no separate full-count request: its dialog
shows that the count will be known after generation, then reads X-Export-Count
from the completed download response. It does not derive counts from preview or
Task Score. API imports use server-validated counts in their receipt; original
CSV line/duplicate counts are explicitly local input summaries. API ending reasons
and persisted operation history remain unavailable. Mock alone supplies demo
controls, concrete ending reasons and local operation records; its whole workflow
still makes zero service requests. Neither mode falls back to the other.

Persistent import/activity history and explicit backend ending reasons remain
follow-up work. Historical browsing can be reconsidered with Storage DB; this
iteration adds no full-query or continuation interfaces.

## API Reference

The API Reference uses the official Scalar Vue component in a top-level lazy
route, outside the Runtime Viewer layout. It loads only
`/reference/openapi.json`, hides request execution and developer tools, and
does not load with the Worker Runtime first screen. The snapshot is a checked
documentation projection rather than Runtime truth: use a running Server's
`/scalar` when the current live schema or request debugger is required.

## Diagnostic Code Dictionary

The page validates schema v1 with Zod and then searches the current Server,
Netty Adapter and Worker Core enum projection by code, symbol, meaning or
owner. Owner namespaces remain independent, so the same number may appear on
multiple rows. This reference does not bind an API operation to a code and is
not a cross-version compatibility promise. Loading, missing-file,
schema-incompatible and empty-search states remain explicit; no Pinia or
Runtime truth is created.

## Worker observation

The Worker page first obtains a bounded, unstable WorkerGroup preview and then
loads Workers only for the selected Group. Adapter Network and Kernel Worker
Score remain separate observation axes:

```text
connected != bound != schedulable != executing
```

Worker and Task preview limits default to 100 and can be set to `1..1000`
before refreshing. The choice remains in the current browser session. A Worker
sample larger than 100 is observed in sequential batches of at most 100 per
status axis; Network and Scheduling still progress independently. WorkerGroup
Preview keeps its separate 100-Group request.

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

In API mode, `Project Tasks` loads a caller-entered Project on explicit request
through `GET /api/v1/projects/{projectId}`, then reads its newest Task window through
`GET /api/v1/projects/{projectId}/tasks?limit=100`. It preserves missing Task/Score
projections and displays `truncated`; it has no pagination or total. This read does
not provision resources or substitute the global Score Preview when it fails.
The selected Project supplies the finite workbench's allowed Groups. Mock mode
does not query Projects. The [Server Project contract](../server_jvm/README.md#profile-projects-and-managed-tasks)
owns admission and the independent projections returned by these reads.

`Task Runtime Preview` reads the highest `1..1000` Task Score coordinates and
displays their Task and WorkerGroup descriptor projections in Owner order. It
has no total, cursor, paging, stable-window or completeness meaning. The page
shows only `Awaiting Review`, `Running Initial`, `Running Visible` and `Closed`;
it never receives raw Score. `Running Initial` is a fixed Kernel sorting
coordinate rather than a wall-clock deadline, and `Running Visible` does not
prove a Task is executing. Search filters only the current browser window and
does not issue another API request. Descriptor gaps remain visible and are never
repaired or inferred by the browser.

In API mode, each readable `PARK_WHEN_IDLE` Task with a
WorkerGroup descriptor exposes a single-Item `Task Call Debug` action. The
debug composer
accepts an advisory Event Name, a JSON Object Payload, and an Item-level
`workerSelector: {executorName, input}`. For `worker.any`, input must be `{}`; the Group must explicitly enable any/worker.any
and a Task must supply that shared Pool. `workerId` takes one string without Pool
demand; `worker.country` takes a country list or `{}` within its enabled Country Pool.
The composer shows an explicit sample and never infers a function from Task supply. The browser validates the
envelope and JSON bounds: non-null root, at most 100 members per container,
container depth 8 and 64 KiB of serialized input. Matching owns local parameter
semantics, normalization and Group enablement. Calls go
through Kernel scheduling; the browser does not query the index or infer
which Worker matched. Each Task retains at most 20 diagnostic exchanges in the
current Pinia/browser memory. A `not_observed` response means submission was
accepted without a Result in the bounded wait window, so the user may manually
load that Message ID later; there is no automatic polling. Both `items:call`
and manual `results:load` may instead return `failed`, which is shown as a
terminal Item Result without payload or failure reason. Browser refresh clears
this history, and Mock mode never fabricates Task Call results.
Both endpoints decode the direct Message-ID-keyed Result Map; manual
`results:load` sends the direct Message-ID array. Finite Task append likewise
sends a direct Item array and reads the direct outcome Map.

`Finite Task Workbench` is a drawer layered over the preview and is available
only in API mode:

1. Load the configured Project through `Project Tasks`.
2. Validate a local UTF-8 `.txt` file (non-empty, at most 1 MiB and 10,000
   lines).
3. Lazily load the bounded WorkerGroup Preview, then select one of the Project's
   Groups, an advisory Event Name, and Payload key.
4. Create one ordinary finite Task through `POST /api/v1/tasks`, including the
   selected `projectId`.
5. Convert each line into one standard TaskItem and append chunks of at most
   100 Items. The direct Message-ID-keyed response uses shared action outcomes:
   accepted Items are `applied`, and a locally rejected Item carries
   `rejected + code/message`.
6. Require explicit approval before calling the Task approve endpoint.
7. Export successful Results manually through
   `POST /api/v1/tasks/{taskId}/results:export`; `400/12010` is shown as not
   ready and never triggers automatic polling. The request has no terminal
   wait budget or JSON body.

Create/append, approve and successful export each request a fresh Task Runtime
Preview, but failure to refresh never rolls back the completed write. The
browser records only confirmed stages: `Created`, `Items Appended`,
`Approved`, and `Export Ready`. It never simulates `RUNNING` or `TERMINAL` from
elapsed time. Append failure stops the flow before approval. Local file metadata
and confirmed workbench stages live only in the current browser session and are
lost on refresh. Persisted Tasks remain queryable through `Project Tasks`; that
bounded window does not reconstruct the imported file or resume its submission.
Mock mode disables the mutating flow and sends no Task request.

## Verification

Lazy business pages import product-specific Element Plus components themselves.
Messages console tests register only the production shell's component set and
submit through the visible button, so global test registration cannot hide a
missing production form or pagination component.

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm build:demo
```

The frontend owns no Task, Worker identity, scheduling, result, or file-storage
truth. It only composes public Runtime API calls and downloads the response
stream selected by the user.
