# Scenario Coexistence Proof

Status: current shared business integration proof owner.

This proof witnesses [SMS](../../scenarios/sms-reception-jvm/README.md) and
[Messages](../../scenarios/message-campaigns-jvm/README.md) on the same real Server,
Redis scope, Adapter and Workers. Python owns two independent JVMs and a unique
`test_products_*` scope. Its fixture explicitly declares the default seed administrator `1000` as its explicit development caller. It calls product APIs,
common Project Task/count APIs, public Runtime APIs and Host business inputs. Only real `message.send` execution creates a simulated recipient record.
It never constructs Reports, calls Worker methods or reads Redis owner storage.
Single SMS and message receipts use the stable Worker coordinate `:inputs` API.
The runner retains fixture identity-to-coordinate addresses, not a second business
router; the Host validates the selected Sender/Sim before any input side effect.
The runner materializes the exact product population through shared proof inventory
support before launching Preview: country quotas, phone order and file coordinates
remain fixed. Interactive Preview instead uses count/seed random initialization.
The proof neither searches for a suitable seed nor repairs sampled quotas.

## Finite storage acceptance

`run_retention_proof.py` is an opt-in storage workload, separate from the small
business traversal above. It runs 10,000 and 100,000 App Checks Items both to
natural completion and with an early close, in one fresh Preview scope with
2,000 Workers per App. Product mutations use HTTP; read-only Redis key sizes and
existence checks are allowed here specifically to verify resource reclamation.
It does not write scores or synthesize Worker results.

The proof overrides grace to two seconds, keeps seven-day retention during
archive comparisons, and triggers the ordinary administrator maintenance API.
It checks preserved totals, stable frozen counts, identical completed JSONL/CSV
content, archive-backed export content, bounded preview,
archived point-query refusal and retained creation identity. It then restarts
Server with one-millisecond retention to exercise startup expiry, verifies the
old resources and files are gone, and recreates each request with a new Task ID.
This policy override accelerates eligibility; it is not a production default or
a force-delete API. Focused Owner tests separately prove exact default thresholds.
For early closes, pre-freeze observations may still change while exports are
generated; the oracle compares the frozen archive and its exports, not two
independent live observations as though they were an atomic snapshot.

The summary contains per-Task Redis key memory, file bytes, archive duration,
sampled heap/RSS and Redis PING latency. JFR allocation samples and `jstat` heap
samples remain private diagnostic artifacts. Result scan deduplication retains
one ID per observed result; allocation sampling does not claim constant memory
or an exact retained-heap measurement. Process RSS need not fall after `UNLINK`.
The launcher cleans only its new scope and private archive root on exit.

```sh
python integrations/scenario-coexistence/run_retention_proof.py --port 18720
```

This opt-in workload is not added to the small Product Coexistence CI gate.

## Small functional, Pool selection and lifecycle worlds

The `functional` and `lifecycle` worlds retain 12 Demo Workers (four per country)
and no App Workers. The `pool-selection` world has the same deterministic 12-Worker
population in each of Demo, App A and App B, with nonoverlapping phone ranges.
These receipt fixtures explicitly select lab-json; Preview itself defaults to text.
Every scenario starts an independent scope, inventory and App Checks configuration
database (a sibling of that private inventory). The packaged seed is used only on
first initialization; no shared Preview database or window YAML overlay is reused. App Checks retains its
independent one-shot witness in the same CI lane.
Its Project binds Demo plus App A/B: Demo hosts carrier and local text checks,
while App A/B retain registration checks. The Pool fixture asserts those exact
bindings without preparing RPC Tasks for the finite-only scenario.

Functional first checks the Messages application's empty creation identity and
conflict response, then closes that empty Task. SMS uses its partitioned lease Pool and returns number plus messageId. The
same-Worker/Reporter witness uses a generic finite Task with an explicit workerId
selector targeting the actual SMS Worker, not a Messages senderPhone parameter.
The Task has no Pool supply and has raw input ownership. Its known Item IDs are
observed through the common Result API; ordinary Messages Tasks use the business
result view plus common Project state/counts. It is a Runtime-directed mechanism fixture.
Kernel
still requires due HOT for execution. Both execute on the same Worker and the SMS
listener subsequently receives input. The finite Task must automatically become
terminal before releasing the automatically generated delivered receipt (hold is
enabled before send), then before manual read/reply; another Task is explicitly
closed first. Lab already records DELIVERED while the platform retains SENT during hold.
Each receipt is checked independently through Result and Item Score; product-created
Tasks additionally use the business result view. No cross-owner atomicity is inferred. Normal checkpoints allow 5 seconds
from the business request to both platform and product observations; temporary
observation failures may be retried only within that deadline. Mutations are never
automatically replayed. Each small scenario is bounded to 180 seconds, excluding
startup; each send still has 30 seconds and each receipt checkpoint 5 seconds.
The oracles use complete snapshots and actual Worker identity.

