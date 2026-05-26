# Control Console Realistic Scenario Roadmap

Last updated: 2026-05-26

Status: proposed server/control-console convergence roadmap.

This roadmap turns the backend-hosted console from early demo-shaped seed data
into a small but real platform scenario. It is server-owned because the work is
about product shell, default scenario data, IAM/API-key setup, HTTP surfaces,
and frontend views. Engine/runtime ownership must not move.

## Summary

Current console seed data proves that the backend shell can show projects,
events, submitters, workers, and tasks, but the visible story is still
fixture-shaped:

```text
demoApp / demoOps
demo.dispatch / demo.dispatch.gb
demo-worker-{lane}-{index}
```

Target mainline:

```text
publicProbe / deviceProbe projects
  -> real probe events
  -> API-key-created task shells
  -> 1,000+ task items
  -> externally registered WorkerGroups
  -> externally registered polling and WebSocket workers
  -> group-first routing
  -> Stage-2 worker match using runtime facts and fingerprint attributes
  -> reviewable task/result/operator views
```

The goal is not to build a production SaaS demo. The goal is to make the first
screen read like a platform skeleton with real scheduling evidence instead of a
mock browser.

## Current Facts

Implemented today:

- Backend-hosted console pages exist for projects, project detail, workers,
  tasks, API keys, submitters, rules, and audit.
- Task creation has a clean HTTP/API-key path through task shell create, item
  append, and seal APIs.
- External worker registration surfaces exist for AdapterNode, WorkerGroup,
  NodeGroupBinding, worker registration, polling presence, polling work, result
  submit, capability report, state report, and command ack.
- SDK/intake can resolve event metadata to explicit `workerGroupId(s)` before
  scheduling.
- Engine scheduling candidate source is group-selector first.
- Worker attributes can still participate in Stage-2 filtering/ranking after
  the candidate source is narrowed to a WorkerGroup.

Current gaps:

- Default dev data still reads as demo/sample vocabulary.
- Default bootstrap can still look like server-owned seed truth instead of
  external actors using public platform paths.
- The UI overemphasizes raw worker rows and underemphasizes WorkerGroup
  capability, AdapterNode/NodeGroupBinding state, and item-level match proof.
- Current console pages can report conflicting online evidence: worker model
  status, transport reachability, event online coverage, and dispatch
  eligibility are not consistently separated in the UI.
- Worker examples are too small or too synthetic to prove match/rank behavior.
- Task examples do not yet carry enough realistic item volume and item payload
  distribution.
- Expected probe failures are not yet modeled as item/result classified
  outcomes in the default scenario.

## Core Rules

1. WorkerGroup is capability and candidate-source truth.
2. Worker rows are execution instances, not project/event capability owners.
3. Worker attributes are Stage-2 match/rank/diagnostic facts only.
4. The default scenario must include both polling workers and WebSocket workers.
5. Default workers must be externally registered through worker credentials and
   worker API / SDK public surfaces.
6. Default task shells must be created through task APIs by an API-key
   submitter identity.
7. The default scenario should seed roughly 5-20 task shells and at least 1,000
   task items across those shells.
8. The default scenario should seed at least 100 external worker instances
   across polling and WebSocket transport.
9. At least one WorkerGroup must include enough fingerprint-distributed workers
   to prove Stage-2 match after group-first routing.
10. Every default event item should carry a common workload envelope with
    bounded `sleepMs`, `timeoutMs`, and `expectedOutcome`. Sleep is latency and
    concurrency evidence, not the business semantic of the event.
11. Default generated tasks must not auto-run. They stop at draft, review, or
    sealed-pending-approval unless an operator explicitly approves or runs them.
12. Console views must separate configured coverage, worker model status,
    transport reachability, dispatch eligibility, and event online capacity.
13. Expected probe failures such as DNS NXDOMAIN, timeout, TLS error, schema
    invalid, or non-2xx HTTP must first be item/result classified outcomes.
    They must not redefine task terminal or runtime result convergence
    semantics without a separate owner review.
14. Old demo names do not need in-repo compatibility once the scenario is
    migrated.
15. Public internet probe providers are dev/demo scenario inputs only. CI and
    offline verification must use local, deterministic probe events and
    fixtures.

## Owner Boundaries

