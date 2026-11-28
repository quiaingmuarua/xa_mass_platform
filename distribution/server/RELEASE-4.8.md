# XA Mass 4.8 release and upgrade contract

Status: current 4.8 release input. 4.8.0 is published and its retained-scope GCP
upgrade completed on 2026-11-27; temporary upgrade-material cleanup was confirmed
at 08:26:12 UTC. Published artifacts remain immutable. Actual host installation
state belongs to the host records reached through the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.8.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.8.0 Server-minted RPC channel provisioning, admin close and purge-only retention

4.8.0 delivers:

- **Server-minted admin-only RPC channel provisioning**: retired
  `POST /api/v1/tasks/{taskId}/rpc:prepare` (no compatibility alias) and replaced
  it with admin-only `POST /api/v1/tasks/rpc` (`TaskRpcPrepareRequest` with
  optional `ownerUserId`). Redis-backed `TaskRpcChannelRegistry`
  (`xa_mass:<scope>:server:rpc-channel:<sha256>`) mints `rpc-<32-hex>` per active
  `(projectId, ownerUserId, workerGroupId)` slot with idempotent reuse on
  identical config, `400/12006` on config mismatch, `403/12013` for non-admin
  callers, and JVM-only `prepareServiceChannel` for internal Scenarios (`#155`).
- **Admin close and purge-only retention for raw RPC channels**: narrowed Kernel
  `RedisTaskRuntime.RETENTION_SCRIPT` so `freeze`/`release` stay
  `CLOSE_WHEN_IDLE`-only while `begin`/`purge`/`finish` also reclaim `TERMINAL`
  `PARK_WHEN_IDLE` Tasks under the negative terminal Score fence. Extended
  `TaskLifecycleService.close(...)` for admin-only closure of raw `PARK_WHEN_IDLE`
  RPC channels (`403/12013` for operator, `400/12008` for internal Scenario
  channels), immediately releasing the `TaskRpcChannelRegistry` slot and evicting
  cached call descriptors, with purge-only Redis cleanup after
  `xa.mass.task-retention.grace` (or immediately via admin
  `POST /api/v1/tasks/{taskId}/retention:run`) and zero rows written to
  `task-history.sqlite` (`#156`).
- **RPC Workbench & SPA convergence**: updated Console RPC workbench for
  server-minted channel creation, admin-only `"关闭通道"` confirmation, automatic
  post-create redirect to the RPC list, same-session re-creation after close, and
  task-list refresh on `view=create` (`#155`, `#156`, `#157`).
- **Ingress, OpenAPI, persistence & Host example upkeep**: updated
  `staging-ingress/renderer.py` to expose `POST /api/v1/tasks/rpc` and retire
  `/rpc:prepare`, updated the verified OpenAPI snapshot and deployed
  `worker-call-performance` consumer contract (`rpcTaskIds`), extracted
  `UserDirectoryStore` from `UserDirectory` without schema change, and set a
  bounded 120s `reconnectPolicy` in `persistent-20-host.json` (`#153`, `#154`,
  `#155`, `#156`).

