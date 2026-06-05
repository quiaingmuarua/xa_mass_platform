# Console Frontend Productionization Roadmap

Status: active roadmap; operator auth/login, route/menu model, and part of
console-kit are already implemented. Shell token convergence, operator chrome,
console-kit visual convergence, product-page maturity, and bundle baseline
remain open.

This roadmap is frontend-only. It must not drive server, SDK, engine,
transport, runtime, trace, or backend authorization ownership changes.

## Goal

Converge the current XA Mass frontend from a development-demo console into a
real commercial management backend while keeping the implementation explicit,
domain-shaped, testable, and repo-owned.

Target shape:

```text
Vue 3 + Vite + TypeScript + Element Plus
  -> src/layouts shell
  -> src/console-kit primitives
  -> src/pages domain pages
```

The shell owns navigation chrome. Console-kit owns reusable page composition and
domain-safe UI primitives. Pages own domain workflow logic. None of these layers
may become a generic CRUD framework, route generator, backend-driven menu, or
admin-template clone.

## Reference Policy

The reference projects are inputs, not dependencies.

Primary engineering reference:

- `un-pany/v3-admin-vite`
  - use as the main reference for Vue 3 + Vite + TypeScript + Element Plus admin
    shell shape, explicit route/menu organization, sidebar density, breadcrumb
    behavior, and permission-aware navigation
  - do not import its runtime dependencies, route generator, state model,
    UnoCSS/SCSS setup, axios layer, or full framework skeleton

Visual and permission-maturity reference:

- `pure-admin/pure-admin-thin`
  - use for Element Plus admin polish, restrained layout density,
    permission-aware menu/user affordances, and production-feeling interaction
  - do not adopt its plugin system, config conventions, build skeleton, or
    framework-level abstractions

Login/RBAC interaction reference only:

- `zxwk1998/vue-admin-better`
  - use only for login flow, RBAC feedback, role/permission visibility, and
    unauthorized-route interaction patterns
  - do not adopt its engineering skeleton, dynamic backend-route architecture,
    component library, or broad admin runtime

## Current Baseline

Already implemented:

- Operator auth/login baseline from
  `roadmap/SERVER_OPERATOR_AUTH_PROD_TRUST_HARDENING_ROADMAP.md`.
- `/login` route in `src/router/modules/app.ts`.
- Login page at `src/pages/app/LoginPage.vue`.
- Backend auth config/login/logout/CSRF handling in `src/auth/provider.backend.ts`
  and `src/auth/backend-auth.ts`.
- Auth guard redirects unauthenticated protected routes to `/login` and keeps
  permission denial on `/forbidden`.
- Explicit route `shell`, `navGroup`, `hidden`, `menuVisible`, `requiresAuth`,
  and `permissions` metadata.
- `src/router/visible-routes.ts` and `src/router/menu-model.ts`.
- Route/menu tests for explicit shell metadata, permission visibility, and
  submitter-viewer separation.
- `AppSidebar.vue` consumes the prepared menu model.
- `src/console-kit` contains:
  - `layout/ConsolePage.vue`
  - `data/MetricCard.vue`
  - `data/MetricGrid.vue`
  - `data/FilterToolbar.vue`
  - `data/StatusBadge.vue`
  - `security/CredentialInputCard.vue`
  - `security/SecretRevealDialog.vue`
- Dashboard, Tasks List, Submitter Viewer, and API Keys already consume
  console-kit components.
- Submitter Viewer and API Keys tests cover API-key terminology and source
  secret persistence rules.

Open debt:

- `src/layouts/AppHeader.vue` is still a dev-demo page header.
- `src/layouts/AppSidebar.vue` is fixed-width and not collapsible.
- `src/app/styles.css`, layouts, LoginPage, and console-kit components still
  contain raw colors and parallel visual choices.
- `ConsolePage.vue` currently leans toward hero/marketing composition instead
  of a restrained admin page container.
- `submitter-viewer` and `public` shell modes are lightweight by current design
  and must not accidentally inherit operator navbar/sidebar.
- `bootstrap.ts` globally installs Element Plus. The build currently passes but
  emits a large main-chunk warning.

## Ownership Boundaries

```text
src/layouts:
  AppShell / AppSidebar / AppNavbar or converged AppHeader
  shell state, sidebar width, operator navbar, breadcrumb, user dropdown

src/router:
  explicit route metadata, visible-routes, menu-model, guards
  no backend-driven menu or second schema

src/auth:
  frontend auth state, backend auth mode discovery, login/logout/CSRF helpers
  no duplicate login owner inside layouts or pages

src/console-kit:
  reusable page composition, data display, security widgets
  no shell chrome, no CRUD engine, no schema-driven table

src/pages:
  domain workflow logic and page-specific API composition
  no route/menu ownership and no generic page DSL
```