| Area | Owner | Must not own |
| --- | --- | --- |
| default scenario shape | `xa-mass-server` bootstrap/config + frontend views | engine scheduling semantics |
| project/event catalog display | server catalog APIs + frontend resources pages | runtime candidate admission |
| task creation demo path | task HTTP APIs with API-key submitter identity | privileged server task insertion |
| task item volume | task append API / SDK public operation | task lifecycle or result convergence semantics |
| WorkerGroup declaration | external worker API / SDK worker registry surface | worker instance Stage-2 facts |
| worker instance registration | external worker API / SDK worker registry surface | WorkerGroup capability truth |
| polling delivery | polling adapter / external worker contract | WebSocket session ownership |
| WebSocket delivery | WebSocket adapter / external worker contract | polling queue semantics |
| Stage-2 match evidence | engine match/rank/admission views and traces | WorkerGroup candidate-source truth |
| online/capacity display | server read APIs + frontend pages | transport presence truth, worker model status truth, dispatch gate ownership |
| expected probe outcome display | task item/result payload and console aggregation | task terminal policy, runtime result convergence |
| public provider profile | server scenario bootstrap profile + worker item payload | CI availability, engine/runtime fallback behavior |
| console aggregation | frontend pages and server read APIs | hot-path scheduling scans |

## Target Scenario

Use a small project set, not a single catch-all project.

```text
Projects:
  publicProbe
    owner story: public API and network reachability probes
  deviceProbe
    owner story: phone/device metadata and fingerprint match probes
  dataQualityProbe
    owner story: local CSV/JSON validation and parser checks

Events:
  publicProbe:
    probe.weather.current
    probe.fx.latest
    probe.crypto.price
    probe.ip.geo
    probe.url.dns
    probe.http.status

  deviceProbe:
    probe.phone.metadata

  dataQualityProbe:
    probe.market.daily-csv
    probe.csv.validate
    probe.json.schema

Flat event catalog:
  probe.weather.current
  probe.fx.latest
  probe.market.daily-csv
  probe.crypto.price
  probe.ip.geo
  probe.url.dns
  probe.http.status
  probe.phone.metadata
  probe.csv.validate
  probe.json.schema

WorkerGroups:
  public-probe-http
  public-probe-browser
  dns-url-inspector
  market-csv-parser
  local-json-validator
  phone-metadata-probe
  phone-device-probe

External worker fleet:
  public-probe-http-poll-{region}-{001..060}
  public-probe-http-ws-{region}-{001..020}
  dns-url-inspector-poll-{001..010}
  dns-url-inspector-ws-{001..005}
  market-csv-parser-poll-{001..010}
  local-json-validator-poll-{001..010}
  phone-metadata-probe-poll-{001..010}
  phone-device-probe-poll-sg-{001..020}
  phone-device-probe-ws-sg-{001..010}

Submitter principals:
  public-probe-runner
  device-probe-runner
  data-quality-runner
  public-probe-reviewer
  public-probe-ops
```

Provider examples:

| Event | Example source | Normal proof |
| --- | --- | --- |
| `probe.weather.current` | Open-Meteo current weather | JSON fields exist and temperature range is plausible |
| `probe.fx.latest` | ExchangeRate open access | `rates.CNY`, `rates.EUR`, and positive numeric values |
| `probe.market.daily-csv` | Stooq daily CSV | required CSV columns and latest close price > 0 |
| `probe.crypto.price` | CoinGecko simple price | selected asset prices exist and are positive |
| `probe.ip.geo` | ipify / ipwho.is | egress IP or target IP returns country / ASN facts |
| `probe.url.dns` | local URL parser + DNS resolver + optional HTTP HEAD | URL parse result, hostname, DNS answer or classified DNS failure, optional reachability result |
| `probe.http.status` | httpbin status / delay / get | status, timeout, headers, and retry behavior |
| `probe.phone.metadata` | local libphonenumber-style metadata | parse, E.164 format, region, type, possible/valid flags |
| `probe.csv.validate` | local CSV validator | columns, row count, numeric ranges |
| `probe.json.schema` | local schema validator or stable sample endpoint | required fields and type checks |

These are default scenario dependencies, not permanent product dependencies.
Before implementation, verify terms, rate limits, attribution, and availability.
If a provider changes access model, replace the provider in the scenario rather
than adding engine/runtime fallback logic.

Provider activation profiles:

```text
dev/demo:
  may include public internet probes:
    probe.weather.current
    probe.fx.latest
    probe.market.daily-csv
    probe.crypto.price
    probe.ip.geo
    probe.http.status
    probe.url.dns with public hosts

ci/test/offline:
  must not require public internet availability
  use local deterministic probes and fixtures:
    probe.phone.metadata
    probe.csv.validate
    probe.json.schema
    probe.url.dns with local fixtures or reserved invalid hostnames
  expected failures are deterministic:
    DNS_NXDOMAIN, SCHEMA_INVALID, CSV_INVALID, TIMEOUT fixture
```

CI must not fail because Open-Meteo, ExchangeRate, CoinGecko, Stooq, httpbin,
ipify, or any other public provider is slow, rate-limited, blocked, or
unavailable. Public provider checks belong to dev/demo browser verification or
an explicitly opted-in network profile.

Common workload envelope:

Every default event item should support the same operational envelope. This
keeps the scenario simple while making work duration, timeout, and expected
failure visible across all event types.

```text
sleepMs:
  default: 100..750
  long-tail samples: 1,500..3,000
  purpose: concurrency, queue, timeout, and operator-visible duration evidence

timeoutMs:
  default: 2,000..5,000
  purpose: classify slow network or local-tool behavior without crashing worker

expectedOutcome:
  values: SUCCESS, FAILED_EXPECTED, FAILED_UNEXPECTED, TIMEOUT,
          DNS_NXDOMAIN, TLS_ERROR, HTTP_STATUS_UNEXPECTED,
          SCHEMA_INVALID, CSV_INVALID

traceLabel:
  short human-readable case label for console grouping
```

Sleep must be bounded and explicit. It is not a substitute for real probe work:
a weather item still fetches or validates weather data, a DNS item still parses
and resolves a URL, and a schema item still validates a payload.

Failure corpus:

The scenario should include work that is expected to fail at the business/probe
level. Failures should be classified result outcomes, not platform crashes.

```text
probe.url.dns:
  success cases:
    https://open-meteo.com/
    https://example.com/
  expected failure cases:
    https://does-not-exist.public-probe.invalid/
      expectedOutcome: DNS_NXDOMAIN
    https://httpbin.org/status/500
      expectedOutcome: HTTP_STATUS_UNEXPECTED
    https://httpbin.org/delay/5
      expectedOutcome: TIMEOUT when timeoutMs < 5000

probe.http.status:
  expected failure cases:
    https://httpbin.org/status/503
    https://httpbin.org/status/429
```

The console should show these as useful probe results: `SUCCEEDED`,
`FAILED_EXPECTED`, `FAILED_UNEXPECTED`, `DNS_NXDOMAIN`, `TIMEOUT`,
`TLS_ERROR`, `HTTP_STATUS_UNEXPECTED`, or equivalent current result vocabulary.
Do not hide all non-200 responses as generic task failures.

## Scale Contract

Default backend-hosted console profile:

```text
workerInstances >= 100
transportKinds includes polling and websocket
taskShells roughly 5..20
taskItems >= 1,000
projects >= 2
phoneDeviceWorkerGroupWorkers >= 30
phoneDeviceFingerprintProfiles >= 10
allItems include sleepMs / timeoutMs / expectedOutcome
profile dev/demo may include public provider probes
profile ci/test/offline uses local deterministic probe items only
```

Focused tests may use smaller fixtures, but the default console data should not
look like two workers and ten items. The UI must aggregate before drilling into
raw rows.

## Stage-2 Match Fixture

The scenario must prove more than WorkerGroup routing. Use `phone-device-probe`
as the first fingerprint-backed WorkerGroup.

```text
WorkerGroup:
  phone-device-probe
  eventBindings:
    probe.phone.metadata
  defaultAttributes:
    executionProfile: phone-device
    country: SG

Workers:
  at least 20 polling workers
  at least 5 WebSocket workers
  at least 10 fingerprintProfile values
  at least 2 workers per common fingerprintProfile

Example profiles:
  fp-android-sg-a:
    deviceModel: Pixel 7
    osVersion: Android 14
    simOperatorMccMnc: "52501"
    networkOperatorMccMnc: "52501"
    riskTier: low

  fp-android-sg-b:
    deviceModel: Galaxy S23
    osVersion: Android 14
    simOperatorMccMnc: "52505"
    networkOperatorMccMnc: "52505"
    riskTier: medium

Example workers:
  phone-device-probe-poll-sg-001:
    transportHint: polling
    fingerprintProfile: fp-android-sg-a
    fingerprintHash: sha256:dev-fp-android-sg-a-001
    maxConcurrentWork: 2

  phone-device-probe-poll-sg-002:
    transportHint: polling
    fingerprintProfile: fp-android-sg-a
    fingerprintHash: sha256:dev-fp-android-sg-a-002
    maxConcurrentWork: 1

  phone-device-probe-ws-sg-011:
    transportHint: realtime
    adapterId: websocket
    fingerprintProfile: fp-android-sg-b
    fingerprintHash: sha256:dev-fp-android-sg-b-011
    maxConcurrentWork: 2
```