4.8.0 was published from merged commit
`8ee3c5f4632d8169937a3c2eaa0589211eca2880` through
[complete manual Proof 45962006497](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45962006497),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 45964142778](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45964142778)
promoted those exact Proof ZIPs to
[Release 4.8.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.8.0)
(Release ID `460319306`).

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.8.0.zip` | `ad14ba14d90c83970bcb315eb7fcde25dfeea9d125910e0fcfeaee888b609966` |
| `xa-mass-worker-sdk-4.8.0.zip` | `bf7feefe12b4f75e3b8ef4ac63c55e8bb59b1ab7e848b687638f99c9401c4775` |
| `xa-mass-worker-simulator-4.8.0.zip` | `46732a4b9df452defeca8fa7ecb56a58dcf1b5b2d0fc3235ec4d3024e0923221` |

### Retained-scope upgrade constraints from 4.7.0

Upgrading an existing 4.7.0 Release Preview Runtime to 4.8.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), SQLite control-plane user
directory (`/var/lib/xa-mass/control-plane.sqlite`, schema 1, WAL), credit
accounts, Task/request identities, business configuration, gateway credentials
and standing Worker Host (`4.4.2`). The previously retained Performance kit
`4.4.4` requires the separate consumer update below. It requires no
Redis scope reset, SQLite schema migration or Worker Host/SDK replacement.

Apply these 4.8.0-specific host updates during the stopped-Runtime maintenance
window:

1. **Re-render Nginx staging ingress from the 4.8.0 Runtime ZIP**: render
   `/etc/nginx/conf.d/...` using `examples/staging-ingress/renderer.py` from the
   immutable 4.8.0 Runtime archive while preserving existing TLS certificates,
   gateway credentials and loopback upstreams so public HTTPS admits
   `POST /api/v1/tasks/rpc` and stops exposing the retired
   `POST /api/v1/tasks/{taskId}/rpc:prepare` route. Verify `nginx -t` and reload
   Nginx.
2. **Pre-existing raw RPC channels on retained scope**: existing running raw RPC
   Tasks created before 4.8.0 remain observable, callable via `items:call` and
   closable by admin via `POST /api/v1/tasks/{taskId}/close`. New raw RPC
   channels are provisioned exclusively by admin via `POST /api/v1/tasks/rpc`
   (minted `rpc-<32-hex>`).
3. **Post-restart standing Worker check**: verify the standing 20 Workers
   (`persistent-workers`, with 120s bounded `reconnectPolicy`) automatically
   reconverge to `20/20 activeHot`; if any remain `STOPPED`, resume explicitly
   via the existing Ali Lab start procedure and record manual resume separately.
4. **Runtime, Ingress, SQLite & Console acceptance**: verify
   `/actuator/health/readiness`, loopback `/actuator/prometheus` (`xa_mass_*`
   series), `/var/lib/xa-mass/control-plane.sqlite` and
   `/var/lib/xa-mass/task-history-4.4.sqlite` (`PRAGMA quick_check=ok`), public
   `POST /api/v1/tasks/{id}/rpc:prepare -> 404`, admin `POST /api/v1/tasks/rpc`
   -> `items:call` (3-Item UTF-8 MD5) -> `POST /api/v1/tasks/{id}/close` ->
   immediate slot re-mint -> `retention:run` purge (0 SQLite history rows added),
   a fresh 3-Item finite MD5 Task with JSONL export, interactive authenticated
   Console verification on `https://staging.xamass.net`, and 0
   `xa-mass.service` warnings/errors.

### Accepted deployment and closeout (2026-11-27)

The 2026-11-27 GCP Release Preview upgrade (`upgrade-4.8.0-20261127T081658Z`)
installed immutable Runtime 4.8.0 (`PID 1112415`) at the commit and SHA256
above, retaining `profile_long_preview_4_4`,
`/var/lib/xa-mass/task-history-4.4.sqlite` (schema 1, `PRAGMA quick_check=ok`),
`/var/lib/xa-mass/control-plane.sqlite` (schema 1, WAL, `PRAGMA quick_check=ok`,
`cp_roles=2`, `cp_users=7`, `cp_user_projects=5`), `configurationId`
`sha256:3d50c77086cd4c78d34d3ee43d0a1b8ed20c453b37943b28dd6ed67fe8240b5a`,
standing Host `4.4.2`, and Performance kit `4.4.4`. Nginx staging ingress was
re-rendered from the 4.8.0 Runtime archive
(`/etc/nginx/nginx.conf` SHA256
`1759bdddfd04fa470122311efa5ed7d3b8202b6b7d6fa690c67d6d53e365780f`), admitting
`POST /api/v1/tasks/rpc` and retiring `POST /api/v1/tasks/{taskId}/rpc:prepare`.

