# SMS Reception

Status: current business API and tracked reception contract.

SMS is a tracked TaskItem scenario. Number acquisition returns promptly; later SMS
observations update that same Item's Result. Client queries read the Result directly.
The platform does not poll a registry of outstanding SMS orders. Device SMS input is
simulated; scheduling, transport, result storage and queries use the real platform.

## 模块和执行路径

```text
POST numbers:lease -> Server managed Call -> Matching partition/country stock
  -> Kernel execution admission -> Worker establishes reception association
  -> initial Result contains phoneNumber, messageId, leaseUntil
  -> normal Worker execution lease release

stored Result notice -> bounded Server projection -> Matching lease ZSET
raw sms.receive input -> original Reporter(tag 9) -> same Item's latest Result
GET messages/{messageId} -> Server Result point read -> business snapshot
```

[SmsReceptionService](src/main/java/com/xa/mass/scenario/sms/SmsReceptionService.java)
uses `ProjectDirectory`, mapped `TaskRpcCallService` and `TaskDataService`.
SMS contributes immutable `PartitionedLeasePoolDefinition` and pure
`TaskLeaseProjection` values. Server owns the bounded result handoff and Matching
owns its storage effects. No Scenario Redis client, Pacer dependency, waiter,
submission queue, business result mirror or background Result scan exists.

The `sms` Project's managed Task retains the original messageId. It is generated
before submission and is also carried in the opaque Worker payload. There is no
second identity database. Business success is interpreted from the complete
snapshot, not inferred from Kernel's `succeeded` status alone. A later reply can
arrive before the initial execution Result without losing its number or deadline.

## 取号与租期

| API | Contract |
| --- | --- |
| `POST /api/v1/sms/numbers:lease` | `{applicationId,country,leaseSeconds?}`; returns the original messageId and, once observed, phoneNumber and leaseUntil |
| `GET /api/v1/sms/messages/{messageId}` | Repeatable, non-consuming latest Result read; never creates a Command or renews the lease |
| `GET /api/v1/sms/catalog` | Immutable application/template/country directory and limits |
| `GET /api/v1/sms/metrics` | Bounded acquisition/query latency samples, errors and lease projection/inventory counters |

Each acquisition creates a new Item. Default duration is 60 seconds, bounded to
1–300 seconds. The Worker sets the absolute deadline when it establishes the
association. Call waits at most 3 seconds; `NOT_OBSERVED` preserves the original
messageId for further queries. The Item has a 30-second establishment TTL and the
existing five-attempt policy. Submission failure can remain uncertain under the
ordinary Call contract; the scenario does not replay it.

`WAITING` means a number is allocated with no observed SMS; `RECEIVED` retains the
latest SMS even after expiry. `leaseActive` is the deadline comparison, not a live
Worker-health claim. `EXPIRED` means the window ended without an observed SMS;
it does not prove the physical device never received one. `FAILED` reflects an
explicit execution/business rejection or invalid business snapshot. Missing
Results remain `NOT_OBSERVED`, including unknown IDs; no order-existence registry
is introduced. There is no cancellation, renewal or server-side history API.

The independent state is a Matching resource-local ZSET:
`encode(number, app, workerId) -> leaseUntil`. Only successful establishment Results
produce updates. Refill and queries never write deadlines. Missing or expired
members qualify even while the expired member remains stored. Repeated writes
keep the greater deadline. There is no lease CAS, distributed exclusivity, reliable
projection replay or expiry deletion in this slice. Delayed/lost projection may
permit repeated allocations; this is measured best-effort behavior.

## 分区库存

