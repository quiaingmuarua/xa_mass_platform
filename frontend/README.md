# XA Mass Console

The Scenario product name is Batch Checks／批量检查. Its `app-checks` routes,
API identifiers and source directory remain technical names; current Group bindings
are deployment-owned configuration and do not replace saved Task execution values.

Vue 3 console for Worker and Task runtime observation, a Project-scoped task
workbench (RPC calls and finite Tasks), Scenario pages and public Demo. It is derived from Pure Admin Thin 6.2.0.
Development login reads Server-managed users without a password or real
session authentication. Server resolves the declared caller against its user directory and checks Project
grants and Task ownership across task entrypoints with the existing HTTP contract.

## Development login and access

`/` lists current development users and enters the console only after an explicit selection.
[Directory source](src/user-directory-source.ts) reads `/api/v1/users` and
`/api/v1/roles`. Server owns the initial JSON and process-memory mutations; its
default seed includes `1000` (管理员), `1001` (用户 A) and `1002` (用户 B).
Admin manages users at `/?view=users`: userId, name, role and supported Projects.
POST `/users` and PUT `/users/{userId}` change the shared Server directory. Other
clients observe changes on refresh; browser reload and logout do not reset them.
Only Server restart reloads the seed. No database or file write persists changes.
User IDs cannot be changed after creation; the
currently logged-in user's profile is read-only to avoid changing an active caller;
its independent credit account can still be adjusted by an administrator.
[Role policy](src/access-policy.ts) applies the Server-provided labels, permissions,
Task scope, Project scope and home route for `admin` and `operator`. Pages do not interpret role strings or
special-case user IDs.

Admin can use all enabled console features and reads Tasks across users. Operator
can create and manage its own Messages and App Checks Tasks in assigned Projects,
including their existing import, approval, closure and result operations. Both
built-in operators have `messages` and `app-checks`. Admin has all Projects; user
management choices come from the common Project directory. Project assignment
does not grant a role's missing operations: SMS, Runtime, Task workbench, Reference,
configuration, user management and Mock demonstration controls remain admin-only.
Route metadata, navigation and
operation controls consume the same permission policy; Catalog availability is a
separate prerequisite. The App Checks configuration view declares its permission
alongside the route, without a path-regex allowlist.

[Login state](src/console-identity.ts) starts anonymous. Only a selected user ID is
stored in the current tab's `sessionStorage` under `xa-mass.console-user`; its role
and label are resolved from user data on reload. Missing, invalid or removed users
return to login. The old `xa-mass.development-user` selector value is not migrated
and never grants a login. Storage failure still permits an in-memory login.
Anonymous deep links lead to login and return to the permitted internal target;
otherwise admin lands on `/runtime/workers`. Operator prefers `/app-checks`, then
`/messages` according to its Projects; without an admitted business entry it sees
the empty console at `/?view=home` with logout available.
The existing Server root mapping serves login on direct visits and reloads; no
new backend page mapping or `/login` compatibility route is added.
User management also uses that root mapping, so direct visits and reloads work
without adding a page mapping. Login restoration waits for the API directory,
including newly added users. Directory failures show an explicit retry and never
substitute Mock users. Project grants control console navigation and routing and are also enforced by
Server task admission. Unknown users and stale role declarations are rejected with 403. This remains a trusted-development identity contract without session authentication.

The top bar displays the current user, role and logout. Logout removes the login
and unmounts the entire user console, releasing observations, Pinia state, filters
and local input. New login instances are distinct even for the same user ID.
Issued writes retain their original caller and may finish on the server; late
responses cannot update another login or initiate chained import/write requests.
Logout does not retry uncertain submissions. Reload and logout discard local
input; persisted Tasks are found through the shared Task API.

Mock business sources live for the application, independently from the console's
login lifetime. Logout keeps created Mock Tasks available to the next authorized
user, while clearing page state. Mock operations resolve the explicit caller through the same application-lifetime
Mock directory used by login and user management, capturing its current role and
Project grants before business work. A changed directory governs the next operation; API and Mock return their admitted Task window. Pages apply
business display filters without another role-based ownership filter.
The explicit Mock directory uses [frontend-only fixtures](src/development-users.ts)
and simulates Server writes across console logins. Only Mock resets on browser
reload. API mode has no built-in user fallback or browser-owned user mutations.