- Health, ingress and observability checks passed: loopback readiness `UP`
  (14s), loopback `/actuator/prometheus` exported 259 `xa_mass_*` lines, public
  `/actuator/prometheus` and `/task-workbench` returned `404`, retired
  `POST /api/v1/tasks/{id}/rpc:prepare` returned `404`, unauthenticated
  `/api/v1/worker-groups` returned `401`, `/docs/` served commit `8ee3c5f46`,
  `/reference/openapi.json` exposed `/api/v1/tasks/rpc` without `/rpc:prepare`,
  and `xa-mass.service` logged `0` warnings/errors.
- Standing `persistent-workers` automatically reconnected to `20/20 activeHot`
  (`0 recovery`) without manual Host intervention.
- Public API RPC lifecycle and MD5 checks passed: admin
  `POST /api/v1/tasks/rpc` minted `rpc-7a1af3eacd924acda2963869cc0560b7`
  (idempotent reuse verified), `POST /api/v1/tasks/{id}/close` -> `200 applied`,
  post-close `items:call` -> `400`, immediate re-mint produced distinct
  `rpc-2073a50796c24dfdbc8f5558ba3a467b`; a 3-Item UTF-8 MD5 RPC round-trip on
  `rpc-e890b37cc52248c4a3d2dd0d21666440` passed `3/3`; `retention:run` purged
  all closed acceptance RPC Tasks (`PURGED`) with `0` rows added to
  `task-history-4.4.sqlite` (`task_history=36`, `task_history_rows=1064`).
- Public finite 3-Item UTF-8 MD5 Task (`task-5125dcf87d3540658468ecc4e6043851`)
  reached `terminal` and exported JSONL (`3/3` independent UTF-8 MD5 verified,
  SHA256 `273687cf04adadbc010278c5d99dd05ea550e179f1afebf507981c1b1461bf7d`).
- Live authenticated Console UI acceptance on `https://staging.xamass.net`
  passed end-to-end: `accept-480-ui-1` (`rpc-54345bb96e2a46aab1ae8554b099080e`)
  minted via `POST /api/v1/tasks/rpc` with automatic post-create redirect to the
  RPC list, closed via `"关闭通道"`, immediately re-created in the same SPA
  session without page reload as `accept-480-ui-2`
  (`rpc-fbd835533da442cfac47847634c10d7b`), closed, and purged via
  `retention:run`.
- Old 4.7.0 binary, temporary stage directory and temporary restore point were
  confirmed removed at `2026-11-27 08:26:12 UTC`; GCP `release.json`,
  `deployment.json`, `OPERATIONS.txt`, `operations.log` and GitHub
  `release-preview-load` Server pins (`INSTALLED_RELEASE_TAG=v4.8.0`,
  `INSTALLED_RELEASE_COMMIT`, `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment`)
  were updated and verified.

## 4.8.1 RPC recovery, Console purge UX and Nginx FD limit

4.8.1 is pending publication and retained-scope GCP upgrade. It delivers:

- **Server RPC recovery & WorkerGroup retry idempotency (`#160`)**:
  - `TaskRpcPreparationService` validates the complete stored descriptor shape
    (including Server attribution) before reserving a durable
    `TaskRpcChannelRegistry` slot, and allows a retained raw channel claim to
    re-enter Kernel `PRE_REVIEW` creation recovery cleanly after partial commit.
  - `WorkerGroupManagementService` and `WorkerGroupRegistrationController` make
    Group deletion and offline Worker removal idempotent across partial-failure
    retries when catalog/binding state was already removed.
  - Hardened `run_external_performance.py`, `external-performance-consumer.yml`
    and OpenAPI/docs for the 4.8 `rpcTaskIds` consumer upgrade contract.
- **Console RPC purge UX & zero-404 Scenario discovery (`#161`)**:
  - Extended `taskRetentionSchema` with `PURGED`, surfaced closed RPC retention
    timeline (`关闭时间`, `保留阶段`, `Redis 预计清理时间`) in
    `TaskDescriptorPanel.vue` / `TaskRetentionNotice.vue`, and added admin-only
    `"立即清理 Redis"` (`POST /api/v1/tasks/{taskId}/retention:run`) in
    `RpcTaskPage.vue`.
  - Gated Console Scenario catalog probes in `ConsoleLayout.vue` against the
    declared `GET /api/v1/projects` directory, eliminating background
    `GET /api/v1/sms/catalog -> 404` requests when SMS is not assembled while
    preserving direct-route fallback.