Recipient receipts can be held and released newest-first, older-stage-last or
duplicated using existing receipt IDs. Continuous replies retain latest content.
An actual repeated Worker execution returns the original message and retains its
first Reporter. That duplicate runs in a separate finite generic Task with no
Pool supply; the proof does not derive a Task from SMS countries or submit
message sends into SMS's managed Task. Worker stop/start releases full local observations and cannot
transfer the old Reporter; retained Runtime Results remain while the new run works.
An additional message runs automatic read and two replies through actual HTTP.
Their text is identical, so the oracle waits for the latest reply operation ID,
not merely REPLIED or equal text. Repeated Result reads must retain that content.
Manual callbackQueued only proves local queue admission; platform observations
remain independent. Lifecycle rejection is checked after HTTP callback completion.
Host tests also block callback HTTP, exercise early callbacks, lost send responses,
queue saturation and startup readiness; the retired direct Reporter path cannot
pass those tests.

The Worker endpoint's focused tests use a synchronous sending operation without a
Lab, HTTP client or listener, proving that original-Reporter association does not
require the receiving implementation. Host composition tests additionally pause
real receiving admission with a barrier, race Worker stop, and require the
admitted observation and association to be gone when stop completes. These Owner
proofs complement, rather than replace, actual HTTP/Worker traversal below.
The composition refactor preserves source/fresh-ZIP fixtures, mixed-load callback
pacing, 105000-send workload, deadlines and capacities; it makes no throughput claim.

The functional world sends four messages. Terminal export first observes held SENT
content, then later replies, and can be repeated after a new reply. Exported
payloads remain private; only pass/fail witnesses enter the summary.

`pool-selection` creates six product Tasks: two US-constrained recipients and two
ANY recipients for each application, twelve successful messages in total. Creation
carries appId; independent Task descriptor and actual Host Worker evidence must
agree on that application's Group. Sender and recipient countries remain independent.
Repeated creation returns the same identity, while changing the application conflicts.
All sends use Messaging Pool declarations and never pass senderPhone. Each message's
automatic delivery is observed. A seventh Task imports ordinary text: API preserves
it exactly, but the explicitly selected lab-json Worker rejects it, yielding one failed Item, zero sent/delivered
counts and no Lab message. It is not a throughput or large-input proof.

No SMS request or RPC Item is created in this world. SMS explicitly prepares one
shared RPC Task, which must remain running-initial before and after the workload.
Messages and App Checks must contain no long-lived RPC Task. These checks preserve
the premise that no hidden task supplies competing Country/window refill demand.

This separation makes the resource premise explicit: the ordinary query witness
has only Messaging Pool demand. A generation enters at most one Pool; a finite
population already admitted elsewhere need not become available within the send
window. The proof makes no cross-Pool fairness, starvation-freedom under insufficient
supply or fixed-throughput claim. It does not prewarm stock, change Properties,
restore Pool sharing or retry mutations to construct a preferred supply state.
The `lifecycle` world retains same-Worker restart and original Reporter checks via
explicit generic Task/workerId fixtures. Worker stop now releases full local observations
and held receipts. The runner checks those releases, rejects old-message actions
after restart, keeps the old Runtime SENT Result and verifies a fresh message
on the same Worker. Focused HTTP tests retain the already-admitted callback/stop
race; no old Reporter is rebound. It does not retain a hidden directed
Messages creation API. Pool-only product evidence stays in pool-selection.

`scenarioCompositionIntegrationTest` complements the process runner: platform/preview assembly asserts one resource set, API/Group/lifecycle gating and exact Console
forwards. Its fault fixtures use real Server services/Redis for an append whose
confirmation is lost, and a real Worker handler whose synchronous completion is
held while recipient actions publish newer content. Additional text fixtures exhaust
Reporter capacity and run short real retention windows, prove Task completion
without receipts and reuse capacity without restarting the Worker. They verify no approval or
resubmission after uncertainty, and no regression after late execution evidence.
Focused product/Host tests own input, capacity, publication and local conflict rules.