Task and Scenario methods take an explicit immutable caller snapshot. The existing
client encodes its userId as UTF-8 Base64URL in `X-XA-Dev-User` and sends
`X-XA-Dev-Role`; list query `userId` remains a target-owner filter. Caller encoding is unchanged; Server validates directory membership, role consistency
and current Project grants before task access. Catalogs remain caller-free
availability reads. Unauthenticated UI cannot issue protected operations; sources
bound to a login never silently switch to a later global caller.

Server supplies `metadata.userId` from the caller. Operator lists select the
caller's range before limiting and reject an explicit other owner; admin may
filter by user. Known Task reads, counts, input, results, approval, closure and
export continue using the shared Server policy. A 403 is a definite rejection,
never an unconfirmed mutation or a reason to substitute Mock. It prompts browser
refresh or logout/login; it never retries a write or changes the caller. Directory
list refresh alone does not replace the current login snapshot. Frontend login and
visibility do not authenticate the HTTP headers; replacing development identity
with real session verification is separate backend work.

## User credit accounts

`/?view=credits` displays the current user's balance, last change time and descending
transaction pages (50 rows). User management provides per-user credit drawers and an
explicit userId lookup for retained accounts outside the current directory. `credits.view`
and `credits.manage` drive routes, navigation and controls; Project grants do not gate accounts.
The same account panel handles personal/admin views. The admin's own profile restriction
does not disable credit adjustments. Only current directory users can receive adjustments.

The adjustment form sends a new UUID for each explicit intent, a signed integer delta and a
required reason. It disables duplicate clicks. Confirmed success refreshes the balance and
first page; confirmed business refusals are explicit errors. Transport loss, invalid success
responses and 503 retain the original request/content. The panel offers receipt lookup and
explicit same-content retry, never automatically creates a replacement request. A missing
receipt stays unknown. Once unknown, a later 403 does not disprove the earlier possible commit.
403 prompts refresh/relogin without fallback or automatic retry. Pending drawers cannot be
closed accidentally; logout still clears local state and cancels reads. Late responses cannot
enter another login or launch follow-up reads. A new login consults Server history and never
resubmits a previous login's request.

`MockCreditSource` shares the explicit application-lifetime Mock directory and resolves each
call's declared user/role before account access. Its synchronous commit models atomic balance,
ledger and receipt publication. Account data survives logout but resets on browser refresh.
Admin Mock controls can drop one post-commit response to exercise query/retry. API mode has
no Mock fallback. `credits.test.ts` covers integer limits, parallel debits, idempotency,
pagination, current directory admission, lost responses and login/disposal races.

App Checks alone adds startup precharge: configuration exposes per-seed `unitPrice`,
creation fixes it, and the approval dialog reads authorized `/app-checks/tasks/{id}/credits`
for actual persisted quantities, owner, amount and balance. The common approve request
carries an explicit UUID and quantity/amount confirmation. Unknown responses retain that
request for read-only reconciliation and explicit continuation; a rejected attempt requires
an explicit new confirmation. Closing the dialog preserves its pending local identity;
logout clears local state and never replays a write. Reopening can recover Server intent.
The current phase does not automatically return points on closure or execution failure.
Account rows retain App Checks attribution and Task ID after Task deletion.

App Checks Mock receives the same `MockCreditSource` used by account pages, with no
implicit funding. Its guarded input/start flow preserves grouped quantities, fixed prices,
current directory admission and lost-response semantics. `app-check-credits.test.ts` proves
owner charging, duplicate imports/starts, competing balance use, rejected receipts and
explicit continuation. Existing business test fixtures explicitly top up their users.

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

The documentation site has a separate development process. Run
`pnpm docs:dev --host 127.0.0.1` in a second frontend terminal alongside
`pnpm dev` (or `pnpm dev:mock`). VitePress listens on port `5174`; the Console
development server proxies `/docs` to it. Both processes are needed when
following the Console's documentation link during development.

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
/?view=users
/runtime/workers
/runtime/tasks
/task-workbench?tab=rpc
/task-workbench?tab=finite
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
The sidebar also links to the current origin's `/scalar` and `/docs/`.
On Vercel, the SPA maps `/scalar` to the same static API Reference; on the Java
Server, its MVC Scalar route takes precedence and stays live. The OpenAPI
snapshot is committed at `frontend/public/reference/openapi.json`. The
dictionary JSON is available at
`/reference/platform-diagnostic-codes.json`. The
[architecture guide](../doc/guide/architecture.md) introduces the project through
the same repository Owner documents used by Agents. Generated frontend,
documentation-site output and dictionary content are not committed.