[Matching](../../worker_matching_jvm/README.md#partitioned-lease-pools) owns one
`sms-reception` memory Pool and `worker.sms.available` query with scalar partition
and country inputs. Preview declares A/B/C × CN/US/GB targets of 100 references
each, under the existing Group/Pool and process budgets. The declarations belong
to the managed Task configuration and are independent of Item queries.

A candidate generation may have multiple app/country references inside this Pool.
Consumption removes all references to that generation; later supply comes from
normal Pacer candidateization after execution release. Full targets skip refill
qualification, and each refill checks at most 1,000 offered Worker/partition
coordinates. It never enumerates the complete Worker × app product. Shared views retain observed availableAt deadlines: future views do not count as
available/full, and become consumable at the deadline without rereading Facts. They
are replaceable under capacity pressure and share the existing stock TTL. This
avoids coupling short lease expiry to a 30-second candidate recycle. Consumption
uses these admitted snapshots without rereading Facts. Pool loss is recoverable through
ordinary refill; persistent lease state and Results survive Server restart.

## 持续收码与 Worker 生命周期

The sole event is `extension.worker.sms.number.lease`. The
[Simulator](../../worker_simulator_jvm/README.md#sms-scenario) installs one shared
SMS input path; each active association retains its original SDK Reporter.
Returning the number ends the initial execution, not the observation window.

Templates remain A: `[A] ` plus six digits (priority 200), B: `[B] ` plus six digits
(priority 100), and C: any nonempty SMS (priority 0). Every input chooses one active
association by template priority, creation order and messageId. The winner remains
active and later matches replace its latest SMS. Reporter tag is 9; its ordering
time advances monotonically per association, while payload `receivedAt` retains
the actual receive time. Publication occurs outside the number state gate.

Expiry only releases the local association; it does not overwrite stored SMS with
an empty terminal report. Stop, phone replacement and run closure revoke Reporters
without synthesizing remote outcomes. A new run never adopts the original Reporter.
A Server reconnect in the same Worker run preserves the original association.

Host admission is bounded by 50,000 active associations and 64 per number. Recent
finished diagnostics are FIFO-bounded to 50,000, and SMS fingerprints to 100,000;
old fingerprints can be evicted. Duplicate retained SMS IDs are ignored, conflicting
content is rejected. Recent finished associations can return their original snapshot
on duplicate execution; this is not durable idempotency. A deadline queue processes
at most 512 expired associations per 100ms tick instead of scanning every device.

## 页面、启动与交付

Follow [Preview](../../distribution/server/PREVIEW.md#source-launch):

```powershell
python run_local_runtime.py --profile preview
```

The shared Console serves `/sms` and `/sms/metrics`. It shows the number, messageId,
window and latest SMS, supports manual ID lookup, and polls the selected active
reception once per second, including after the first SMS. It retains at most 100
records in browser session storage. Server restart does not discard those IDs.
There is no browser call to the Host; raw SMS input uses the separate `/lab` page.

[Boot](../../server_boot_jvm/README.md#platform-and-preview) owns Group enablement,
Project/managed Task and refill configuration. Ordinary platform profile has no SMS
API or resource declaration. Scenario shutdown rejects new calls before platform
shutdown. [Distribution](../../distribution/server/PREVIEW.md#archive-delivery)
packages the same frontend, Server and Host; there are no embedded SMS assets.

## 检查与验收

Focused checks cover the Matching resource, Server handoff/mapped waiter, business
snapshot mapping and Worker associations. Real Redis proves independent coordinates,
monotonic deadlines and reopening without deleting expired members. These are separate
from real Runtime proof; source or unit success does not establish device reception.

```powershell
.\gradlew.bat :worker_matching_jvm:test :server_jvm:test :worker_simulator_jvm:test :scenarios:sms-reception-jvm:test
.\gradlew.bat :server_jvm:redisOwnerIntegrationTest --tests '*PlatformLeaseIntegrationTest'
.\gradlew.bat :server_boot_jvm:test :server_boot_jvm:smsCompositionIntegrationTest
python -m unittest discover -s scenarios/sms-reception-jvm -p 'test_*.py'
python scenarios/sms-reception-jvm/run_acceptance.py --build --scenario functional
python scenarios/sms-reception-jvm/run_acceptance.py --scenario lifecycle
python scenarios/sms-reception-jvm/run_acceptance.py --scenario concurrency
```

Functional uses CN/US/GB = 1/1/1 real Workers. It witnesses cross-app sharing, latest
SMS replacement, repeatable reads without Handler execution, another execution during
reception, expiry/reuse, persisted leases and Results across actual Server restart,
refill recovery, and Worker property/address changes. Lifecycle witnesses actual
Worker stop/start and old Reporter isolation. Boot composition blocks public Task
HTTP while the Scenario calls the shared application services. Coexistence retains
its separate Messages/App Checks witnesses.

The load fixture uses 1,000 Workers (700/200/100), three applications, 60-second
leases and 30 acquisitions/sec for 180 seconds (5,400 requests). SMS input is
300/sec for up to 240 seconds. The external client performs an initial point read
per acquisition and final latest-result queries; this is not an all-active-clients
one-second-poll capacity claim. It reports acquisition/query P95/P99, active
associations, stock/projection counters, duplicate lease rate, latest-result
observation loss and Server/Host resources. Normal provisioned acquisitions must
establish; false SMS associations fail. Zero duplicate leases or reliable delivery
are not promised, and neither this fixture nor green CI establishes a platform limit.

The runner uses exact pre-materialized SMS-only inventory with `app_count=0`, a
unique `test_*` scope and the packaged launcher's lifecycle/cleanup. Fresh ZIP
functional must run with `--root <extracted Preview>` and no build or checkout
fallback. Summaries contain bounded metadata and fingerprints; private logs are
not public artifacts. `.github/workflows/sms-reception-preview.yml` selects the
regular functional/lifecycle/ZIP checks; the sustained workload is explicit.