## Hard Rules

1. No new npm runtime or dev dependencies unless a later explicit decision
   accepts the cost.
2. Do not clone, vendor, or copy a third-party admin template.
3. Keep routes explicit in `src/router/modules/*`.
4. Keep route meta as the menu visibility source. Do not create a parallel menu
   schema.
5. Keep frontend permissions as UX visibility only. Backend authorization is the
   real enforcement boundary.
6. Keep operator console, submitter-viewer, and public routes as separate shell
   modes.
7. Do not create another login page or move the current login page to
   `src/pages/auth`.
8. Do not change backend auth contracts, CSRF behavior, SDK API-key behavior, or
   submitter credential behavior.
9. API-key secrets are password-like. Show them once, never persist raw secrets,
   and never render internal viewer/session credentials as user-facing concepts.
10. Tests stay behavior-focused. Do not add broad screenshot or visual snapshot
    suites for shell or console-kit primitives.
11. Add abstractions only when they encode real reuse, ownership, or invariant
    value. Avoid Element Plus pass-through wrappers.
12. Do not expand frontend runtime data into CRUD-heavy pages. Runtime/worker
    surfaces are bounded diagnostics unless backend ownership changes.

## Do Not Start With

Do not start by importing a reference project, adding a state library, rewriting
auth/login, or redesigning content pages. Start by classifying the current
baseline and token debt, then stabilize the shell.

## Phase Plan

### FRONT-0 Current Baseline And Residue Scan

Goal: produce the first executable inventory so later phases do not repeat
already-landed route/menu/auth/console-kit work.

Scope:

```text
1. inventory raw hex / rgba sites in:
   - src/app/styles.css
   - src/layouts/AppHeader.vue
   - src/layouts/AppSidebar.vue
   - src/layouts/AppShell.vue
   - src/pages/app/LoginPage.vue
   - currently used src/console-kit components
2. classify each raw-color site:
   - shell token work
   - login polish
   - console-kit token convergence
   - page-local deferred debt
3. confirm auth/login baseline remains implemented
4. confirm route/menu baseline remains implemented
5. confirm submitter-viewer and public routes remain lightweight shell modes
6. classify existing console-kit primitives as mainline, visual debt, or
   rollback candidate
7. record current build and test status, including any chunk warning
```

Acceptance:

```text
1. route/menu/auth baseline is documented as current, not future work
2. token debt is classified by owner
3. console-kit current consumers are listed
4. submitter-viewer shell boundary is explicit
5. next slice is FRONT-1, not page redesign
```

Suggested verification:

```powershell
cd frontend
corepack pnpm test:run
corepack pnpm build
```

### FRONT-1 Shell Token Foundation

Goal: introduce global shell tokens without changing visible output.

Scope:

```text
1. add CSS custom properties to :root in src/app/styles.css
2. replace global shell raw colors in src/app/styles.css
3. replace scoped raw colors in AppHeader.vue and AppSidebar.vue
4. add --sidebar-width and --sidebar-width-collapsed
5. record console-kit raw colors as follow-up debt, but do not create a second
   console-kit token system
```

Acceptance:

```text
1. global and shell styles use tokens outside token declarations
2. rendered output is visually unchanged
3. console-kit token debt remains visible for FRONT-4
4. build and tests pass
```

### FRONT-2 Operator Shell

Goal: replace the demo shell chrome with a production-oriented operator shell.

Scope:

```text
1. add collapsed state to AppShell.vue
2. pass collapsed state and toggle handler to AppSidebar
3. bind el-menu :collapse in AppSidebar
4. create AppNavbar.vue or converge AppHeader.vue
5. add hamburger, route-derived breadcrumb, user dropdown, and logout
6. move operator mode switch into the dropdown
7. show operator mode switch only in mock auth or backend dev-header mode
8. remove visible top-bar Mock API / Backend API and auth diagnostic badges
9. keep submitter-viewer and public routes off the operator navbar/sidebar
```

Acceptance:

```text
1. sidebar toggles between 280px and 64px without layout overflow
2. navbar shows breadcrumb and operator identity
3. logout calls existing logout() from @/auth/use-auth and routes to /login
4. session auth mode hides operator mode switch
5. submitter-viewer/public shell behavior is unchanged
6. build and tests pass
```

### FRONT-3 Login/Auth Surface Polish

Goal: align the existing login/auth frontend with the commercial shell without
rebuilding auth.

Scope:

```text
1. keep LoginPage owner at src/pages/app/LoginPage.vue
2. keep /login route metadata in src/router/modules/app.ts
3. keep backend auth provider and CSRF behavior unchanged
4. token-polish LoginPage colors if FRONT-0 classified them as login-surface
   debt
5. keep bounded invalid-credential and generic network/server error display
6. verify guard behavior:
   - unauthenticated protected route -> /login
   - authenticated /login -> /
   - permission denied -> /forbidden
```