## Fixed 1,000 Worker workload

CN/US/GB = 700/200/100. SMS offers 25 acquisitions/second for 60 seconds (1,500),
while Messages creates twelve 1,000-recipient campaigns at five-second intervals.
Finite SMS input runs at 300/second for at most 135 seconds. Every actually sent
message receives automatic delivery from HTTP acceptance, then read and two replies
through the Host recipient API. There are still four committed receipt facts per
message; manual request latency now measures three actions. This protocol change
makes that latency sample incomparable with the old four-manual-action workload;
this slice makes no new load-performance claim.
The generators are bounded; persistent per-thread HTTP connections avoid measuring
ephemeral-port exhaustion as product performance. Mutations have no automatic retry.
The fixture uses the existing hold/release control for automatic delivery and manual
receipts. The 24 recipient workers each commit the three manual actions. One
generator-local admission gate reserves room below 32 outstanding callbacks before
releasing a message's four original receipts exactly once. Completion uses the
smaller of HTTP success and Reporter admission counters. Input workers do not wait
for every other producer to reach a simultaneous empty queue; final convergence
still requires all callbacks admitted. Each receipt takes its own HTTP request and
original Reporter path.
Pacing uses compact Host callback counters; the final oracle independently checks
every receipt's one HTTP attempt and Reporter acceptance, plus complete Results.
This bounds outstanding callbacks,
not only short-lived mutation requests, and prevents a fast sender from turning
this complete-receipt fixture into a queue-overflow test. A dropped callback still
fails the proof; all four facts, latest Results and associations remain mandatory.
Queue-overflow loss is proved separately by the focused Host tests. This pacing
change makes recipient action latency incomparable with older generator runs.

After scheduled producers stop, convergence allows at most 120 seconds. All 1,500
SMS applications must establish, then agree with Host received/expired records;
an expired valid window is not fabricated SMS success. All 12,000 messages must be
sent and match their latest replies and associations; channel identities must be
unique. Evidence reports actual rates, percentile latency, quantity observations,
errors, fingerprints and two JVM resource peaks. This fixed fixture proves neither
capacity limits, per-Task fairness, nor reliable receipt delivery under failure.
An early producer/recipient failure retains its safe stage, exception type and
accepted count instead of only a generic load-aborted assertion. The first failure
also prints call-site names and elapsed time, never request or response bodies;
diagnostics do not retry the failed mutation or relax the final oracle.
Failure summaries also retain the last observed callback/held-receipt counters,
without extra observation requests or receipt content.

## Explicit Messages sending workload

`messages-send-100k` runs on the same two-process Preview composition with 1000
Workers (Demo 400, App A 300, App B 300), fixed CN inventory and text mode. App A
first sends 100000 recipients. Fifty subsequent 100-recipient Tasks run sequentially,
rotating applications, with the same Host: 105000 unique message identities and 51
Tasks cross both retired cumulative limits. Every Task uses product create/import/
approve and the original Task-plus-normalized-recipient identity algorithm.

Import is timed separately. The sum of sending/observation phases has a 900-second
budget; it is not a production SLA. Default ten-minute Host windows are unchanged.
Transient read-only observation failures are polled again within that same phase
deadline, using the existing five-second request timeout. Create, import and approve
mutations remain single-attempt; invalid responses and failed evidence fail immediately.
The runner continuously reads known IDs through results:load, receiver
acceptances:load and items:states in bounded batches. A successful Result must have
its matching receiving fingerprint, original Group/Worker/phone/time and terminal
Item tag 6. Missing/expired receiving evidence fails; the runner never reconstructs
acceptance from Results or a retained-message page. Each Task must become terminal
with exact sent count, zero failures and zero delivered/read/replied counts.

Public evidence contains counts, timings, identity digests, cache maxima, actual
process samples and send-attempt/duplicate diagnostics, never bodies or numbers.
Source acceptance, Result and Item witnesses are independent; they establish the
real Redis/HTTP/Worker path and simulated receiver acceptance, not external App
delivery. Process samples include RSS/threads and Linux FDs (Windows reports handles
instead). Full completion requires bounded caches, observation admission actually
skipped under capacity, no automatic callbacks and continued sending after the first
large Task. The proof does not claim fairness, concurrent Handler count, sustained
unbounded throughput, soak or improvement over another version.

