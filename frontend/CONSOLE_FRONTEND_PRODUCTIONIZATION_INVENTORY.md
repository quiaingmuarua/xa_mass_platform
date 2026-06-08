# Console Frontend Productionization Inventory

Status: current FRONT-0 inventory for
`CONSOLE_FRONTEND_PRODUCTIONIZATION_ROADMAP.md`.

This file is the durable handoff for frontend productionization. Keep it
frontend-owned; do not use it as server, SDK, engine, transport, runtime, or
trace truth.

## Verification Baseline

Recorded on 2026-06-05 from `frontend/`.

| Command | Result | Notes |
| --- | --- | --- |
| `corepack pnpm test:run` | pass | 35 files, 80 tests |
| `corepack pnpm typecheck` | pass | `vue-tsc --noEmit` |
| `corepack pnpm build` | pass with warning | `vue-tsc -b && vite build` |

Build chunk baseline:

| Artifact | Raw | Gzip | Classification |
| --- | ---: | ---: | --- |
| `dist/assets/index-bfnbL0DU.js` | 929.20 kB | 296.60 kB | main chunk warning |
| `dist/assets/index-B1D2_oS1.css` | 353.46 kB | 47.88 kB | Element Plus global CSS likely contributor |
| `dist/assets/runtime-core.esm-bundler-18ao9Z66.js` | 65.98 kB | 25.90 kB | Vue runtime chunk |

Post-implementation build baseline:

| Artifact | Raw | Gzip | Classification |
| --- | ---: | ---: | --- |
| `dist/assets/index-DTJl_Iqs.js` | 930.14 kB | 296.81 kB | accepted main chunk warning |
| `dist/assets/index-CVxYA6Jc.css` | 354.96 kB | 48.28 kB | Element Plus global CSS contributor |
| `dist/assets/runtime-core.esm-bundler-18ao9Z66.js` | 65.98 kB | 25.90 kB | Vue runtime chunk |

Warning text:

```text
(!) Some chunks are larger than 500 kB after minification. Consider:
- Using dynamic import() to code-split the application
- Use build.rolldownOptions.output.codeSplitting to improve chunking
- Adjust chunk size limit for this warning via build.chunkSizeWarningLimit.
```

Current likely contributor: `src/app/bootstrap.ts` globally installs
`ElementPlus` and imports `element-plus/dist/index.css`.

FRONT-6 decision after implementation: keep the warning accepted for this
roadmap. A trial `build.rolldownOptions.output.advancedChunks` split isolated
Element Plus, but it still produced an oversized Element Plus chunk and the
option is deprecated in Vite 8. Do not hide the warning with
`chunkSizeWarningLimit`. The next real optimization is an explicit Element Plus
on-demand import/registration track after shell/product-page behavior settles.

## Baseline Proof

| Area | Current Evidence | Classification |
| --- | --- | --- |
| Operator auth/login | `src/auth/provider.backend.ts`, `src/auth/backend-auth.ts`, `src/pages/app/LoginPage.vue` | implemented baseline |
| Auth mode discovery | `/api/v1/auth/config` via `loadBackendAuthConfig()` and `BackendAuthConfig.authMode` | implemented baseline |
| CSRF/session handling | `setOperatorCsrfToken()`, `currentOperatorCsrfHeader()`, `clearOperatorSessionAuth()` | implemented baseline |
| Route metadata | `src/router/modules/*`, `src/router/routes.test.ts` | implemented baseline |
| Menu model | `src/router/visible-routes.ts`, `src/router/menu-model.ts`, `src/router/menu.test.ts` | implemented baseline |
| API-key viewer/public shell separation | `AppShell.vue`, `AppShell.test.ts`, `menu.test.ts` | implemented baseline |
| Console-kit components | `src/console-kit/**` | partial baseline with visual debt |
| Console-kit consumers | Dashboard, Tasks List, API-key Viewer, API Keys | mainline consumers |

## Raw Color And Visual Debt

| Owner | Current Sites | Classification | Target |
| --- | --- | --- | --- |
| Global shell tokens | `src/app/styles.css` raw hex/rgba, page-card, page title, startup error | shell token work | replace with `:root` tokens |
| Operator header | `src/layouts/AppHeader.vue` raw text/status colors and pill colors | shell token work | converge into navbar/dropdown tokens |
| Operator sidebar | `src/layouts/AppSidebar.vue` raw gradients, brand mark, menu colors | shell token work | use `--sidebar-*` and status tokens |
| App shell layout | `src/layouts/AppShell.vue` fixed content padding and shell mode padding | shell token work | use shell spacing tokens |
| Login page | `src/pages/app/LoginPage.vue` raw title/eyebrow colors | login polish | align with app tokens |
| ConsolePage | `src/console-kit/layout/ConsolePage.vue` hero gradients, large type, negative letter spacing | console-kit convergence | dense admin page container |
| MetricCard | `src/console-kit/data/MetricCard.vue` tone gradients and raw colors | console-kit convergence | quiet metric card using app tokens |
| StatusBadge | `src/console-kit/data/StatusBadge.vue` local status palette | console-kit convergence | token-aligned status palette |
| Credential widgets | `CredentialInputCard.vue`, `SecretRevealDialog.vue` raw muted/secret colors | console-kit convergence | token-aligned security widgets |

Current residue check after FRONT-4: raw hex/rgba matches in the listed shell
and console-kit files are limited to `:root` token declarations and Vue
`#default` slot syntax, not active component-local color definitions.

## Console-Kit Current Consumers

| Component | Consumers | Classification |
| --- | --- | --- |
| `ConsolePage` | Dashboard, Tasks List, API-key Viewer, API Keys | mainline, visual debt |
| `MetricGrid` | Dashboard, API-key Viewer, API Keys | mainline |
| `MetricCard` | Dashboard, API-key Viewer, API Keys | mainline, visual debt |
| `FilterToolbar` | Tasks List | mainline, limited reuse |
| `StatusBadge` | Dashboard, Tasks List, API-key Viewer, API Keys | mainline, visual debt |
| `CredentialInputCard` | API-key Viewer | mainline, limited reuse |
| `SecretRevealDialog` | API Keys | mainline, security-critical |

Rollback candidates: none currently. Avoid adding `ResourceTable` until repeated
table behavior is proven across pages.

## Shell Modes

| Shell | Current Behavior | Boundary |
| --- | --- | --- |
| `operator` | renders `AppSidebar` and `AppHeader`; menu from route metadata | full operator shell |
| `api-key-viewer` | no operator sidebar/header; centered shell content | lightweight viewer shell |
| `public` | no operator sidebar/header; centered shell content | lightweight public shell |

API-key Viewer and public shell modes must not inherit operator navbar,
sidebar, operator mode switch, or operator auth diagnostics.

## Next Slice

Proceed to FRONT-1 Shell Token Foundation. Do not start page redesign before
shell/global tokens are in place.
