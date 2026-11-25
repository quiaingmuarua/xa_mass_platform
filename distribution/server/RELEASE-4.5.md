# XA Mass 4.5 release and upgrade contract

Status: current 4.5 release input. 4.5.0 is published and its retained-scope GCP
upgrade completed on 2026-11-25; temporary upgrade-material cleanup was confirmed
at 05:41:22 UTC. Published artifacts remain immutable. Actual host installation
state belongs to the host records reached through the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.5.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.5.0 Runtime convergence, observability base and unified Runtime Tasks

4.5.0 delivers:

- **Server & Redis Lua convergence**: converged core Task orchestration, delivery
  and RuntimeView services (`#128`, `#129`), batched encoding-blind Kernel Redis
  Lua primitives (`#131`), and read-only Credit queries moved out of Lua (`#132`).
- **Observability base**: zero-pollution pure-JDK `MetricsSink` in
  `kernel_pacer_jvm`, isolated Micrometer binding in `server_jvm`, and Prometheus
  scrape export at `/actuator/prometheus` in `server_boot_jvm` (`#135`).
- **Unified `/runtime/tasks` Console**: integrated the upgraded Task Workbench UI
  (running finite-task progress board, segmented flat tables, tabbed drawers and
  RPC debug correlation) with cross-Project global scheduling (`tab=global`) at
  `/runtime/tasks`, removing `/task-workbench` and the redundant `workbench.use`
  permission (`#137`).
- **Proof CI & Release reliability**: rerun artifact attempt propagation for
  `prepare_release.py` and Proof CI, split Robolectric caches, JFR observation
  ordering fix (`#136`), and CI-default Google Maven Central mirror fallback for
  Gradle builds (`#138`).

