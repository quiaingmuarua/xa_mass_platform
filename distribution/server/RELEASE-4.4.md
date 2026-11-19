# XA Mass 4.4 release and upgrade contract

Status: version-scoped 4.4 contract. 4.4.5 is published and its retained-scope
GCP upgrade was accepted on 2026-11-19; temporary upgrade-material cleanup was
confirmed at 09:58:31 UTC. 4.4.4 is published and its normal 10k
observation completed with all nine cases passed and automatic cleanup confirmed.
The earlier 4.4.3 observation retained nine invalid cases and confirmed cleanup;
its generator investigation was closed by the bounded 4.4.4 observation below.
4.4.2 is published and the Ali
consumers were updated; GCP Runtime remained 4.4.0 at that acceptance. The 4.4.2 campaign and automatic
cleanup completed, and the bounded 75-minute idle and single controlled
fault/reconnect acceptance passed on 2026-11-18.
Published artifacts remain immutable.
Actual installation and acceptance status belongs to host records and
[dated evidence](../../doc/guide/capabilities.md#external-public-closeout), not a live claim here.

## 4.4.5 Terminal RPC data retention patch

This patch delivers the shared Performance sampler exit fix, the consolidated
CI gates and the staged terminal RPC Item retention implementation. The ordinary
complete manual Proof now includes SMS Reception Preview. Build and prove the
exact merged release commit with candidate version 4.4.5, then promote those
same three Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Their
manifest schemas remain 6/1/3. Do not relabel an older candidate or rebuild on
GCP.

4.4.5 was published from merged commit
`520b7ce314de24ad42ea079f97491919a24852b4` through
[complete manual Proof 44493074012](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44493074012),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 44495903690](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44495903690)
promoted those exact Proof ZIPs to
[Release 4.4.5](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.5)
(Release ID `451116838`). All six GitHub digests and SHA256 sidecars were
verified; no new Runtime build was made on GCP.

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.4.5.zip` | `72f08d6e8b7db998854145b30f95c454d813f19a05b918c14d5181e22081ed7f` |
| `xa-mass-worker-sdk-4.4.5.zip` | `de868f92b230c99f92a20a9c573bd9a7805fa88c01e1acb0d14118f5934d56cf` |
| `xa-mass-worker-simulator-4.4.5.zip` | `452be280a5b66819bd9298c200adf66cf53eb47fc61a3b23de5364a536c78665` |

### Existing GCP 4.4.0 upgrade

Upgrade only the Runtime to the published immutable 4.4.5 artifact. This patch
reuses `profile_long_preview_4_4` and
`/var/lib/xa-mass/task-history-4.4.sqlite`; the initial 4.4.0 fresh-scope cutover
instructions later in this document do not apply to this patch. Retain the
existing external configuration, user directory and grants, credit accounts,
Task/request identities, business configuration and gateway credentials. Keep
the existing Nginx ingress configuration and budgets. No schema migration,
scope reset, manual Redis sweep, old-scope maintenance or Worker Host/SDK upgrade
is part of this change. The independent standing Host remains on its current
kit and keeps its existing inventory and 20 Worker identities.

Use the existing single-operator operations record. Confirm that external
Performance is not running and capture the pre-change artifact/configuration
coordinates and aggregate Redis measurements below. Prepare one temporary
restore point: saved launch/configuration/user/ingress inputs, a confirmed
successful Redis snapshot with its restoration procedure, and the SQLite
database plus any WAL/SHM copied only after the Runtime is confirmed stopped.
Preserve the old immutable Runtime installation until acceptance; never run
two Pacers in this scope. Redis persistence configuration, including any AOF,
must be accounted for when restoring the snapshot; an unconfirmed save or an
unmatched database copy is not a restore point.

The published defaults keep RPC reclamation off. For this explicitly selected
Preview acceptance, enable it in the existing external configuration before
starting the new Runtime:

```yaml
xa:
  mass:
    task-retention:
      enabled: true
      rpc-item-retention-enabled: true
      rpc-input-retention: 1h
      rpc-terminal-retention: 24h
      interval: 1h