- **`staging-ingress` `worker_rlimit_nofile` contract (`#161`)**:
  - Added optional `worker_rlimit_nofile` (`>= worker_connections`) to
    `staging_ingress/renderer.py` so high-concurrency ingress configs emit
    `worker_rlimit_nofile` in the main Nginx context and avoid `nginx -t`
    `RLIMIT_NOFILE` warnings.

### Retained-scope upgrade constraints from 4.8.0

Upgrading an existing 4.8.0 Release Preview Runtime to 4.8.1 reuses the existing
Redis scope (`profile_long_preview_4_4`), `/var/lib/xa-mass/task-history-4.4.sqlite`,
`/var/lib/xa-mass/control-plane.sqlite`, `runtime.yaml` (`configurationId`),
standing Worker Host (`4.4.2`), and existing pre-provisioned `performance-consumer`
RPC channels. Apply during the stopped-Runtime maintenance window:

1. **Set `worker_rlimit_nofile: 65536` and re-render Nginx**: add
   `"worker_rlimit_nofile": 65536` to `/etc/xa-mass/ingress/deployment.json`,
   render `/etc/nginx/nginx.conf` from the immutable 4.8.1 Runtime ZIP's
   `staging_ingress`, verify `nginx -t` completes with zero warnings, and reload
   Nginx.
2. **Runtime, SQLite, Workers & Console acceptance**: verify readiness `UP`,
   loopback `/actuator/prometheus`, both SQLite `PRAGMA quick_check=ok`,
   `persistent-workers` `20/20 activeHot`, 3/3 UTF-8 MD5 (RPC + finite export),
   0 `xa-mass.service` warnings, and live authenticated Console verification on
   `https://staging.xamass.net` (0 `/api/v1/sms/catalog` 404s; RPC close ->
   retention timeline -> `"立即清理 Redis"` -> drawer closed & purged with 0
   SQLite history rows added).

## Finite Task history and RPC retention

4.8 preserves immutable SQLite finite Task history (schema 1) and opt-in
terminal RPC Item retention without schema changes, and adds purge-only Redis
retention for admin-closed raw `PARK_WHEN_IDLE` RPC channels (zero SQLite
history writes). See
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
cross-version compatibility promise. In particular, Runtime 4.8.0 and later
removed `rpc:prepare`: Performance kit 4.4.4 cannot bootstrap against this Runtime.
Use an immutable kit from 4.8.0 or later and configure its two pre-provisioned
`rpcTaskIds`; existing valid Performance Tasks may be referenced without
recreation. This requires updating `PERFORMANCE_KIT_RELEASE_TAG/COMMIT` and
`PERFORMANCE_DEPLOYMENT_JSON` together, not an administrator key on the consumer,
a persistent Host upgrade or a Redis reset.

Compatibility correction (2026-11-28): the dated upgrade record above retains
the actual reported kit 4.4.4; it is not evidence that this consumer worked with
Runtime 4.8.0. This repository correction does not claim the Environment pins
have been updated or a public campaign has been executed.

Existing APKs are not replaced
automatically, and prior-version witnesses retain their versions.

The [Worker Call Performance Owner](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns deployed workload and measurement limits. Current kit usage and
configuration belong to the
[kit guide](../worker-simulator/src/main/dist/README.md) and
[kit examples](../worker-simulator/src/main/dist/examples/README.md).

## Earlier release records

Completed 4.7, 4.6, 4.5, 4.4 and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.7.x | [4.7 release and upgrade contract](RELEASE-4.7.md) |
| 4.6.x | [4.6 release and upgrade contract](RELEASE-4.6.md) |
| 4.5.x | [4.5 release and upgrade contract](RELEASE-4.5.md) |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
