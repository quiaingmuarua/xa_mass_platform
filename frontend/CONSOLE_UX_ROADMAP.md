# Console UX Roadmap

Status: direction roadmap for the lightweight Vue/Vite control console.

This roadmap is frontend-only. It must not drive server, SDK, engine,
transport, or runtime ownership changes.

## Summary

The current frontend uses:

```text
Vite + Vue 3 + TypeScript
Element Plus components
hand-written routes, API clients, pages, and shell
```

The goal is not to adopt a full admin framework. The goal is to add a small,
project-owned commercial-console component layer so pages stop looking like
engineering demos while staying easy for agents to understand and modify.

Target shape:

```text
Element Plus
  -> xa-mass console-kit
      -> domain pages
```

The console-kit should provide reusable page patterns, navigation rules, and
security/credential widgets. It should not become a schema-driven CRUD engine.
It should also not replace the existing `src/layouts` shell in one large move.
The first implementation path is to converge current layouts and components,
then extract stable primitives only where repetition is proven.

## Core Rules

1. Keep Vue / Vite / TypeScript / Element Plus as the baseline.
2. Do not import a full admin runtime such as Vben, Soybean, or a CRUD platform.
3. Borrow visual and interaction patterns from mature dashboards, but own the
   implementation inside this repo.
4. Keep routes explicit in `src/router/modules/*`; do not generate route trees
   from schemas.
5. Keep page logic domain-shaped in `src/pages/*`; do not introduce generic page
   builders.
6. Frontend permissions are UX visibility only. Backend authorization remains
   the real enforcement boundary.
7. Operator console and API-key viewer must be separate shell modes, not one
   menu hidden with ad-hoc conditions.
8. API-key secrets are password-like. Show them only once when created or
   approved, never persist API-key secrets in browser storage, and never show
   internal viewer/session credentials as user-facing concepts. A short-lived
   viewer session credential may be stored in `sessionStorage` only as an
   implementation detail; it must never be copied to `localStorage` or rendered
   as page content.
9. Components should make pages clearer, not hide ownership. Avoid large
   pass-through wrappers that only rename Element Plus.

## Console-Kit Target

Add a small project-owned component layer under:

```text
src/console-kit/
```

This is a target inventory, not a first-commit checklist. Add folders only when
the first real consumer exists.

Target structure:

```text
layout/
  ConsolePage.vue
  ConsolePageHeader.vue
  OperatorShell.vue
  ViewerShell.vue

navigation/
  ConsoleSidebar.vue
  visible-routes.ts
  menu-model.ts

data/
  MetricCard.vue
  MetricGrid.vue
  ResourceTable.vue
  FilterToolbar.vue
  StatusBadge.vue

feedback/
  EmptyState.vue
  ErrorState.vue
  LoadingSection.vue

security/
  CredentialInputCard.vue
  SecretRevealDialog.vue

code/
  JsonBlock.vue
  ResultPayloadViewer.vue
```

These components should wrap Element Plus and app CSS variables. They should be
thin, typed, and easy to replace.

Current repo alignment:

```text
src/layouts:
  keep owning AppShell / AppHeader / AppSidebar until shell split is actually
  implemented

src/components:
  existing PageEmptyState / PageErrorState / PageSectionSkeleton are migration
  candidates, not obsolete code

src/console-kit:
  new reusable product primitives only; no same-module pass-through wrappers
```

Extraction threshold:

```text
visual/data primitives:
  extract only after at least two existing pages repeat the same concept

security/credential primitives:
  may be extracted with one first consumer when the component encodes a real
  invariant, such as one-time secret reveal or no-secret-persistence behavior
```

## Shell And Navigation

Route meta should become the single source for menu visibility. This should be
done by extending the existing typed route meta, not by introducing a parallel
menu schema.

Recommended route meta extension:

```ts
meta: {
  shell: 'operator' | 'submitter-viewer' | 'public'
  navGroup?: 'dashboard' | 'resources' | 'tasks' | 'runtime' | 'system' | 'viewer'
  title: string
  icon: string
  order: number
  hidden: boolean
  menuVisible: boolean
  requiresAuth: boolean
  permissions: string[]
}
```

Rules:

```text
visible-routes(shell):
  shell matches
  menuVisible is true
  hidden is false
  required permissions are present
```

Migration rule:

```text
1. UX-1 may temporarily treat missing `shell` as `operator` inside the menu
   helper to avoid disappearing routes during one small migration.
2. The same phase must add route-meta coverage that fails if any route remains
   without an explicit `shell`.
3. The fallback is removed once all route modules are migrated.
```

`navGroup` is initially a grouping hint layered on top of the existing route
children tree. It is not a separate source of route truth and should not replace
`src/router/modules/*` until there is a concrete shell/menu reason to flatten
the navigation model.

`ConsoleSidebar` should render a prepared menu model. It should not own
permission logic. In the current implementation this likely means evolving
`AppSidebar` first, then renaming only if the shell split makes the old name
misleading.

Shells:

```text
operator:
  dashboard / resources / tasks / runtime / system

submitter-viewer:
  viewer overview / own tasks / usage

public:
  forbidden / not found
```

## Visual Direction

The UI should feel like a product dashboard, not a generated CRUD console.

Defaults:

```text
typography:
  keep readable app typography, but define purposeful page title, section title,
  metric value, mono, and caption styles

color:
  define CSS variables for surface, border, muted text, success, warning,
  danger, info, and credential/security accents

layout:
  consistent page width, header rhythm, card spacing, grid gaps, and table
  density

motion:
  light page-load and section transitions only; no decorative motion that
  distracts from operator workflows
```

Avoid:

```text
1. raw Element Plus pages with unrelated spacing
2. one-off metric card styles per page
3. table action columns with inconsistent button ordering
4. credential flows that expose internal implementation terms
5. generic CRUD labels for task / worker / result concepts
```

## Phase Plan

### Phase UX-0: Inventory And Guardrails

Goal: lock current frontend ownership and identify the highest-value reuse
points.

Scope:

```text
1. inventory duplicated page header, metric, table, empty, error, loading, and
   secret dialog patterns
2. mark pages that should stay domain-specific
3. document operator shell vs submitter-viewer shell expectations
4. add a small source guard or test to prevent submitter viewer pages from
   exposing `mass_sess` / session-token language
5. decide whether existing `src/components/Page*` components stay in
   `src/components` or move to `src/console-kit/feedback`
```

Acceptance:

```text
1. duplicated component candidates listed
2. no behavior change required
3. API-key viewer terminology is user-facing API key / secret only
4. no new broad component layer exists without a first consumer
```

### Phase UX-1: Shell And Menu Model

Goal: make operator and viewer navigation explicit and agent-friendly before
visual refactors spread across pages.

Scope:

```text
1. extend route meta with `shell` and optional `navGroup`
2. add `visible-routes.ts` and `menu-model.ts`
3. make the current AppSidebar consume prepared operator route groups
4. make submitter viewer use an explicit viewer shell or route mode
5. stop ad-hoc hiding inside page components where route meta is sufficient
6. keep `navGroup` as a grouping hint over the current route tree; do not
   replace children-based route modules with a flat schema
```

Acceptance:

```text
1. operator menu does not show viewer-only routes unless intentionally linked
2. submitter viewer does not show operator system/runtime/resource routes
3. permissions still hide UX-only menu entries
4. route guard behavior remains backend-auth compatible
5. route meta remains explicit in `src/router/modules/*`
6. every route has explicit `shell` by the end of the phase; no permanent
   missing-shell fallback remains
```

### Phase UX-2: Console-Kit Foundation

Goal: create reusable primitives after shell ownership is clear.

Scope:

```text
1. add `src/console-kit` with only folders needed by migrated components
2. introduce ConsolePage as the page-header/layout migration target
3. migrate or wrap existing empty/error/loading/page-header patterns
4. add StatusBadge only if at least two pages consume it immediately
5. add SecretRevealDialog and CredentialInputCard for IAM/API-key flows
6. defer MetricCard / MetricGrid / ResourceTable / FilterToolbar until task/runtime pages are
   refactored
7. keep all components thin wrappers over Element Plus
```

Acceptance:

```text
1. existing pages still render
2. no route/auth behavior changes
3. new components have focused tests or page tests where behavior matters
4. no generic CRUD page builder or schema-driven table is introduced
5. ConsolePage exists before Task / Result page refactors depend on it
```

### Phase UX-3: Credential And IAM Pages

Goal: make IAM/API-key pages commercially understandable.

Scope:

```text
1. finish API-key viewer as key-secret based usage viewer
2. make session/internal viewer credential invisible to users
3. update API Keys create/approve secret dialog to show Key ID + Secret once
4. use shared SecretRevealDialog / CredentialInputCard
5. keep API-key secret out of localStorage/sessionStorage; only the server-issued
   viewer session credential may be kept in sessionStorage
6. clear stored viewer session credentials on 401, explicit exit, or failed
   profile refresh
7. do not show backend session key prefixes as API-key prefixes unless the
   server returns the source API-key prefix explicitly
```

Acceptance:

```text
1. user sees Key ID and Secret as separate concepts
2. secret is one-time only
3. list/detail never expose raw secret or credential hash
4. API-key viewer can inspect profile and usage without operator auth
5. page copy does not mention session token, attach session, or raw internal
   viewer credentials
6. raw API-key secret is never written to localStorage or sessionStorage
```

### Phase UX-4: Task / Result Product Pages

Goal: make the core product surface feel intentional.

Scope:

```text
1. refactor Task List using ConsolePage, FilterToolbar, and the smallest useful
   table helper
2. refactor Task Detail around summary, lifecycle, shared config, and result
   sections
3. add ResultPayloadViewer for JSON-safe result rows
4. keep create/append/control actions explicit; do not build a generic CRUD page
5. preserve task/result API ownership; frontend layout must not reintroduce
   projection truth for public result rows
```

`ConsolePage` should already exist from UX-2. `FilterToolbar` and the table
helper are introduced in this phase only when Task List becomes the first real
consumer.

Acceptance:

```text
1. task pages keep existing API behavior
2. action buttons remain permission-aware
3. result rows use public result APIs, not internal projection review APIs
4. layout is consistent on desktop and usable on narrow screens
```

### Phase UX-5: Runtime / Worker / Trace Pages

Goal: make operator diagnostics readable without hiding kernel ownership.

Scope:

```text
1. refactor Runtime Discovery and worker pages with shared metrics and status
   badges
2. add consistent event/timeline visual patterns for trace-oriented pages
3. keep trace/query UI read-only and bounded
4. avoid scan-heavy frontend polling loops
5. keep worker capability display grounded in `eventBindings`, not old flat
   supported-project/event fields as independent truth
```

Acceptance:

```text
1. runtime pages do not imply frontend owns scheduling state
2. worker capability display uses eventBindings as truth
3. diagnostics pages clearly distinguish declared facts, reachability, load, and
   trace evidence
```

## Test Strategy

Use focused tests, not visual snapshot sprawl. Shared Vue components should use
Vitest plus Vue Test Utils for slot, state, permission, and credential-handling
behavior. Do not add screenshot or broad E2E snapshots for console-kit
primitives.

Required coverage:

```text
1. route menu visibility by shell and permissions
2. credential secret handling does not persist raw API-key secret
3. API-key viewer does not expose session-token language
4. shared components render core slots and states
5. representative pages still load and call the right API clients
6. route meta tests cover every route having explicit shell once UX-1 lands
```

Manual checks:

```text
1. `corepack pnpm build`
2. backend-hosted `http://localhost:8088/`
3. `http://localhost:8088/submitter-viewer`
4. operator admin mode vs viewer mode menu visibility
5. API-key create/approve one-time secret dialog
```

## Non-Goals

This roadmap does not:

```text
1. replace Element Plus
2. adopt a full admin framework
3. add a schema-driven CRUD system
4. move backend authorization into frontend
5. change server/SDK/engine APIs
6. implement billing, quota, or worker earnings UI
7. make API-key viewer a full login product
8. clone or vendor a third-party admin template into the repo
```

## Reference Policy

Mature dashboards may be used as design references:

```text
Vben / Soybean:
  layout density, route/menu organization, admin polish; reference only because
  their runtime is too heavy for this repo

vue-pure-admin:
  Element Plus admin patterns, table/action density, and route/menu polish;
  reference only, not a runtime dependency

shadcn/ui / shadcn-vue-admin:
  owned component recipe style and modern page composition; reference only, not
  a runtime dependency

Stripe / Vercel dashboards:
  API key, secret, usage, and developer-console interaction patterns
```

Do not copy their runtime architecture. Convert useful patterns into small
repo-owned Vue components.