```

Keep the existing finite-task grace, retention, history retention and database
path. Maintenance runs on startup and at the existing fixed delay; observe its
first actual pass rather than waiting for an additional hour or manually
deleting Redis data. The RPC slice has its existing 120-second/2,000,000-row
budget and carries process-local progress across passes.

Only attributed raw RPC Tasks observed parked are candidates. The age is the
existing terminal Score's band-local time, not the last HTTP response or the
time a report reached this Server. At 1h, matching inputs can be removed while
Score and Result remain; at 24h, matching input, Result and Score are removed
together. This may remove unconsumed Results. Old calls outside the retention
window must not be automatically replayed, and new business calls use new
message IDs. ACTIVE Items, finite retention, SMS, other scopes, Worker records
and credit ledgers are outside this cleanup. Disabling the flag stops future
deletions and does not restore removed data; the writer guards remain active.

### Bounded onsite acceptance and closeout

Confirm the installed Runtime version/full commit/checksum, readiness, retained
configuration and database path. Observe the independent persistent Host's
actual runtime state after the Server becomes ready. A live Host process does
not prove its Workers have resumed. If retained coordinates have desired
RUNNING but runtime STOPPED, use the existing trusted local Lab
`POST /lab/v1/workers/{workerGroupId}/{labWorkerKey}:start` once per stopped
coordinate and independently confirm the same 20 identities at GCP. Do not
regenerate inventory, restart/upgrade the Host or extend SDK retry budgets as
part of this maintenance-resume step. It is an explicit operator action, not
a health daemon. Run one ordinary external finite baseline of three
Items using the existing published consumer entry and compare all results.
This is a small real-use baseline, not another 10k Performance campaign.

For the known qualifying RPC Tasks, retain private before/after TYPE, HLEN,
ZCARD, TTL and sampled MEMORY USAGE for their input, Result and Item Score key
families, plus Redis used memory and maintenance aggregate logs. Reconcile
scanned/matched/deleted counts, skips, failures and budget/progress state;
distinguish the 1h input stage from the 24h terminal stage. Verify retained
Task descriptors/directory entries and protected finite/Worker/credit state.
Use Owner/API state observations, never decode opaque Scores in operations or
publish complete Worker/Task/request identities or payloads. Key count, AOF
file length and RSS are not required to fall to zero. A partial budgeted pass
is recorded as partial and followed on the same maintenance lifecycle.

The 2026-11-19 upgrade installed Runtime 4.4.5 at the commit/checksum above,
reusing the same active scope, SQLite file, user/account state and ingress.
RPC retention is enabled with 1h/24h periods and the 1h interval. The deployed
configuration fingerprint is
`sha256:8bf0a7ba13db534c8a1ee5b846c0e011ee35b84644103094dcd447755ae7cb2d`.
Only the installed-Server pins and corresponding consumer `serverDeployment`
record changed: the Performance kit remains 4.4.4 and the standing Host
remains 4.4.2.

The first RPC maintenance cycle completed in **23,813ms**, scanning and
examining 892,741 eligible observations across two Task candidates, with
892,741 matches. It deleted **892,741 inputs, 250,774 Item Scores and 69
Results**, with zero skipped Tasks, no failure and no deadline/scan/Task-budget
flag. Private point checks covered 21 identities from the 24h purge stage
and 39 from the input-only stage. The following snapshots precede the fresh
RPC acceptance calls:

| Existing RPC families | Before | After first GC |
| --- | ---: | ---: |
| input fields | 892,741 | 0 |
| Result fields | 1,279 | 1,210 |
| Item Score members | 892,741 | 641,967 |

Redis `used_memory` fell from **545,717,368 to 148,777,632 bytes**
(520.44 to 141.89MiB), a reduction of 396,939,736 bytes (378.55MiB).
RSS was separately observed at 565,772,288 then 232,013,824 bytes;
`maxmemory` remained 2,147,483,648 bytes. Remaining Scores and Results retain
their own eligibility window. Protected current state was unchanged; the
independent old `profile_long_preview` scope was not cleaned, retained 347 keys,
and its checked protected-state fingerprints remained unchanged.

Both the external three-Item finite baseline using the 4.4.5 kit and three
fresh RPC calls passed independent UTF-8 MD5 checks; the fresh RPC Items also
reached terminal state. Runtime readiness, public API/Worker authentication
and packaged documentation commit were checked. SQLite remained schema 1 and
`quick_check` returned `ok`. Console source was unchanged, so no new browser
interaction acceptance was selected. No capacity or maximum-TPS run was made.

The standing 20 Workers **did not automatically resume** after this maintenance:
the Host remained live with desired RUNNING and runtime STOPPED. One explicit
existing Lab start per original coordinate restored all same 20 identities.
Host PID 53788, configuration and inventory remained unchanged; the process
was not restarted, and SDK retry budgets were not changed. This is a recorded
manual maintenance-resume result, not an automatic-recovery proof. Temporary
acceptance credentials were cleared and owned upgrade/acceptance units exited.
At 2026-11-19 09:58:31 UTC, the old 4.4.0 binary, single temporary restore point
and temporary Runtime download were confirmed absent. GCP's upgrade operation
and Ali's independent resume operation were closed, with canonical records
updated. GitHub Server pins were read back as 4.4.5 at the commit/configuration
above; the separate 4.4.4 Performance kit pins remained unchanged.

If a problem appears, first disable future reclamation on 4.4.5 and preserve
the evidence. A binary downgrade alone cannot recover deleted RPC identities.
Any restore must stop the new Runtime and use the matching restore point,
preserving post-snapshot request/credit receipts for explicit reconciliation;
do not merge histories or replay uncertain work. After acceptance is recorded,
remove this upgrade's old installation and temporary restore point, close the
operations record and retain the immutable Release and sanitized evidence.
Retained old scopes are not temporary rollback materials and remain untouched.

## 4.4.4 Administrator connection reuse patch

The staging renderer adds the positive integer `admin_keepalive_requests`,
default 1000, at administrator TLS-server scope. It retains the ordinary
Worker-server policy. Nginx 1.19.7+ applies this setting to HTTP/2; the proof
rejects older instruments that use the retired HTTP/2-specific directive.
This does not change request admission, authentication, stream capacity,
idle timeout, Worker behavior or the Performance workload and case oracle.

The Preview deployment explicitly uses 100000 requests per administrator
connection. That spans the existing 3000 warmup and up to 60000 formal calls
per case, reducing repeated GOAWAY/cold-cache TLS bursts. Longer reuse delays
reclamation of connection allocations; retain memory/FD observations. Runtime
remains 4.4.0 and the standing Host remains 4.4.2. Apply the published renderer,
validate and gracefully reload Nginx, then synchronize the Performance kit
and configuration pins without changing API 3000/s, burst 6000 or H2 8192 streams.

A valid private Nginx 1.24 comparison used the actual 4.4.3 client, four CPUs,
an ECDSA certificate and 175ms delay in each TLS direction. Changing only the
administrator reuse budget from 1000 to 100000 reduced measured dispatch p99
from 81.49ms to 0.085ms, FD peak from 1647 to 90 and new measurement connections
from 1519 to zero. Both 500/s formal cohorts completed 5000 calls without unknown
or duplicate results; the low-budget warmup retained 22 unknowns. This local
control does not claim to reproduce every cloud delay or establish GCP capacity.
Earlier controls with an unsupported Nginx version or invalid instruments are
retained separately and do not support this comparison.

The existing real Nginx/JDK proof retains the default GOAWAY boundary and adds
an SNI-selected administrator reuse case beyond 1000 requests, checked for
complete, exact-once mock receipt.

4.4.4 was published from merged commit
`01f795013ee9ded817304eba26e40805a21ddb89` after
[manual Proof 44364668882](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44364668882),
attempt 1, with all 17 jobs successful.
[Promotion 44367760555](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44367760555)
provided the identical verified bytes to immutable
[Release 4.4.4](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.4)
(ID `450377682`). All six GitHub digests and SHA256 sidecars matched; the bounded
current-credential-format scan found zero matches in 1,542 text entries,
including nested archives.

| ZIP component | SHA256 |
| --- | --- |
| Runtime | `121f161a27fd7f54298001f983c704f1944504702fb71f8eeae4578f00a9fc8b` |
| Worker SDK | `662dfbe36de4d42057f2099695ecc17d1fe972f34ce57805717366e1428efbd9` |
| Worker Simulator kit | `75698f07a411aa35390f6ee96f1d28393b402279f2f0c7e22aaf156d7659ddaa` |

GCP consumed only the published renderer and one new administrator directive,
followed by a graceful Nginx reload. The configuration fingerprint is
`sha256:bc1a46b635341c32a76d5089f0a1975265ab49900c2d0ca49ebe55061560c8e5`.
The original Runtime/Redis/Nginx master processes and standing 4.4.2 Host were
retained; a separate post-reload check found all 20 standing Workers connected.
The unprofiled 10k observation
[44368693491](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44368693491)
completed with **nine passed cases**: the unchanged three paths at 500, 1000 and
2000/s, one repetition, each with its full 30-second formal window. All 315,000
formal calls received complete HTTP/2 replies and successful Results were observed
after the original drain. HTTP 429, unknown submissions, not-sent and failed Results
were zero. The 3,992 initially accepted calls without an observed Result all closed
under their original IDs; warmup cohorts remain separate. Schedule-lag p99 was at
most 6.206ms and executor-dispatch p99 at most 0.352ms. The earlier invalid and
unknown outcomes remain unchanged; this does not establish maximum Server TPS.

Automatic cleanup confirmed the original 10,000 identities unknown in 8,105ms,
100 pages with eight concurrent reads. Owned Host/Harness processes and temporary
credential copies were removed; the independent postcheck agreed and found the
original standing Host with 20 connected Workers and the same Runtime process.
All four public artifacts had zero current credential-format matches, zero
complete matches against the 10,000 private Worker IDs, and zero matches against
the two current consumer secret values checked on GCP.

Separate operator `/proc` sampling retained 192 samples over a 960-second lifecycle,
covering all formal cases and an idle tail. Nginx summed RSS peaked at 1.11GB across
multiple generations, including shared pages; minimum host MemAvailable was
7.81GB (about 7.28GiB). Means include warmup/quiesce/idle and role peaks are not
simultaneous. These observations do not replace the Harness's explicit missing
Server/Redis/JFR collection fields or prove sustained capacity. Canonical consumer
and GCP records were updated, scoped operations closed, the one original restore
point and two temporary Runtime ZIP downloads removed. Runtime remains 4.4.0 and
the standing Host remains 4.4.2. Full dated evidence is retained in the
[2026-11-19 observation](../../integrations/worker-call-performance/observations/external/2026-11-19.json).

## 4.4.3 Performance transport patch

The common Performance HTTP client prefers HTTP/2 with the JDK's HTTP/1.1
fallback. The staging renderer enables HTTP/2 on TLS listeners; WSS retains
its existing HTTP/1.1 Upgrade path. The optional positive integer
`http2_max_concurrent_streams` defaults to 128. An operator must provision it
for concurrent consumer requests: the 10k Preview campaign uses 8192 for
the unchanged 4096 in-flight bound and observation/cancellation headroom.
This does not change API rate, burst, connection limits or authentication.
Runtime can remain 4.4.0; apply the published 4.4.3 renderer to the gateway
configuration and validate before a graceful Nginx reload. Do not replace
the running Runtime artifact or the standing 4.4.2 Worker Host for this patch.

The existing five-second call budget now covers client dispatch, response
headers and the complete body. Expiry cancels the same exchange without an
application retry; a completed response remains known even if its awaiting
thread resumes late. Safe timeout-stage, offer/dispatch-lag and negotiated
protocol counts explain consumer observations. They do not reconcile an
unknown request or establish that it never reached Runtime. The workload,
warmup, fast-fail limits, result checks and case statuses remain unchanged.

Private profiling of diagnostic run
[44333272615](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44333272615)
found client CPU concentrated in TLS handshake cryptography while its TCP
connection count expanded. TLS session resumption already worked, so this
patch addresses connection reuse rather than changing session-cache policy.
That profiled run retained three passed, three invalid and three saturated
cases, with 2794 attempted calls lacking HTTP responses; profiling makes it
diagnostic evidence rather than a clean performance comparison. Original
unknown outcomes are preserved without replay.

The existing ingress proof exercises real JDK/Nginx HTTP/2 stream admission,
complete mock POST receipt, connection rollover and HTTP/1.1 fallback alongside
the existing TLS, Basic/Bearer, route and WSS checks. This is transport proof,
not Runtime throughput proof.

4.4.3 was published from merged commit
`13e60437f30da282614a03c869a3e6871acdcfc7` after
[manual Proof 44349083564](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44349083564),
attempt 1, completed all 17 jobs successfully.
[Promotion 44352535338](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44352535338)
supplied the same bytes to immutable
[Release 4.4.3](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.3)
(ID `450286254`). All six GitHub digests and sidecars were verified; current
credential-format inspection found zero matches in 1,542 text entries, including
nested archives. This bounded scan is not a historical security audit.

| ZIP component | SHA256 |
| --- | --- |
| Runtime | `1fe7ce58ac83278e50cf5d4139a81610ff550a9e8f4ec7435c173e5dbf349abe` |
| Worker SDK | `dcb2b55b1d84f836bcd62548208155a7ba51502f8bc12fcdb2f02245ec8438f8` |
| Worker Simulator kit | `0ae3bc25ee5b10750ad78dec2d4e78602631479690971fc7d744b67835eeb6ec` |

The published renderer was applied to the GCP gateway with an explicit 8192
stream budget and a graceful Nginx reload. API 3000/s and burst 6000, Worker
admission and authentication remained unchanged. Runtime 4.4.0, Redis and Nginx
master processes did not restart. The configuration fingerprint became
`sha256:df061f31e09c448e6c3b99a6cf96cae84de9e8342d6f2e97aa08d2b53ac3ef67`.
The standing 4.4.2 Host retained its process and protected inputs, with all 20
logical Workers observed connected after reload. This point check does not
repeat or extend the previous 75-minute recovery proof.

The normal unprofiled 10k observation
[44353588495](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44353588495)
completed with nine invalid cases: six generator-lag and three with no qualified
Worker observations. All 75,019 complete formal replies used HTTP/2 and successful
Results were observed; 3,061 attempted calls without complete HTTP responses
remain unknown and 26,920 were not sent. No formal HTTP 429 was observed.
At 500/s, offer-lag p99 was below 11ms while dispatch-lag p99 was 198–232ms;
HTTP/2 preference alone did not repair generator pacing. The subsequent 4.4.4
reuse change and observation above completed that investigation without changing
the workload or replaying these unknown requests.

Automatic cleanup confirmed all 10,000 original identities unknown in 8,114ms,
100 pages with eight concurrent requests. Owned processes and credential copies
were removed. The independent postcheck agreed, confirmed 20 standing connections,
and retained the original Host/Runtime processes. These operational outcomes do
not turn the invalid cases into comparable performance evidence. The original
record is retained in the [dated observation](../../integrations/worker-call-performance/observations/external/2026-11-19.json).

## 4.4.2 Consumer closeout patch

The patch keeps the existing Runtime APIs, Worker protocol, workload and result
oracles. Deployed Performance connection cleanup uses a bounded complete scan
with eight concurrent pages of at most 100 original IDs, sharing the existing
30-second deadline. A missing page, read error or deadline remains unconfirmed;
safe counts and error types explain the observation without publishing identities.
It does not retry failed pages or replace unknown submissions. Sampling retains
the five-second interval after successful and failed observations. Prepared
credential copies receive a one-time five-minute cleanup fence before any secret
is written; a campaign must take ownership without extending or replaying work.

Java WSS sockets enable TCP keepalive; where all extended options are supported,
the private SocketFactory sets 60-second idle, 10-second interval and three probes.
The [Java Worker Owner](../../transport/java-worker/README.md) owns fallback and
SOCKS boundaries. This adds no application Ping or Handler execution deadline,
and is not yet a claim that every half-open path has been repaired.

4.4.2 was published on 2026-11-18 from merged commit
`ccf676801663f953169f2d03058cbe7236a01123` after
[complete manual Proof 44293971501](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44293971501),
attempt 1, with all 17 jobs successful.
[Promotion 44296410463](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44296410463)
prepared verified Draft assets. After independent audit, the immutable
[4.4.2 Release](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.2) was published
(Release ID `449899527`) with the normal three ZIPs and their three SHA256 sidecars:

| ZIP component | SHA256 |
| --- | --- |
| Runtime | `876e8110feed31b631bd5d98450d3b2420e7236ecf038609fd3d5f53d63f6866` |
| Worker SDK | `9c0059dd6f243db50f987110971430c7f1487ae3b189ab36190594b20297c99d` |
| Worker Simulator kit | `fb848b6d8194e2ddd1affc7a6507b2e22bcf47be75e1b57d233b44a35824341f` |

The release inspection found zero current credential-format matches in 1,542 text
entries, including nested JAR entries. This is a bounded artifact inspection,
not a historical leak audit or comprehensive security guarantee.

Ali installation verification retained all 20 Worker IDs and the existing
configuration, gateway credential and inventory hashes. The updated Host had
20 TCP-keepalive Worker sockets and GCP observed 20 connected Workers. Performance
kit pins now select 4.4.2 at the commit above. GCP Runtime remains 4.4.0 at
`b28a87ac918a34ffabb4feb8c63a29998a0610db`; its configuration fingerprint and
recorded service restart state were unchanged. Its `INSTALLED_RELEASE_*` pins,
scope and database were not upgraded.

The single default 10k, one-repetition campaign
[44297177821](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44297177821)
completed successfully using the external route. All nine formal measurements
are present: three 500/s cases passed, three 1000/s cases were invalid due to
generator lag, and three 2000/s cases saturated at admitted rates of
1287.7–1618.5/s. Formal requests had no observed 429; 118,789 successful Results
were observed, 15,651 requests were not sent, and 560 sent requests without HTTP
responses remain unknown without replay. Workflow success does not make the
invalid cases comparable or establish maximum Server capacity.

Automatic cleanup confirmed the original 10,000 identities as unknown in one
complete scan: 100 pages of 100, at most eight concurrent reads, 8,048ms within
the existing 30-second budget. Owned processes and temporary credential copies
were removed; the 300-second preparation timer had been armed and was stopped.
The separate operator postcheck agreed on absent campaign processes/credentials,
10,000 unknown campaign identities and 20 connected standing Workers, without
rewriting the original receipt. The four public artifacts had zero current
credential-format findings and zero complete matches against the 10,000 private
Worker IDs in the bounded publication review. Full observations and their
limitations are linked above; the older failed cleanup and unknown submissions
remain unchanged.

The standing Host then completed **4,500.018 seconds (75 minutes)** of idle
observation from **2026-11-18 11:42:07.733 UTC**, with no process restart and the
same 20 logical Worker IDs, configuration, credential and inventory. Maximum
sample gap was 5.1201 seconds. Two TCP-tuple changes were observed, including one
late-hour turnover; all original 20 sockets had been replaced by elapsed
3,558.77 seconds. GCP confirmed 20 connected after the window. This is successful
sampled continuity/recovery, not proof of zero instantaneous disconnections.

One outbound DROP from the owned Host user to GCP HTTPS was requested for 120
seconds and independently removed after 120.702 seconds, recording 620 dropped
packets. All original 20 sockets were gone during the fault; the first replacement
was observed after 82.424 seconds. After restoration, local 20-TCP recovery took
3.951 seconds, while the independent GCP check confirmed all 20 connected after
59.619 seconds, within the declared 180-second bound. The same process and logical
identities were retained; the rule was absent afterward without manual removal.

The [dated public observations](../../integrations/worker-call-performance/observations/external/2026-11-18.json)
retain the bounded persistent-consumer evidence separately from the 10k campaign
and earlier failures. These results apply to this Ubuntu/Java 21 deployment;
they establish no Android/SOCKS behavior, unlimited retry, full capacity or
zero-disconnection guarantee. Current host state still requires its own read.
The existing Ali and GCP operations records were updated and the scoped
operations closed. No GCP Runtime/Nginx restart or reload was performed; the
original Runtime artifact and protected configuration remained unchanged.

The project-owned current-tree credential check runs inside existing Proof CI;
it adds no workflow or historical secret audit. Lab remains a trusted loopback
control surface, not a new authenticated public service.

## 4.4.1 Performance consumer patch

4.4.1 corrects the shared Performance Harness bootstrap readiness check. A Group
preview can contain Worker identities retained from earlier campaigns, so checking
that its returned identities all belong to the current Host inventory can falsely
reject a ready campaign. The Harness retains the existing Group preview API and
its 1000-entry ceiling, filters observations to known current Worker IDs and
accumulates nonempty Properties coverage. Groups of up to 1000 current Workers
require coverage of all of them; larger Groups use a stable 1000-Worker sample
from the current inventory. Historical identities and empty Properties do not
count. The whole current fleet must still be RUNNING, connected and HOT; the
240-second bootstrap budget, path cases, rates, warmup, windows and result
assertions are unchanged. This changes no Server, SDK or wire contract.

4.4.1 was published on 2026-11-18 from
`60752f8b51bdc9d291bcbde53de97d65592cd984` through the ordinary Release path;
the [published Release](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.1)
retains the normal three ZIPs and SHA256 sidecars. Manifest schemas remain
Runtime 6, SDK 1 and Simulator kit 3. Do not replace files inside the published
4.4.0 kit or build a separate consumer-only Release format.

The 4.4.1 deployed-Performance combination used the published **4.4.1
Simulator kit** with the existing GCP **4.4.0 Runtime**, whose installed commit is
`b28a87ac918a34ffabb4feb8c63a29998a0610db`. Record and validate the kit's Release
tag, exact Release commit and ZIP SHA256 independently of that installed Server
version/commit. The two commits need not match; neither coordinate uses `latest`
or a fallback. This consumer correction requires no GCP Runtime replacement,
Server restart, new scope/database or replacement of the standing 20-Worker Host.
The selected 4.4.0 scope, SQLite database and retained old data below remain as
installed. The dated 2k and 10k Performance observations are linked above;
invalid/saturated cases, unknown requests and unconfirmed runner cleanup remain
explicit. Neither publication nor an operator postcheck makes all cases valid.

## Finite Task history

4.4 includes the immutable SQLite finite Task history introduced on main at
`6fb9de84cb63dbf522fb29cd64e7228ddd9c16a9`. Server publishes a complete frozen
snapshot before releasing Runtime Item/Result payloads. Runtime reads remain
Redis-only; the independent current-scope `/api/v1/storage/tasks` API reads saved
history with its existing caller/Project checks and explicit cursor pagination.
Batch Checks and Messages add their own pure interpretations of those saved rows.
The Console exposes separate history views; it does not silently replace Runtime
reads with database reads or restore historical execution.

The [Server retention and history Owner](../../server_jvm/README.md#finite-task-retention)
owns publication, expiry, access and failure semantics; [Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration)
owns defaults. Grace is 1h, Runtime retention 7d, history retention 30d and the
maintenance interval 1h. The first SQLite deployment has one Runtime writer.
Preserve the database across Runtime restarts; backup requires a stopped Runtime
and the database together with any remaining WAL/SHM files. A live WAL is not a
disposable log.

This is a fresh-scope/database cutover. Old JSONL/manifest archives and the retired
`xa.mass.task-retention.directory` configuration are not read or migrated.
Remove that legacy launch input and any `xa.mass.task-retention.archive-directory`
input; neither is a supported alias for the new database setting.
Do not point 4.4 at an old archive directory or convert it in place. No historical
identity, Task, credit or result migration is included.

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas remain 6 and 1; the Simulator
kit becomes schema 3 and adds
`performanceEntry=acceptance/performance/run_worker_call_performance.py`, with
compiled libraries under `acceptance/performance/harness/`.

The same [Worker Call Performance runner and Harness](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
now accept an explicit deployment configuration. The deployed mode retains the
original open rates `500,1000,2000`, path cases, warmup and windows, with attribution
and diagnostics off. It starts only its local Host/Harness and reuses stable
per-Group RPC Task IDs. Server coordinates and assignment ceiling are recorded
from the operator's configuration; the consumer does not change or discover them.
It supplies no Server JFR, Redis calibration, saturation, recovery or reference-host
proof and adds no CI lane or low-rate profile.

The kit also ships an ordinary persistent 20-Worker Host/systemd example. It uses
the existing Simulator and gateway credential, keeps inventory across restarts
and needs no operator key. It installs no Task timer or health-judgment loop. A
persistent Host and an on-demand Performance Host require distinct Group/inventory
coordinates; on one physical machine they still share resources and a network
failure domain. See the [kit examples](../worker-simulator/src/main/dist/examples/README.md).

**4.4.0 example erratum:** its immutable `persistent-20-host.json` used the parent
`/var/lib/xa-mass-worker/persistent-20` as `sandboxRoot`, which the Host rejects.
The external operator configuration was corrected to
`/var/lib/xa-mass-worker/persistent-20/data/scenario-workers`; the published ZIP
was not patched. The corrected example is included in 4.4.1 and later kits.

This consumer delivery change adds no Worker protocol or existing Runtime Task API
change relative to 4.3.2. The new Storage APIs belong to the history change above.
The 4.4.0 cutover acceptance uses its matching 4.4.0 kit/SDK; the 4.4.1 Performance
consumer combination is described above. No existing APK is replaced automatically
and prior-version witnesses retain their versions.

## Selected GCP cutover

Follow the existing [Release Preview procedure](../../doc/guide/operations/deploy.md#release-preview)
and its actual host records. The reviewed 4.4 deployment selects:

| Coordinate | 4.4 value |
| --- | --- |
| Redis scope | `profile_long_preview_4_4` |
| SQLite history database | `/var/lib/xa-mass/task-history-4.4.sqlite` |

Set `xa.mass.redis.scope` and `xa.mass.task-retention.database-path` accordingly
through the existing external configuration; `XA_MASS_REDIS_SCOPE` and
`XA_MASS_TASK_HISTORY_DATABASE` are the corresponding Boot inputs. Remove the
retired `directory` and any `archive-directory` settings from launch inputs;
the new YAML uses only `database-path` for this storage location. Create a fresh database,
not a copy of previous Task archives, and preserve Runtime ownership/permissions.

Keep the users file, role/Project grants, daily gateway credentials and external
Batch Checks/Messages configuration, including their enabled switches, applications
and host resource bindings. The new Redis scope starts without the old credits,
Workers, Tasks or business runtime state. Existing host Group declarations must
initialize their registrations in that new scope before Scenario dependency
validation; a Project declaration alone does not register resources. External
Hosts re-Prepare their retained coordinates and receive new Server-issued Worker
IDs in this scope. Do not compare those IDs as a retained-scope recovery witness.
Any account funding required for a tiny paid acceptance is a new explicit,
request-identified adjustment in the new scope, not a copied balance or replayed debit.

Keep the old scope and original archive data untouched. They are retained data,
not the upgrade's temporary backup. Take the existing single upgrade restore point
before stopping/switching the Runtime. Re-render the administrator ingress with
this Release's renderer so the new exact Storage routes are admitted; validate
before loading it and retain TLS, Worker restrictions and capacity settings.
Start one Runtime/Pacer in the new scope, verify the installed artifact coordinate,
readiness, database path and the declared Scenario resources. GCP still runs no
Worker Host, source build or full CI workload.

During the bounded upgrade window, a failed switch may restore the old immutable
binary, its saved configuration and old scope using that restore point. Stop 4.4
first; never run both Pacers against one scope. New-scope operations do not appear
in the old scope: preserve their receipts and database for diagnosis, without
merging histories or replaying uncertain business requests. After acceptance,
remove the previous Release directory and the single temporary restore point and
close the operations record. This does not delete the retained old scope or
original archive data. Later fixes use a new Release, not a permanent local
rollback archive.

Provision the reviewed external consumers once in the new scope: Group
`persistent-workers` serves the ordinary 20-Worker Host with the MD5 event;
`performance-a` and `performance-b` serve Project `performance-consumer` with
`extension.worker.string.md5` and the existing `extension.worker.lab.delay` event.
Declare `any` / `worker.any` enablement and an explicit per-Group `task-rpc` default
refill of `any`, empty target and count `1000` for all three Groups; built-in
`workerId` needs no registration. Add these Groups to the gateway's explicit
allowlist. Create operator user `perfcamp` with only the `performance-consumer`
Project grant, without API administrator access. Retain its standing access key
privately on GCP; copy it to the 43 consumer host only for a campaign and remove
that copy afterwards. Use separate Basic credential IDs `consumer-persistent` and
`consumer-performance`, retaining their permanent private gateway credential
files; the persistent Host's private copy must remain available for service
restarts, while campaign credential copies are removed after use. These gateway
credentials do not authenticate individual Worker identities. Leave capacity
settings unchanged; record the existing host/gateway limits and operator preflight
before a run rather than automatically tuning them. The new host receives no
Server SSH or API administrator credential.

## Acceptance and publication

Complete manual Proof at the exact candidate provides the selected Owner, Redis,
Runtime, Android, distribution and business regression evidence. Publish the
existing workflow's exact verified ZIP bytes; publication and deployment remain
separate recorded facts.

The 4.4.0 GCP cutover acceptance is small and specific to that Release:

Use the standing test identity `accept4`, currently an administrator without an
access key, for external acceptance. Before changing it, privately record its
original name, role and Project grants. Temporarily make it an operator with the
actual declared acceptance Projects (including `default`, business Projects and
`performance-consumer`), then issue one ephemeral access key for the external
acceptance client and browser. Perform administrator operations on GCP with user
`1000`; its private key stays on GCP throughout and is never given to the external
host or browser. Afterwards revoke the `accept4` key, remove its temporary copies
and restore that identity's original name, role and Project grants. Existing daily
users, grants and access keys remain unchanged; no extra acceptance user is needed.

- Verify readiness, public Console, unauthenticated API rejection and valid
  authenticated access. Confirm Batch Checks and Messages remain enabled with
  the preserved external configuration and new-scope Group/resource registrations.
- Start a small external Host from the published 4.4.0 kit and run its shipped
  finite round. Independently verify Item identities/states, MD5 Results, terminal
  Task and complete export. Use the ephemeral operator access above and a temporary
  Worker credential under the host's existing private-file procedure.
- In the actual Console, open the finite workbench history view and the Batch
  Checks/Messages history entries with an appropriately granted user. In the fresh
  scope/database, an accurate empty history view is expected before publication;
  verify API access and rendering without borrowing old-scope data or Mock rows.
  Verify the ordinary finite Task/detail/result view and a tiny affected business
  flow as needed. Record browser evidence separately from API checks.
- Leave the production retention timings unchanged. History publication/content,
  expiry and release semantics have their named repository proofs; an empty new
  database is not a claim that a historical snapshot was published on GCP. Record
  any unexercised on-host history-content path explicitly rather than waiting for
  a long-running health campaign or forcing early retention.
- Stop temporary acceptance processes, revoke their temporary credentials and
  remove private copies. Retain daily credentials, new database, new scope and
  selected production configuration. Record completed/pending checks accurately.

The new 20-Worker service and on-demand Performance command are consumer deployment
capabilities, not a requirement to run a 24-hour health loop, repeated faults or a
large public campaign during ordinary Release acceptance. The packaged local TLS
consumer fixture proves its shipped entry, not public throughput. Historical
capacity results keep their dated version/configuration/status; this Release does
not reopen a closed load investigation or assert the prior 10k business failure
has been repaired.