## Build ownership

`pnpm build` writes the unified console to `dist`; `pnpm build:demo` writes
`dist-demo` for public hosting. Both also place the documentation site under
their output's `docs/` directory. `pnpm build --outDir <path>` does the same
for a custom output directory, including Distribution staging. There is one
frontend dependency graph. The console owns navigation and page layout. Runtime and the task workbench share
a console-session provider for their directory, call history and drafts, while
each surface owns independent read snapshots and request generations. Invalid
Runtime configuration blocks only Runtime/workbench pages; SMS and Reference
remain independent of that configuration. No background reads are started by
the session provider.

## Documentation site

Frontend owns the VitePress presentation, local search, Mermaid rendering and
build integration at `/docs/`. It uses VitePress `1.6.4` and Mermaid `12.2.0`
from this module's existing `pnpm-lock.yaml`; there is no second package or lock.
[Documentation entrypoints](../doc/README.md) own reading routes, while mechanism,
configuration, API and proof content remains in its existing Owner Markdown.
The site renders those sources without maintaining copied mechanism narratives.

```text
pnpm docs:dev --host 127.0.0.1
pnpm docs:build
pnpm docs:check
```

`docs:build` builds the static site into `.vitepress-dist` and validates its
output. Run `docs:check` afterwards for documentation tests and another check of
that output and its links. Console builds include the site for API, Mock and custom-output
artifacts. [Distribution](../distribution/server/README.md) packages those assets
alongside the Console; the running Server serves them as static content.
The site neither calls Runtime APIs nor interprets a current deployment's state.
Documentation checks remain separate from the behavioral lanes in
[TESTING](../TESTING.md).

