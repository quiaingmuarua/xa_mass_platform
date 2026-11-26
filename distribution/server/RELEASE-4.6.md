# XA Mass 4.6 release and upgrade contract

Status: current 4.6 release input. 4.6.0 is published and its retained-scope GCP
upgrade completed on 2026-11-26; temporary upgrade-material cleanup was confirmed
at 09:08:39 UTC. Published artifacts remain immutable. Actual host installation
state belongs to the host records reached through the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.6.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.6.0 WorkerGroup lifecycle management, offline Worker cleanup and Storage workspace

4.6.0 delivers:

- **WorkerGroup & offline Worker resource management**: paginated admin
  directory (`GET /api/v1/worker-groups`), metadata updates
  (`PUT /api/v1/worker-groups/{workerGroupId}`), zero-active-worker safe Group
  removal (`DELETE /api/v1/worker-groups/{workerGroupId}`), and single offline
  Worker Runtime cleanup
  (`DELETE /api/v1/worker-groups/{workerGroupId}/workers/{workerId}`), backed by
  O(log N) Kernel Score state summaries, atomic inactive-only Score removal,
  JSON-free conditional Binding cleanup, non-terminal Task keyset scanning, and
  Matching state/pool purges (`#143`).
- **Read-only Project resource topology & 3-tab `/runtime/workers` Console**:
  extended `GET /api/v1/runtime-view/worker-groups/bindings` with `projects`
  topology (`exclusiveInputOwner`) and upgraded `/runtime/workers` to **Worker
  观察**, **Group 管理**, and **资源绑定**, plus Staging Ingress allowlist rules
  for the new admin WorkerGroup routes (`#143`).
- **Independent Storage Task history workspace**: exposed `/storage/tasks` as a
  dedicated finite Task history workspace (`#142`).
- **Runtime Tasks & workbench polish**: active RPC channel board,
  retention-window statistics, App Checks approval guard, and global tab polish
  (`#140`, `#141`).