Item payloads may carry Stage-2 requirements:

```text
workerGroupId: phone-device-probe
requiredFingerprintProfile: fp-android-sg-a
requiredNetworkOperatorMccMnc: "52501"
```

The kernel candidate source still comes from
`workerGroupId=phone-device-probe`. Fingerprint fields only select among
workers inside that group.

## Target Operator Views

### Projects

The Projects page should show platform resource summary, not fixture names:

- project code and description
- event count
- WorkerGroup coverage count
- task shell count
- item count
- external worker count
- online polling / online WebSocket counts
- latest result summary

### Project Detail

The Project Detail page should answer:

```text
What can this project do?
Who can submit work?
Which WorkerGroups cover each event?
How much configured capacity exists?
How much runtime capacity is currently online?
Which task shells/items are pending review or already executed?
```

Required sections:

- event capability table with expected payload and normal proof
- failure corpus summary for events that intentionally include NXDOMAIN,
  timeout, TLS, or non-2xx HTTP cases
- event -> WorkerGroup coverage table
- configured coverage versus online capacity
- submitter/API-key principals
- task shells with item counts and lifecycle state
- sample item payload and sample extracted result

### WorkerGroups

The WorkerGroup view is the capability center:

- group id
- event bindings
- project coverage
- adapter nodes
- enabled/draining state
- worker count by transport
- online count by transport
- capacity/load summary
- fingerprint distribution when applicable

### Workers

The Workers page is the instance/debug view:

- worker id
- worker group id
- transport kind
- adapter node
- status / heartbeat
- capacity / active load / lock state
- fingerprintProfile / fingerprintHash for device-like workers
- device/network attributes when applicable
- recent matched item count
- recent rejection or unavailable reason when available

### Task Detail

Task Detail should prove item-level routing and match:

- task shell metadata and API-key submitter provenance
- item count
- item distribution by event
- item distribution by expected success versus expected failure outcome
- item distribution by required fingerprint
- selected WorkerGroup
- candidate count before Stage 2
- matched count after Stage 2
- assigned worker sample
- result sample with extracted values

## Phase Plan

### CCR-0: Inventory And Guardrails

Goal: record current demo paths and prevent owner regression before behavior
changes.

Scope:

- inventory current dev bootstrap creation paths
- inventory frontend mock/real resources that expose demo names
- identify tests that assert `demoApp`, `demoOps`, `demo.dispatch`, or
  `demo-worker-*`
- document the external registration path for both polling and WebSocket
  workers

Acceptance:

- old demo vocabulary call sites are known
- server-internal insertion paths are separated from public API/SDK operation
  paths
- no engine/runtime code is changed

### CCR-1: Public-Path Scenario Bootstrap

Goal: make default data creation demonstrate the platform boundary.

Scope:

- seed only minimal catalog and credential prerequisites in server dev setup
- create task shells through task API with API-key submitter identity
- append 1,000+ items through task item APIs
- register AdapterNode, WorkerGroup, NodeGroupBinding, polling workers, and
  WebSocket workers through external worker APIs or SDK public operations
- keep any local bootstrap runner as an API/SDK client, not a truth owner

Acceptance:

- task security/provenance shows API-key submitter ownership
- worker rows can be explained as external registration products
- default data includes both polling and WebSocket workers
- disabling scenario credentials prevents new scenario resources from being
  created through that path

### CCR-2: Public Probe Catalog

Goal: replace generic demo vocabulary with useful probe events.

Scope:

- introduce `publicProbe`
- introduce at least one additional project, preferably `deviceProbe`, so
  project scope and API-key ownership are visible instead of collapsed into one
  catch-all project
- introduce the probe events listed in Target Scenario
- map each event to one or more WorkerGroups and to one owning project
- keep proof-only events visually separate from the default scenario
- after the new probe catalog is ready, delete the default seed entries for
  `demoApp`, `demoOps`, `demo.dispatch`, and `demo.dispatch.gb` in the same
  change; do not run the old demo catalog and new probe catalog side by side