For the Vercel project with Root Directory `frontend`, keep **Include source
files outside of the Root Directory in the Build Step** enabled and automatic
unaffected-project skipping disabled. The repository's `ignoreCommand` owns
the skip decision: current Markdown anywhere in the repository, guide assets
and frontend inputs trigger a build; an absent or unavailable previous commit
also builds. These project settings allow the repository-level documentation
inputs to reach that decision. See the
[Vercel monorepo guidance](https://vercel.com/docs/monorepos/monorepo-faq).

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

`/messages` is the desktop Task workbench for users admitted to the Messages Project:
one loaded list, one operation menu per
row and a 760px result preview drawer. `/messages?view=create` is a single creation
page. `/messages/tasks/:taskId` opens the same drawer over the list; adding
`?view=create` opens it over the retained draft. Opening/closing previews does not
reload the list or discard its filters, table position or focus. Browser history
and direct links use the same routes; no new Server forwarding path is needed.

The business list excludes managed Tasks without fetching replacements. It retains
ordinary Tasks with missing metadata and shows unavailable values. The current
loaded batch is capped at 100; state tabs, application/country/date filters and
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
Preview has no historical input-version or senderPhone compatibility path.

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

The draft captures the logged-in caller on opening. Requests retain that caller
through submission and reconciliation. Logout clears the local console and stops
chained imports; it neither cancels an already-issued server write nor replays it.

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
offer preview/closure; ended and unknown Tasks offer preview. Managed or incomplete business Tasks cannot
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

Real multi-application assembly follows the
[Messages startup contract](../scenarios/message-campaigns-jvm/README.md#worker-供给与装配).
Mock behavior is not proof of real assembly, environment migration or Worker delivery.

### Shared Task observations and operations

Messages and App Checks compose `/projects/{projectId}/tasks` or its point-read
with `/projects/{projectId}/tasks:stats`; the latter sends at most 100 Task IDs.
Their `/{scenario}/tasks/{taskId}/results` response is only `{taskId, results,
truncated}`. Shared client code captures one caller for the composed request, maps
Score tags to display counts and preserves the bounded window. Result previews never
supply whole-task business-verdict statistics. Raw Result/Item-state reads remain
independent. The common finite client submits approval as `{expectedItemCount}`.
Scenario routes retain creation, import, business results and configuration/CSV.

Raw creation has a business requestId independent of `X-Request-Id`. Drafts retain
their original caller and request identity through parsing, submission and explicit
reconciliation; ending a raw attempt allocates a new requestId. New reads use the
current identity, old list/detail snapshots are invalidated on identity change, and
late preparation/call responses stay attached to their original operation.

## App Checks Task workspace

The Batch Checks workbench (technical module and route: app-checks) is the first business Task workflow sample. It keeps
the existing Console theme/navigation and does not change the Tasks, Messages
or SMS workspaces. Its Catalog probe independently controls availability:
an initial 404 means disabled; transient errors allow explicit retry.

The admin desktop workspace has two entrypoints: Tasks at
`/app-checks` and check configuration at
`/app-checks?view=configuration`. One JSON document covers all checks;
there is no separate add-check form.
Opening configuration directly does not load Tasks. Switching tabs keeps the Task
table mounted, preserving filters and scrolling without another list request.
Task drafts and the JSON editor text survive navigation within the current login.

The Task workspace uses a list, one action menu per row and a 760px
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
them. Seed inputs are not persisted to browser storage.

### List and creation

The list shows finite business Tasks from the existing bounded 100-Task response.
Search, seed-format/capability/country/date filters and state-tab counts operate only on
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
Pending-review Tasks also allow additional seed imports using their saved type and options. Startup, cancellation and stopping require confirmation with check type, execution configuration, applicable country and
execution Item count. In-flight actions reject duplicate submission; failures retain
the observed state. Unsupported API operations show their unavailable reason.

Creation supplies the current identity through caller headers. The userId and role
are frozen alongside the request on first submit; the JSON
body contains no creator field. The single page selects phone/text seed format and then one effective check capability. There is one carrier choice; App A/B remain separate registration targets. An empty seed category stays explicit without automatic fallback. Group, event, Pool, selector and implementation type are read-only collapsed details. Only phonenumber shows country and CSV options. There is one submit
action. Names are generated by the server at first creation without an input-count dependency; there is no editable name or separate review step. Mock startup
confirmation remains a separate action after creation. Successful creation
returns to the list, refreshes its bounded snapshot and preserves filters. A
visible new Task is highlighted; a dismissible success notice also links its
preview when the current filters or read range exclude it.
For phonenumber, UTF-8 TXT, CSV and pasted lines share existing number/country validation. CSV
supports BOM, escaped quotes and quoted newlines, with explicit header/column
selection. Text accepts only TXT or paste, removes a leading BOM and surrounding ASCII spaces/Tabs per line, skips empty lines and deduplicates exact normalized strings; case, Unicode and internal content stay intact. It does not parse JSON or infer a domain. A dedicated module Web Worker performs parsing and validation; new
inputs and leaving the page cancel old work. Switching the input-method tab does
not replace the current numbers: the source label always names the actual file or
pasted content. Reading/revalidating replacements blocks submission; invalid input
never submits a valid subset. Creation displays only counts and
a concise error, with no seed-detail table or problem-file export. Changing seed format or capability revalidates unsubmitted input without silently converting it.

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
create request contains requestId, checkId and country only for phone seeds, with no name, seeds or
execution parameters. The server resolves the effective check once and saves its
type, seed type/options, names, event, refill, selector and normalized parameters into the Task.
A compact creation summary shows the effective Group, event and Worker query from Catalog, with the full read-only execution binding collapsed, plus import limits and a configuration link.
First submission freezes the caller and request. An explicit check of an unconfirmed
creation reuses them without automatically importing or approving; configuration
changes do not cause a different Task or reinterpret a saved Task.
The UI creates once, retains the returned Task ID, then uploads a
normalized UTF-8 seed file (`text/plain`) to seeds:import for server validation and bounded
append. Catalog supplies the per-import 100,000-seed/10 MiB limits. Grouping is configured
only in JSON configuration and saved in each Task (inputVersion 5); creation cannot
override it. Import summaries distinguish unique seeds, assembled Items and added/existing
Items. Lists, progress, approval and recovery quantities count execution Items. The UI
never derives a whole-Task seed total from these counts. Results and CSV remain per seed;
row identity is the unambiguous messageId/seed tuple, so several rows can share an Item.
A malformed group has no partially valid export. Preview is capped at 100 expanded rows.
Mock preserves complete ordered group identities, tail groups, overlaps and frozen grouping. A failed
import never creates another Task. Pending-review menus allow additional imports;
existing whole ordered seed groups are skipped and older input-version Tasks explain why import is
unavailable. Starting sends the quantity shown in the confirmation dialog; the
server rejects a changed/empty quantity and active imports.

Unknown creation freezes its original request payload and known Task link.
Unknown import retains the target Task and offers explicit re-import of the same
numbers; neither action retries automatically. The recovery panel reads the known
Task independently. Users may close the local draft after acknowledgement;
association records retain only request/Task identities and input summaries for
the current session, never another phone-list copy. A later draft starts empty.
Successful import receipts and source summaries are likewise session-only; after
refresh the UI reports unavailable history rather than reconstructing it from
execution counts. Shared Messages and finite Task file limits are unchanged.

Business input is admitted by the fixed input owner. Preview has no retired supply/input-version compatibility path. Pool configuration becomes effective only after Server restart.

### Preview drawer, management and results

The drawer shows identity, observed state, last successful read time and the
shared action menu, with preview itself omitted. Progress stays in the list row.
Pending-review Tasks initially show the import summary; other Tasks show result
previews. Import summary, activity and technical information are expandable
sections, with auxiliary data read only when expanded. Task state, Item Score counts and Result content remain
independent. Matched and unmatched answers are both successful execution.
The result view exposes `seed`, `matched` and type-specific `details`; registration retains `details.registered` and the explicit 已注册/未注册 answer. Carrier results retain the carrier or normal negative reason. Execution failure
and invalid content share the check-error filter, with distinct reasons and no verdict. Closing a Task does not force progress to 100%.

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
from Task counts or Results. API exposes no extrapolated whole-Task business-verdict
totals or fabricated import/activity history. Known creation time remains visible.

The collapsed **本次任务配置** section uses only the Task response: saved check type and execution configuration,
actual Group, seed type/options, event, refill, Worker selector and parameters. A link opens the current check
configuration; browser Back restores the original drawer and background context.
Current Catalog values never overwrite saved Task values. Unavailable or malformed
configuration is shown locally and does not block result preview or management.

Technical information retains Task/Worker identities. Only simulated-registration-v1
shows salt and manual Web Crypto SHA-256/BigInt verification of up to 100 snapshot Results. It compares
business answers, Group and configured delay, not scheduling or attempts. Missing
evidence is unavailable; verification is invalidated by refresh. There is no
remote hashing fallback.

The workspace is titled **批量检查**. The original-carrier check reuses the number
Worker protocol; valid=false is an unmatched answer, not an execution error.
Its Mock provides explicit examples without copying libphonenumber. Worker and delay
diagnostics are read from details. Results without a usable verdict are never
presented as a fourth business category.

### Application configuration storage and activation

One monospaced textarea edits the saved JSON document. Its structure is
`common.typeDefaults`, `checks` (a checkId-keyed object containing label, checkType, Group,
event, Task refill and Item selector). Check parameters replace the named
default object as a whole; there is no recursive merge. A collapsed reference shows
registered contracts and examples, read-only countries/import limits, and the current
effective document. There is no second form or editor framework.

Edit, format, save, cancel and reread operate on raw session text. The UI validates
only JSON syntax and an object root; the server validates semantic fields and returns
paths/reasons. `PUT /api/v1/app-checks/configuration` uses `expectedFingerprint` and
whole-document replacement. Invalid JSON/fields, content-fingerprint conflict and uncertain saves
preserve the exact text; rereading never silently replaces a dirty draft. Uncertain
saves retain the submitted text/base fingerprint for explicit comparison, without automatic
retry. In-flight saves remain locked across remounts; late responses cannot steal focus.

The scenario stores one UTF-8 JSON configuration file, without format/revision counters.
The Server is its single writer while running; manual file edits require stopping it.
The editor retains its text after a write failure or uncertain save and supports readback without automatic retry.
API fingerprints are opaque SHA-256 content tokens carried unchanged by the client.
Object key order and formatting do not change the token; array order and string content do.
Catalog checks use stable checkId order, independent of JSON key order. Saving requires manual restart;
new pending checks do not enter the effective Catalog. The complete JSON may add, delete or rename checks,
and change an existing checkId's type or Group. At least one valid check must remain; parameters and execution
must fit the selected contract. No enabled flag, replacement mapping or automatic conversion is added.
Deleting, renaming or rebinding uses a new Redis scope and request identities; no cross-change old-data migration is provided.
Several checks may use the same Group. Host Group/events/Matching enablement and actual Handler
installation are separate startup dependencies; saving does not provision them.
Window parameters are host-owned and shown in Runtime Workers; changing their window length requires a new Redis scope.
An unsubmitted creation draft remembers its selected contract. If a refreshed Catalog removes that check
or changes its type, the input remains and submission is blocked until explicit reselection/revalidation.
Changing seed format is explicit, even when the same checkId now accepts another format. Frozen requests
and known Tasks retain their original caller, request and saved input context.

Mock uses the same document and five effective capabilities (App A/B registration, carrier, uint32 prime and MD5 prefix), content-fingerprint comparison and explicit
**模拟重启生效**. It has no service requests or runtime registration. Created Mock Tasks
capture their own execution configuration; changes apply only to later creations.
Mock policy values (priority 50, three retries, 365-day validity) are demonstration
data. Task refill is shown from the saved Task. API policy fields not returned by its
view remain **当前接口未提供**; there is no scan or local default substitution.
The window is an observation filter for a single Worker in a Pool/Group, not a strict
quota or predicted throughput. registration-v1 results have no simulated delay and
do not offer hash recomputation. The text demos expose prime answers and full MD5 digests. Mock uses declared fixed input/answer samples, fails other sample executions explicitly, and is not an algorithm oracle. Real uint32 validation occurs during complete Server import, before any Item append. Preview has no city/DNS/stock dependency.

CSV filters are all/matched/unmatched. Shared columns are seed, verdict, check type and execution configuration; phone adds country and types supply business columns (including registration status). Export uses the full valid-result stream, independently of the 100-row preview. Errors never become unmatched or fabricated missing Results.

Manual Catalog refresh retains the workspace and previous data while reading.
A failed refresh preserves the last successful timestamp and marks the snapshot
stale, independently of Task read/mutation status. Configuration reads do not reset
drafts, frozen requests or Task snapshots; late reads and drawer close callbacks
cannot steal focus after navigation. No polling or persistent number storage is added.

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

Terminal-only CSV exports accept `all`, `matched` and `unmatched`; old registration
filter names are rejected. Contract code owns extra CSV columns; the original-carrier
check adds carrier name and identification reason. The dialog reads complete local counts, generates a
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

`/runtime/tasks` is a raw viewer. Project tabs use the immutable directory from
`GET /api/v1/projects` and read a current batch of at most 100 Tasks.
The Server selects by attribution score/Task ID and only then sorts that batch by
saved creation time; there is no latest-N guarantee or scan to fill missing rows. Global observation keeps
its independent Score window (1..1000 coordinates in Owner order). Search, Group
and finite/managed filters operate only on the loaded collection; scrolling
never fetches more data. Missing descriptors and Score bands remain unavailable.
Refresh failures retain the last snapshot and successful read time.

The configured `default` Project remains an ordinary observed Project, preferred
on first entry. `project`, `taskId` and the global `view` route query preserve the
viewer context. TaskView decoding includes backend `name` and `metadata`. The
drawer exposes original configuration and a workbench link carrying the saved Project
and actual Task ID. Every known Project also has a workbench entry above its list,
including an empty list. Unknown/missing attribution cannot be used for navigation.
It contains no create, append, approve, close or Task Call control. The former
`view=create` parameter no longer opens a creation flow.

Terminal finite Tasks in every Project retain raw successful-Result JSONL export.
It reads the whole-task source independently of the preview/filter state, does
not apply Scenario business validation, and requires a fresh terminal snapshot.
Export state is independent of finite-task mutations; Mock never exports.

## Task workbench

Explicit host Group bindings appear through the existing Group capability response, independently of Project membership. The finite form displays the selected
query's actual Pool and uses that relation for supply suggestions; enabling supply alone does not change an Item query.
Mock includes `pool-validation` and its Android `device-window` binding. Project capabilities and Runtime binding details share
the same resolved example; Mock still disables raw writes and does not simulate window scheduling.

`/task-workbench` is admin-only and has RPC and finite-task tabs, defaulting to
`tab=rpc`. It reads every Project in the immutable startup directory, including Scenario
Projects. A single Project is a read-only label; multiple Projects use a selector.
The `project` query selects the context. Without it, the console restores the last
valid session choice or uses the first directory entry; no Project means no writes.
An explicit unknown Project stays unavailable until the user chooses another.
Raw Item append is enabled only for `server.inputOwner=raw`; business Tasks keep
their Scenario import conversion.
Approval and closure use the shared Task client, including for Scenario Tasks.

RPC uses a Task list at `project=...&tab=rpc`, content-area creation at
`view=create`, a read-only configuration drawer at `taskId=...`, and one JSON
debug page at `view=debug&taskId=...`. The current Project batch is filtered for
PARK_WHEN_IDLE Tasks; missing descriptors remain unavailable and no refill read
is made to fill the list. This is not a complete or latest RPC history. Known IDs
can be point-read outside that batch. Runtime links open configuration first.

Creation uses the logged-in user; a `user` query parameter cannot select another
creator. `rpc-workbench-v1.<project>.<user>.<group>` retains independently encoded
UTF-8 Base64URL segments without padding. One triple reuses one immutable Task.
The form selects a registered Group, optional name and explicit Pool supply,
initially `[]`. The finite-task supply editor is shared. Target `{}` and count
100 are editable initial values, not guaranteed eligibility, concurrency or
exclusive Worker quotas. Priority 0 and maxRetryTimes 3 remain read-only.

Only explicit creation or resumption calls `rpc:prepare`; route changes, reads
and calls never prepare Tasks. The workbench always sends `refill`, including
empty supply, and freezes its caller, ID and full request on first preparation.
Same-ID configuration conflicts cannot update a Task. Unconfirmed preparation
retains that request for manual reconciliation; PRE_REVIEW can be explicitly
prepared, terminal Tasks cannot reopen. Existing raw Tasks can also be opened
by their actual ID. Scenario-owned RPC Tasks remain read-only in this workbench.

Web debugging submits the existing `{items, waitTimeoutMillis}` request as JSON.
Each request holds 1..100 Items; one Item uses the same editor and adapter. There
is no chunking, automatic replay, synthesized message ID on submission or implicit
Task creation. Example insertion generates a visible ID before submission.
Worker queries accept bounded non-null JSON, including `workerId` strings;
Matching retains local parameter interpretation. Duplicate IDs keep the Server's
last-input behavior and are disclosed before submission. Shared Pool supply and
per-Item queries remain independent.

The adapter checks the full deduplicated response identity set. Each message
independently shows succeeded, failed or not_observed with original Worker output.
Uncertain HTTP outcomes and rejected requests are separate from Item execution
failure; non-2xx does not prove no work occurred. Manual `results:take` reads use
original IDs and caller snapshots, including batches entered without browser
history. Failed reads preserve the previous results and successful read time.
The UI never automatically resends; same message IDs are not an exactly-once
promise. The default example waits 3000ms; omission follows the Server default.

A Task keeps 20 browser-session request records and its JSON draft. Project list
filters, scroll and creation drafts remain separate. Late responses update only
their original Task/session. Request and POSIX cURL examples use current API paths
and declared caller headers; no SDK or persistent invocation journal is added.
Mock includes static mixed batch observations and disables preparation/calls/reads;
API never falls back to Mock.

Finite tasks use `project=...&tab=finite`, `taskId=...` for details and `view=create`
for the content-area creation page. The selected Project collection excludes PARK_WHEN_IDLE
descriptors without fetching replacements. Unknown descriptors
remain visible and cannot be mutated. Create, append, approve and close use
fresh Server state, so reloading the browser does not lose the ability to manage
existing tasks. Mutations require matching Project attribution and a fresh legal
lifecycle state; terminal export shares the viewer's download implementation.

Finite filters, scroll positions, drafts and submission receipts belong to their
Project; append drafts additionally belong to a Task. RPC Group selection is
remembered per Project, while call records remain keyed by actual Task ID.
Switching Projects closes the current drawer. Accepted submissions retain their
captured caller, Project, Group and inputs and may finish original batches, but cannot
navigate or write into the newly selected context. Finite writes keep one shared
in-flight gate; an uncertain receipt freezes only its own Project's submission.
No drafts, numbers or receipts are persisted to browser storage.

The creation page separates execution configuration, raw input and submission
summary. Group/Event/function/Pool choices come from the capabilities response.
Functions expose their actual nullable Pool binding; applying a supply suggestion
is explicit, starts at target `{}` and count 100, and never overwrites edits on
refresh. Complete refill JSON remains available and supply/query may differ.
Priority 50 and retry 3 remain under advanced settings.

Payload JSONL is the default: each object is a raw payload using the selected
Event/query. TXT maps each physical line, including empty lines, to an explicit
field. Complete Item JSONL retains its own identity, Event, query and optional
priority/TTL. All support UTF-8 file or paste, 1 MiB/10,000 physical lines and
whole-input validation before creation. JSONL ignores blank lines while preserving
physical error/identity coordinates; duplicate Item identities fail. Payload/TXT
content is not deduplicated; extra imports use a fixed submission prefix. Only
three examples are rendered. Matching remains the semantic parameter validator.

Project reads include `workerGroupSource`: explicit default uses registered Groups,
other Projects use declared IDs. Both RPC and finite selectors use the capabilities
endpoint; startup declarations are not used as a resolved inventory. Default lists
all registered Groups on manual refresh and never automatically enables Pools.
Failed capability refresh keeps the old snapshot and disables dependent writes.

Finite detail exposes GET `tasks/{id}/results:preview`: at most 100 raw Results,
with truncation, refresh time and stale snapshot feedback. It does not interpret
business answers. JSON formatting is optional; copying and terminal successful
JSONL export preserve original strings. Preview/filters do not bound export.
Mock demonstrates the same shapes with zero service calls while raw writes and
export remain disabled.

Append batches remain at most 100. Rejection or uncertainty stops further
batches while retaining confirmed, rejected, unconfirmed and unsubmitted counts.
The browser never automatically retries creation, replays Items or approves.
The business requestId identifies creation for the captured caller; HTTP
`X-Request-Id` remains diagnostic. Ending the
local submission clears its input without closing the Server Task. A structured
503 may include the known Task ID; the recovery button explicitly calls
`directory:ensure`, without replaying creation, importing Items or approving.
App Checks also offers an ID-based open action for Tasks outside the current batch.
Mock selects score/Task ID batches before display sorting, matching this boundary.

Viewer/workbench navigation retains filters, table scroll, RPC drafts/history
and finite input drafts in the current console session. Create and append drafts
are isolated by target. No numbers, files or call records are persisted in browser
storage; reload clears them. Drawer close restores focus only while its route
is still current. Lists, RPC and finite details have independent request slots,
so late reads and submission continuations cannot replace another context.

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

## Finite Task retention views

The common Project Task contract includes retention phase, first termination,
freeze and expiry timestamps. Finite workbench, App Checks and Messages show the
same facts. OPEN terminal tasks may receive late outcomes; FROZEN/RELEASED tasks
cannot. Archived previews and exports use existing URLs. Item Score counts remain
available and unfinished Items are not displayed as successful. Archived raw
point queries are unsupported (`400/12014`); RPC point queries are unaffected.

MockTaskRetention provides the single controllable 1h/7d policy used by both
business Mock sources. Their explicit maintenance helpers require the shared
admin permission, freeze later observations, retain preview/export data, and
remove task-local maps plus creation correlation on purge. Same-request creation
then has a new Task ID. No timer, filesystem or Server data fallback is simulated.

## Consuming RPC Results and SMS cache views

RPC debug uses `results:take`: successful/failed receipts remain in session history,
follow-up takes target unresolved IDs, and explicit known-ID takes can obtain later
values. Empty results do not erase earlier receipts. Consumption can commit before
an HTTP response is lost; no automatic execution retry or Mock fallback is added.
SMS queries still refresh new content, retain higher business revisions, and label
a previous browser observation when Server returns NOT_OBSERVED. Server SMS cache
is bounded process-local retention, not history available after every restart or
from another instance. Existing login/403/late-response isolation remains in force.

### WorkerGroup effective resource bindings

Runtime Workers separates Worker observations from the read-only resource relation view at
`/runtime/workers?view=bindings&group=...`. The default remains Worker observation. The binding directory endpoint lists startup
Matching/window Groups plus declared Project members without Redis access; it is not a complete registration directory. Known-ID
lookup remains available, including identities outside this list. Binding direct entry never starts random Worker sampling.
The detail shows independent Project scope, declared events, Pool/query relations and windows with embedded paired feedback.
Read and write property fields are explicit; feedback is event-selected allocation observation, not Pool consumption accounting.
Missing registration, unavailable reads and stale snapshots stay visible. There is no claim of installed Handlers, online Workers or stock.
An explicit observation link point-checks the selected Group and samples its Workers even outside the random Group sample.
Directory, detail and sample state are separate; late responses do not change current selection. Refresh is manual.
The raw finite form shows the query/Pool relation and supply separately and reads matching event feedback when its auxiliary summary is expanded;
summary failure does not reset drafts or replace existing submission admission. Batch Checks links current bindings separately from
saved Task values, only within existing Runtime UI permissions. Mock shares one scope fixture and independent Group capabilities.
Batch Checks config retains only common/checks; its response no longer exposes newScopeRequired and its editor rejects old poolGroups input.