```powershell
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle --port 18540
python integrations/scenario-coexistence/run_proof.py --scenario load-1k --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario messages-send-100k --port 18700
python integrations/scenario-coexistence/run_proof.py --scenario functional --root <extracted-preview-root> --port 18580
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --root <extracted-preview-root> --port 18600
```

Ordinary `product_coexistence` Proof CI runs source functional, Pool selection and
lifecycle, plus fresh ZIP functional and Pool selection. Each proof has its own
step, output directory, port and scope. App Checks also runs in independent source
and ZIP steps, with explicit development caller headers and saved-creator checks.
App Checks uses two startup seed fixtures for success/failure and negative/mixed
answers; the lightweight create request carries no execution parameters;
its [Owner](../../scenarios/app-checks-jvm/README.md#装配与证明) defines the separate
caller/ownership proof and its limits. After common preparation succeeds, one proof failure does not skip
the others; ZIP execution additionally requires successful archive validation.
Cancellation stops further execution and any required failure still fails the job,
whose ordinary 20-minute limit is unchanged. The existing workflow accepts explicit
`workflow_dispatch` with scenario=load-1k or messages-send-100k; the latter has a
40-minute job budget including preparation. Ordinary CI selects neither workload.
Artifacts contain safe summary.json, archive fingerprints and the sending workload
process-resources.jsonl. Raw logs, source
Properties, messages/replies and private phase records remain excluded. Small
scenario summaries retain `completedStages` and completed receipt checkpoints on
failure, plus `failedStage` and the exception type. Fixed labels distinguish supply
preconditions, US/ANY sends, receipts, exports and lifecycle steps. Remote exception
content stays private; diagnostics do not replay mutations or extend deadlines. Generic
monotonic Owner behavior remains owned by Redis Owner and Runtime Boundary.
Every invocation selects a fresh private inventory directory, even when reusing
an explicit output location. Host restarts within that invocation reuse its same
inventory; the interactive Preview instead keeps its persistent inventory.

## RPC Result memory proof

`run_rpc_result_proof.py` uses the same real Server/Worker Preview and a fresh
`test_*` scope. The default workload submits 10,000 raw RPC Items in batches of
100 through 200 directed Workers. It checks each returned identity and MD5
payload, retains actual receipts, and takes only unresolved IDs. Sampling records
Result HASH fields/bytes and Redis PING latency; the summary also records HTTP
batch latency and execution-resource memory. A stopped Worker produces an
unclaimed late Result, a 15-second test idle TTL expires it, and a new call on the
same Task proves continued operation. The production default remains seven days.

```powershell
python integrations/scenario-coexistence/run_rpc_result_proof.py --output build/rpc-result-memory --port 18730
python integrations/scenario-coexistence/run_rpc_result_proof.py --root <extracted-preview-root> --output build/rpc-result-zip-memory --port 18730
```

This explicit proof measures Result reclamation, not RPC Item cleanup, reliable
delivery, a capacity limit or a latency SLA. It does not run the large coexistence
workload or replace its business assertions.

## Task management read boundary

Product preparation creates an empty Task with appId through /api/v1/messages/tasks, retains its
Server identity, uploads UTF-8 recipients through recipients:import and explicitly
approves the confirmed count. No mutation is automatically retried; an unconfirmed
phase stops that workflow. Pool-selection and load worlds use this three-operation submission path. The
functional/lifecycle directed controls use public generic Task create/append/approve
for their distinct identity/Reporter assertions; mutation failures stop either path. Small worlds verify
the bounded business Result view against independent Item states and raw Results. Large
mixed worlds discover actually executed message IDs from retained Lab observations,
then use public results:load in batches of 100. The large text workload instead
derives its complete expected identity set from frozen Task inputs and point-reads
acceptance fingerprints; missing evidence fails rather than shrinking the expected set.
The UI's 100-row preview is never promoted to a full-task count. Quantities come
from common Project `tasks:stats` observations; Server Task queries independently
provide lifecycle state. The retired Campaign cache, asynchronous
submission queue and Messages metrics API are not proof inputs. The existing
three manual actions plus automatic delivered still require four committed receipt
facts per message. Boot also restarts Server and reads the same saved Task and reply.

The combined 1k workload retains its 12,000 Messages sends/receipts and offers SMS
at 25 acquisitions/sec for 60 seconds (1,500 leases), within the three-app number
window premise. Its external client reads SMS Results by the returned messageIds;
there is no backend SMS history API. This is separate from the 30/sec, 180-second
SMS-only fixture. Latest SMS content is compared after the finite windows finish.