Acceptance:

- `/resources/projects` no longer depends on `demoApp` or `demoOps`
- default seed data no longer creates `demoApp`, `demoOps`,
  `demo.dispatch`, or `demo.dispatch.gb`
- `/resources/projects` shows at least two real-looking projects with distinct
  event/task/item ownership
- event names describe useful checks
- event descriptions include expected input and normal proof

### CCR-3: WorkerGroup-Centered Console Views

Goal: make capability and coverage group-first in the console.

This phase should happen before expanding the default fleet to 100+ workers.
Otherwise the current raw worker-row view will only become noisier.

Current API starting point:

- `/api/v1/runtime/workers` already exposes worker model status, transport
  reachability, transport-online boolean, active connection hints, lock state,
  adapter id, and worker attributes.
- `/api/v1/catalog/event-capabilities` already exposes event-level worker ids
  and online worker ids based on transport reachability.
- Dispatch eligibility is not the same fact as model status or transport
  reachability. If the console needs a first-class eligibility count, add or
  extend a server read model instead of calculating it from frontend rows.

Scope:

- add or surface a WorkerGroup inventory view when API support is available
- define the server read contract used by the console for group coverage. It
  may extend existing runtime/catalog endpoints or add a dedicated
  WorkerGroup coverage read API, but it must expose the facts below as
  separate fields instead of asking the frontend to infer them
- update project detail to show event -> WorkerGroup coverage before worker
  instance rows
- show configured coverage separately from online capacity
- split visible online/capacity fields into configured coverage, worker model
  status, transport reachability, dispatch eligibility, and event online
  capacity instead of collapsing them into one `Online` number
- show AdapterNode/NodeGroupBinding enabled/draining state

Acceptance:

- a user can tell why an event maps to a WorkerGroup
- the frontend has a server-owned source for transport reachability and event
  online capacity rather than deriving those facts solely from worker model
  `status`
- project detail does not collapse coverage into a long worker id list
- configured coverage and runtime online capacity are visibly different facts
- Workers, Project Detail, and Discovery no longer disagree about online worker
  counts for the same event/fleet snapshot
- online-capacity labels make clear whether the number comes from transport
  reachability, worker status, or dispatch eligibility

### CCR-4: Instance Fleet And Stage-2 Match Proof

Goal: make worker rows prove runtime scale and Stage-2 matching.

Scope:

- seed 100+ external workers across polling and WebSocket transport
- seed 30+ `phone-device-probe` workers with fingerprint distribution
- expose fingerprint and device/network attributes for device-like workers
- keep crawler/parser attributes sparse and operational
- add/read match evidence where available: candidate count, matched count,
  selected worker, rejection/unavailable reason
- add a server integration proof for fingerprint Stage-2 matching, reusing the
  current task API and external worker registration path. The proof should
  register at least two workers in the same WorkerGroup with different
  fingerprint attributes, create/append/seal a task through the API path, and
  verify that only the matching fingerprint worker receives the item

Acceptance:

- at least one local item batch proves group-first routing followed by
  fingerprint-based worker selection inside the selected group
- the Stage-2 proof is enforced by a server-side integration test, not only by
  console observation
- the proof verifies both negative and positive cases: a worker in the selected
  group with a non-matching fingerprint is not assigned, and a matching worker
  is assigned
- Stage-2 proof does not rely on fake global worker capability
- the UI requires aggregation and drill-down rather than hand-reading every
  worker row

### CCR-5: Task Starters And Item Volume

Goal: make task creation useful without auto-running hidden work.

Scope:

- update task starters to use the default project set, including `publicProbe`
  and `deviceProbe`
- create a modest number of task shells, roughly 5-20
- append 1,000+ items across those shells
- distribute item payloads across events, fingerprint profiles, and expected
  failure outcomes
- select public-provider-backed items only in the dev/demo profile; CI,
  test, and offline profiles must use local deterministic item sources
- include `sleepMs`, `timeoutMs`, `expectedOutcome`, and `traceLabel` on every
  starter item
- write expected probe failures as item/result outcome details that can be
  aggregated by the console; do not introduce new task terminal reasons as part
  of this phase
- stop generated tasks at draft/review/sealed-pending-approval by default
- require an explicit operator action before active dispatch

Example items:

```text
probe.url.dns:
  url: https://does-not-exist.public-probe.invalid/
  workerGroupId: dns-url-inspector
  sleepMs: 250
  timeoutMs: 1500
  expectedOutcome: DNS_NXDOMAIN
  traceLabel: dns-nxdomain-case
  expected:
    hostname parsed
    dnsOutcome == DNS_NXDOMAIN
    httpRequestSkipped == true

probe.phone.metadata:
  phoneNumber: "+14155552671"
  defaultRegion: US
  workerGroupId: phone-device-probe
  requiredFingerprintProfile: fp-android-sg-a
  sleepMs: 500
  timeoutMs: 3000
  expectedOutcome: SUCCESS
  traceLabel: phone-fingerprint-a
  expected:
    e164 exists
    regionCode == US
    possible == true
```

Acceptance:

- generated tasks do not run merely because the page opened
- item count and distribution are visible in task/project pages
- item payloads are understandable without reading source code
- expected probe failures are visible as classified outcomes, not hidden as
  generic worker or platform errors
- CI verification does not depend on public internet providers, DNS outside
  controlled fixtures, third-party rate limits, or third-party terms
- task terminal state remains governed by existing lifecycle/result rules while
  item-level probe outcomes explain business success or failure
- result samples can show extracted values such as temperature, CNY/EUR rates,
  latest CSV close, IP country/ASN, DNS answers, timeout/NXDOMAIN reasons,
  status code, phone metadata, or schema pass

### CCR-6: Proof And Regression Coverage

Goal: keep the scenario useful without weakening kernel boundaries.

Scope:

- update server/controller/frontend tests that assert old demo names
- remove old demo-name assertions in the same convergence path that removes
  old default seed entries; do not leave compatibility assertions for
  `demoApp`, `demoOps`, `demo.dispatch`, or `demo.dispatch.gb`
- add focused coverage for API-key task creation and external worker
  registration paths used by the scenario
- add or update a server integration test for fingerprint Stage-2 matching.
  The test should be similar in intent to the existing worker attribute
  routing proof, but use the new probe event and WorkerGroup/fingerprint
  scenario instead of `demo.dispatch`
- add CI-safe fixture coverage for local-only probe items and expected failure
  classification without public network calls
- add frontend tests for WorkerGroup-first project detail and worker instance
  views
- browser-check backend-hosted pages

Acceptance:

- focused tests pass
- CI test profile runs without public internet access
- server integration coverage proves non-matching fingerprint workers are not
  assigned and matching fingerprint workers are assigned
- console works at `http://localhost:8088/`
- no engine/runtime contract docs are changed unless behavior actually changes
- no ordinary scheduling path falls back to event/project/all-worker scans

## Non-Goals

This roadmap does not do:

1. No task lifecycle, assignment, retry, result, or terminal semantic changes.
2. No production multi-tenant SaaS model.
3. No billing, quota, credit, or worker earnings.
4. No generic CRUD/page-schema framework.
5. No frontend mock data as backend truth.
6. No server-owned hidden task or worker insertion path for default resources.
7. No WorkerGroup capability truth derived from worker attributes.
8. No phone-number prefix metadata treated as live carrier, SIM, roaming, HLR,
   or portability truth.
9. No phone/device attributes on crawler/parser workers just to make rows look
   richer.
10. No new task terminal states just to display expected probe failures.
11. No compatibility track for old demo names after migration.

## Verification Profile

For roadmap implementation changes, prefer:

```text
Frontend:
  cd frontend
  corepack pnpm typecheck
  corepack pnpm test:run
  corepack pnpm build

Server focused:
  .\mvnw.cmd -q -pl xa-mass-server -am "-Dtest=TaskApiControllerTest,*Project*Test,*Worker*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
  .\mvnw.cmd -q -pl xa-mass-server -am -DskipTests compile

Browser check:
  http://localhost:8088/resources/projects
  http://localhost:8088/resources/projects/{projectCode}
  http://localhost:8088/resources/workers
  http://localhost:8088/runtime/discovery
```

Profile expectations:

```text
dev/demo:
  may seed public-provider probe items
  may browser-check real public provider results
  must keep generated tasks stopped until explicit approval/run

ci/test/offline:
  must not call public provider APIs
  must use local deterministic item fixtures
  must still prove API-key task creation, external worker registration,
  WorkerGroup-first routing, fingerprint Stage-2 match, and expected-failure
  result classification
```

Use broader server E2E only when the change touches task creation, external
worker registration, transport presence, dispatch, or result flows beyond
display and bootstrap vocabulary.