4.5.0 was published from merged commit
`a0db0cf30e466d305d37e9a880525d80b2316a44` through
[complete manual Proof 45552422098](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45552422098),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 45554251315](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45554251315)
promoted those exact Proof ZIPs to
[Release 4.5.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.5.0)
(Release ID `457693291`).

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.5.0.zip` | `5bcc9a10d10a5f2615269115c736a95a7f8fe57a028d37c0ce47073a7fe327f5` |
| `xa-mass-worker-sdk-4.5.0.zip` | `67ec3c040ce2b656e86b490b3a20b8c4e4950931a772407dac56e3bb78e9b8a6` |
| `xa-mass-worker-simulator-4.5.0.zip` | `6c08d1e0033bfa8bdd938ec94e2ae0199a2d5b9af09b9e791afd05f8c932c92a` |

### Retained-scope upgrade constraints from 4.4.5

Upgrading an existing 4.4.5 Release Preview Runtime to 4.5.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), credit accounts,
Task/request identities, business configuration, gateway credentials and standing
Worker Host (`4.4.2`) / Performance kit (`4.4.4`). It requires no Redis scope
reset, SQLite schema migration or Worker Host/SDK replacement.

Apply these 4.5.0-specific host updates during the stopped-Runtime maintenance
window:

1. **External `users.json` permission cleanup**: preserving file owner, group
   and mode, remove the retired `"workbench.use"` entry from the `admin` role's
   `permissions` array so the 4.5.0 Console's strict role decoder accepts
   `/api/v1/me` and `/api/v1/roles`. Do not alter user IDs, access-key hashes or
   Project grants.
2. **Staging Ingress alignment**: regenerate and validate Nginx configuration
   from the 4.5.0 Runtime ZIP's `staging-ingress` renderer to remove
   `/task-workbench` from admin page allowlists. Keep `/actuator/prometheus`
   bound to loopback on the Runtime host only; do not expose it through public
   Nginx ingress.
3. **Post-restart standing Worker check**: verify the standing 20 Workers after
   Runtime restart; if the Host remains live with desired `RUNNING` and runtime
   `STOPPED`, resume the original 20 identities explicitly via the existing Ali
   Lab start procedure and record manual resume separately from automatic
   reconnection.
4. **Console & observability acceptance**: verify `/actuator/health/readiness`,
   loopback `/actuator/prometheus` (`xa_mass_*` series), authenticated
   `/runtime/tasks` (RPC, finite and global views), `/task-workbench` returning
   `404`, external finite + RPC MD5 checks, and SQLite `PRAGMA quick_check`.

### Accepted deployment and closeout (2026-11-25)

The 2026-11-25 GCP Release Preview upgrade (`upgrade-4.5.0-20261125T053403Z`)
installed immutable Runtime 4.5.0 (`PID 973667`) at the commit and SHA256 above,
retaining `profile_long_preview_4_4`, `/var/lib/xa-mass/task-history-4.4.sqlite`
(schema 1, `PRAGMA quick_check=ok`), `configurationId`
`sha256:c9bdee9c5282ea089ea667c086fb933ea4f7ce7b38518e9eac248da266cc79fd`,
standing Host `4.4.2`, and Performance kit `4.4.4`.

- External `/var/lib/xa-mass/users.json` removed retired `"workbench.use"` from
  `admin.permissions` while preserving `0600 xa-mass:xa-mass`; `/api/v1/me`
  confirmed `userId=1000` with `runtime.view=true` and `workbench.use=false`.
- Nginx staging ingress was re-rendered from the 4.5.0 ZIP (single-line removal
  of `task-workbench` from admin page allowlist) and validated with
  `nginx -t -g 'user www-data;'`.
- Health, ingress and observability checks passed: loopback readiness `UP`,
  loopback `/actuator/prometheus` exported 94 `xa_mass_*` lines (with live
  Pacer dispatch counters incrementing during acceptance), public
  `/actuator/prometheus` returned `404`, public `/runtime/tasks` returned `200`,
  public `/task-workbench` returned `404`, unauthenticated `/api/v1/me` and
  Worker prepare returned `401`, and `/docs/` served commit `a0db0cf30`.
- All 20 standing Workers (`persistent-workers`) automatically reconnected
  without Host restart (`connected=20`).
- External 3-Item finite Task (`task-f12292b2537d4c809ffd9e0132f1e679`, JSONL
  export SHA256
  `6e3c9fbf459d4362962cea9a8bb8a3d1fa32f3bc742f3f9cac5cff330732de42`) and fresh
  3-Item RPC Task (`accept-450-rpc-a0f4e13e`, 3/3 terminal succeeded) passed
  independent UTF-8 MD5 verification; temporary `accept4` key was cleared.
- Interactive browser spot-check of `/runtime/tasks` with the private admin key
  remains explicitly recorded as pending operator browser verification.
- Old 4.4.5 binary, temporary stage directory and temporary restore point were
  confirmed removed at `2026-11-25 05:41:22 UTC`; GCP `release.json`,
  `deployment.json`, `operations.log` and GitHub `release-preview-load` Server
  pins (`INSTALLED_RELEASE_TAG=v4.5.0`, `INSTALLED_RELEASE_COMMIT`,
  `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment`) were updated and read back.

## Finite Task history and RPC retention

4.5 preserves immutable SQLite finite Task history (schema 1) and opt-in
terminal RPC Item retention without schema changes. See
[Server retention and history Owner](../../server_jvm/README.md#finite-task-retention),
[Server RPC retention Owner](../../server_jvm/README.md#rpc-terminal-item-retention)
and [Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration).

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas are 6 and 1; Simulator kit
schema 3 includes
`performanceEntry=acceptance/performance/run_worker_call_performance.py` and
compiled libraries under `acceptance/performance/harness/`.

Each immutable kit and target Runtime is pinned independently by exact version,
commit and checksum for deployed Performance. The two commits need not match;
neither coordinate uses `latest` or a fallback. This is not a general
cross-version compatibility promise. Existing APKs are not replaced
automatically, and prior-version witnesses retain their versions.

The [Worker Call Performance Owner](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns deployed workload and measurement limits. Current kit usage and
configuration belong to the
[kit guide](../worker-simulator/src/main/dist/README.md) and
[kit examples](../worker-simulator/src/main/dist/examples/README.md).

## Earlier release records

Completed 4.4 release records and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