4.6.0 was published from merged commit
`d4ff703aed33b4906da36002ac3d79b1bb6c4e0e` through
[complete manual Proof 45784713284](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45784713284),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 45787735767](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45787735767)
promoted those exact Proof ZIPs to
[Release 4.6.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.6.0)
(Release ID `459161268`).

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.6.0.zip` | `8b1a4ec67ea5e31f525a88a687f47ac8b5b6decbc4c0ba803243eed6778d8c6d` |
| `xa-mass-worker-sdk-4.6.0.zip` | `541e979a0c034c7c2c2bc5852c5a0946f398bfa0634bf472cc005e99c20b5a38` |
| `xa-mass-worker-simulator-4.6.0.zip` | `4c8f0d41782c8c31098c5426fa34073b17e86b17314949f4d1499cf55d70aeb2` |

### Retained-scope upgrade constraints from 4.5.0

Upgrading an existing 4.5.0 Release Preview Runtime to 4.6.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), external `users.json`,
credit accounts, Task/request identities, business configuration, gateway
credentials and standing Worker Host (`4.4.2`) / Performance kit (`4.4.4`). It
requires no Redis scope reset, SQLite schema migration, `users.json` change or
Worker Host/SDK replacement.

Apply these 4.6.0-specific host updates during the stopped-Runtime maintenance
window:

1. **Staging Ingress alignment**: regenerate and validate Nginx configuration
   from the 4.6.0 Runtime ZIP's `staging-ingress` renderer to allowlist the new
   admin WorkerGroup management routes (`GET /api/v1/worker-groups`,
   `PUT /api/v1/worker-groups/{workerGroupId}`,
   `DELETE /api/v1/worker-groups/{workerGroupId}`,
   `DELETE /api/v1/worker-groups/{workerGroupId}/workers/{workerId}`). Keep
   `/actuator/prometheus` bound to loopback on the Runtime host only; do not
   expose it through public Nginx ingress.
2. **Post-restart standing Worker check**: verify the standing 20 Workers after
   Runtime restart; if the Host remains live with desired `RUNNING` and runtime
   `STOPPED`, resume the original 20 identities explicitly via the existing Ali
   Lab start procedure and record manual resume separately from automatic
   reconnection.
3. **Runtime, management API & Console acceptance**: verify
   `/actuator/health/readiness`, loopback `/actuator/prometheus` (`xa_mass_*`
   series), authenticated `GET /api/v1/worker-groups`,
   `GET /api/v1/runtime-view/worker-groups/bindings` (including `projects`), a
   temporary dynamic Group register/update/delete round-trip through public
   ingress, authenticated `/runtime/workers` (`Worker 观察` / `Group 管理` /
   `资源绑定`) and `/storage/tasks`, external finite + RPC MD5 checks, and
   SQLite `PRAGMA quick_check`.

### Accepted deployment and closeout (2026-11-26)

The 2026-11-26 GCP Release Preview upgrade (`upgrade-4.6.0-20261126T090259Z`)
installed immutable Runtime 4.6.0 (`PID 1048450`) at the commit and SHA256
above, retaining `profile_long_preview_4_4`,
`/var/lib/xa-mass/task-history-4.4.sqlite` (schema 1, `PRAGMA quick_check=ok`),
external `users.json`, standing Host `4.4.2`, and Performance kit `4.4.4`.
Recomputed `configurationId` after the `nginx.conf` and `start-runtime` updates
is `sha256:821d6284a7768280b2b34388080520affa3b0e3c36a775c12098eff57ce14563`
(previous:
`sha256:e4b29267527fdfee5d75b3aec2a24b10421490acc29d7c1c617702efe68c09c6`).

- Nginx staging ingress was re-rendered from the 4.6.0 ZIP (adding allowlist
  rules for `GET/PUT/DELETE /api/v1/worker-groups*`,
  `DELETE /api/v1/worker-groups/{id}/workers/{workerId}` and `/storage/tasks`),
  validated with `nginx -t -g 'user www-data;'` and reloaded without dropping
  the master process (`PID 659618`).
- Health, ingress and observability checks passed: loopback readiness `UP`,
  loopback `/actuator/prometheus` exported 175 `xa_mass_*` lines, public
  `/actuator/prometheus` returned `404`, public `/runtime/workers`,
  `/storage/tasks` and `/runtime/tasks` returned `200`, public `/task-workbench`
  returned `404`, unauthenticated `/api/v1/worker-groups` and Worker prepare
  returned `401`, `/docs/` served commit `d4ff703ae`, and `xa-mass.service`
  logged 0 warnings/errors.
- Public authenticated WorkerGroup management checks passed:
  `GET /api/v1/worker-groups` returned 10 registered groups (`unreadable=0`),
  `GET /api/v1/runtime-view/worker-groups/bindings` returned the 4 declared
  Projects (`app-checks`, `default`, `messages`, `performance-consumer`), and a
  dynamic `accept-460-smoke` Group completed register -> update -> startup-seeded
  `PUT` 400 rejection -> delete cleanly.
- Standing `persistent-workers` observed `10/20` connected (`activeHot=10`,
  `recovery=10`, Ali Host untouched) and served both a fresh 3-Item RPC Task
  (`accept-460-rpc-bb1feadf`, 3/3 terminal succeeded) and a fresh 3-Item finite
  Task (`task-82d62860d9794064b6dea521802c206c`, JSONL export SHA256
  `5a14bd2303cb66e735846f732d3ca132fe7df59deba1a5bf2ba4e21da4981ac2`) with
  independent UTF-8 MD5 verification passing. Interactive browser check remains
  pending operator review.
- Old 4.5.0 binary, temporary stage directory and temporary restore point were
  confirmed removed at `2026-11-26 09:08:39 UTC`; GCP `release.json`,
  `deployment.json`, `OPERATIONS.txt`, `operations.log` and GitHub
  `release-preview-load` Server pins (`INSTALLED_RELEASE_TAG=v4.6.0`,
  `INSTALLED_RELEASE_COMMIT`, `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment`)
  were updated and read back.

## Finite Task history and RPC retention

4.6 preserves immutable SQLite finite Task history (schema 1) and opt-in
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

Completed 4.5, 4.4 and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.5.x | [4.5 release and upgrade contract](RELEASE-4.5.md) |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