Acceptance:

```text
1. no duplicate login page exists
2. login still uses existing login() flow
3. backend session login remains real and CSRF-aware
4. mock and backend auth tests pass
```

### FRONT-4 Console-Kit Visual Convergence

Goal: make existing console-kit primitives production-usable and token-aligned.

Implemented baseline:

```text
1. ConsolePage exists and has multiple consumers
2. MetricCard / MetricGrid exist and have multiple consumers
3. FilterToolbar exists and is used by Tasks List
4. StatusBadge exists and has multiple consumers
5. CredentialInputCard and SecretRevealDialog exist for credential flows
```

Remaining scope:

```text
1. align console-kit colors, radii, shadows, and typography to app tokens
2. reduce ConsolePage hero-like treatment into a denser admin page container
3. keep MetricCard and StatusBadge quiet and scan-friendly
4. migrate feedback components only if doing so reduces real duplication
5. defer ResourceTable until repeated table behavior is proven
6. keep console-kit thin over Element Plus plus app tokens
```

Acceptance:

```text
1. console-kit no longer defines a parallel visual system
2. ConsolePage feels like an admin page container, not a landing hero
3. existing consumer pages still render
4. no route/auth/API behavior changes
5. build and tests pass
```

### FRONT-5 Product Page Maturity

Goal: use the stable shell and console-kit to improve important domain pages
without hiding backend ownership.

Scope:

```text
Credential and IAM:
  1. keep Key ID and Secret separate
  2. keep raw secret one-time only
  3. keep internal viewer credential invisible to users
  4. clear viewer session credential on 401, explicit exit, or failed refresh

Task and Result:
  1. refactor Task Detail around summary, lifecycle, shared config, and results
  2. add ResultPayloadViewer for JSON-safe result rows
  3. keep create/append/control actions explicit
  4. use public task/result APIs; do not reintroduce projection truth

Runtime, Worker, Trace:
  1. improve diagnostics readability with bounded metrics/status patterns
  2. keep trace/query UI read-only and bounded
  3. avoid scan-heavy polling loops
  4. keep worker capability display grounded in eventBindings
```

Acceptance:

```text
1. pages keep existing API behavior
2. permission-aware actions remain explicit
3. runtime/worker/trace pages do not imply frontend runtime ownership
4. no generic CRUD page builder is introduced
5. build and tests pass
```

### FRONT-6 Frontend Runtime Baseline

Goal: keep the commercial console from growing a permanently heavy first-load
bundle.

Scope:

```text
1. record current build output and chunk warning
2. decide whether full Element Plus registration remains acceptable
3. evaluate route-level code splitting and Element Plus import strategy
4. avoid new dependencies unless explicitly accepted
```

Acceptance:

```text
1. build still passes
2. chunk warning is resolved or explicitly accepted
3. shell/console-kit changes do not increase the main chunk without review
```

## Test Strategy

Use focused tests:

```text
1. route menu visibility by shell and permissions
2. auth guard login/forbidden behavior
3. navbar dropdown and sidebar collapse behavior
4. credential secret handling and no raw secret persistence
5. shared components render core slots and states
6. representative pages still call the right API clients
7. token convergence through targeted DOM/class assertions where behavior
   matters, not screenshots
```

Manual checks:

```text
1. corepack pnpm build
2. corepack pnpm test:run
3. backend-hosted http://localhost:8088/
4. backend-hosted http://localhost:8088/submitter-viewer
5. operator admin mode vs viewer mode menu visibility
6. API-key create/approve one-time secret dialog
```

## Non-Goals

```text
1. replace Element Plus
2. adopt a full admin framework
3. add a schema-driven CRUD system
4. move backend authorization into frontend
5. change server/SDK/engine APIs
6. implement billing, quota, or worker earnings UI
7. make API-key viewer a full login product
8. clone or vendor a third-party admin template
9. add mobile/responsive layout before a separate decision accepts the scope
```

## Completion Criteria

This roadmap is complete only when:

```text
1. route/menu/auth baseline has been residue-scanned and stays single-source
2. shell/global tokens exist and shell raw colors are removed or confined to
   token declarations
3. operator shell has collapsible sidebar, navbar, breadcrumb, user dropdown,
   and logout
4. submitter-viewer and public shell behavior remain intentionally lightweight
5. login/auth surface is polished without duplicating auth ownership
6. console-kit consumes app tokens and no longer carries a parallel visual
   system
7. ConsolePage and MetricCard read as commercial admin components
8. credential/IAM pages preserve Key ID + Secret semantics and no-secret
   persistence rules
9. task/result/runtime/worker/trace pages remain domain-shaped and bounded
10. build and focused tests pass
11. bundle-size debt is resolved or explicitly accepted
```
