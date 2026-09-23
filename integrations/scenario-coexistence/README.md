# Scenario Coexistence Proof

Status: current shared business integration proof owner.

This proof witnesses [SMS](../../scenarios/sms-reception-jvm/README.md) and
[Messages](../../scenarios/message-campaigns-jvm/README.md) on the same real Server,
Redis scope, Adapter and Workers. Python owns two independent JVMs and a unique
`test_products_*` scope. It calls product APIs, public Runtime APIs and Host business
inputs. Only real `message.send` execution creates a simulated recipient record.
It never constructs Reports, calls Worker methods or reads Redis owner storage.
Single SMS and message receipts use the stable Worker coordinate `:inputs` API.
The runner retains fixture identity-to-coordinate addresses, not a second business
router; the Host validates the selected Sender/Sim before any input side effect.
The runner materializes the exact product population through shared proof inventory
support before launching Preview: country quotas, phone order and file coordinates
remain fixed. Interactive Preview instead uses count/seed random initialization.
The proof neither searches for a suitable seed nor repairs sampled quotas.

## Small functional, Pool selection and lifecycle worlds

The mixed `demo-sim` Group has four Workers per country (12 total). The existing
fixture uses `app_count=0`: new Preview App Groups have no Workers
in this proof. Their independent one-shot witness belongs to
[App Checks](../../scenarios/app-checks-jvm/README.md#装配与证明), run in the same CI lane.
Each small scenario starts an independent scope and Host inventory. In `functional`,
SMS uses the Country Pool. A real SMS listener supplies the number
for a campaign's qualified Direct Phone query with empty Pool supply. Phone Index
and current Worker Facts establish the identity and message qualification; Kernel
still requires due HOT for execution. Both execute on the same Worker and the SMS
listener subsequently receives input. The finite Task must automatically become
terminal before releasing the automatically generated delivered receipt (hold is
enabled before send), then before manual read/reply; another Task is explicitly
closed first. Lab already records DELIVERED while the platform retains SENT during hold.
Each receipt is checked independently through Result, Item Score and product
projection. No cross-owner atomicity is inferred. Normal checkpoints allow 5 seconds
from the business request to both platform and product observations; temporary
observation failures may be retried only within that deadline. Mutations are never
automatically replayed. Each small scenario is bounded to 180 seconds, excluding
startup; each send still has 30 seconds and each receipt checkpoint 5 seconds.
The oracles use complete snapshots and actual Worker identity.

Recipient receipts can be held and released newest-first, older-stage-last or
duplicated using existing receipt IDs. Continuous replies retain latest content.
An actual repeated Worker execution returns the original message and retains its
first Reporter. Worker stop/start retains local records but cannot transfer the old
Reporter; old product observations remain unchanged while the new run works.
An additional message runs automatic read and two replies through actual HTTP.
Their text is identical, so the oracle waits for the latest reply operation ID,
not merely REPLIED or equal text. Repeated Result reads must retain that content.
Manual callbackQueued only proves local queue admission; platform observations
remain independent. Lifecycle rejection is checked after HTTP callback completion.
Host tests also block callback HTTP, exercise early callbacks, lost send responses,
queue saturation and startup readiness; the retired direct Reporter path cannot
pass those tests.

The functional world sends four messages. Terminal export first observes held SENT
content, then later replies, and can be repeated after a new reply. Exported
payloads remain private; only pass/fail witnesses enter the summary.

`pool-selection` sends the other four messages in an independent 12-Worker world:
two with a US sender constraint, then two through ANY, using the same deterministic
CN recipient list. It checks actual Worker identity, independent sender/recipient
countries and the Host's matching message records. ANY does not promise a country
distribution. Neither request supplies a phone; public Project Task descriptors
must declare Messaging supply. The runner creates no SMS listener or managed-Task
Item and checks, before and after these sends, that the SMS and Messages managed
Tasks remain `running-initial`. They therefore create no active Country refill
demand. Production Preview configuration and supply counts remain unchanged.

This separation makes the resource premise explicit: the ordinary query witness
has only Messaging Pool demand. A generation enters at most one Pool; a finite
population already admitted elsewhere need not become available within the send
window. The proof makes no cross-Pool fairness, starvation-freedom under insufficient
supply or fixed-throughput claim. It does not prewarm stock, change Properties,
restore Pool sharing or retry mutations to construct a preferred supply state.
The existing `lifecycle` world retains the same Worker restart and Reporter checks.

`scenarioCompositionIntegrationTest` complements the process runner: platform/preview assembly asserts one resource set, API/Group/lifecycle gating and exact Console
forwards. Its fault fixtures use real Server services/Redis for an append whose
confirmation is lost, and a real Worker handler whose synchronous completion is
held while recipient actions publish newer content. They verify no approval or
resubmission after uncertainty, and no regression after late execution evidence.
Focused product/Host tests own input, capacity, publication and local conflict rules.

## Fixed 1,000 Worker workload

CN/US/GB = 700/200/100. SMS offers 200 applications/second for 60 seconds (12,000),
while Messages creates twelve 1,000-recipient campaigns at five-second intervals.
Finite SMS input runs at 300/second for at most 135 seconds. Every actually sent
message receives automatic delivery from HTTP acceptance, then read and two replies
through the Host recipient API. There are still four committed receipt facts per
message; manual request latency now measures three actions. This protocol change
makes that latency sample incomparable with the old four-manual-action workload;
this slice makes no new load-performance claim.
The generators are bounded; persistent per-thread HTTP connections avoid measuring
ephemeral-port exhaustion as product performance. Mutations have no automatic retry.

After scheduled producers stop, convergence allows at most 120 seconds. All 12,000
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

```powershell
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle --port 18540
python integrations/scenario-coexistence/run_proof.py --scenario load-1k --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario functional --root <extracted-preview-root> --port 18580
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --root <extracted-preview-root> --port 18600
```

Ordinary `product_coexistence` Proof CI runs source functional, Pool selection and
lifecycle, plus fresh ZIP functional and Pool selection. Each proof has its own
step, output directory, port and scope. App Checks also runs in independent source
and ZIP steps. After common preparation succeeds, one proof failure does not skip
the others; ZIP execution additionally requires successful archive validation.
Cancellation stops further execution and any required failure still fails the job,
whose 20-minute limit is unchanged. The dedicated workflow accepts explicit
`workflow_dispatch` with `scenario=load-1k`; ordinary CI never selects that workload.
Artifacts contain only `summary.json` and archive fingerprints. Raw logs, source
Properties, messages/replies and private phase records remain excluded. Small
scenario summaries retain `completedStages` and completed receipt checkpoints on
failure, plus `failedStage` and the exception type. Fixed labels distinguish supply
preconditions, US/ANY sends, receipts, exports and lifecycle steps. Remote exception
content stays private; diagnostics do not replay mutations or extend deadlines. Generic
monotonic Owner behavior remains owned by Redis Owner and Runtime Boundary.
Every invocation selects a fresh private inventory directory, even when reusing
an explicit output location. Host restarts within that invocation reuse its same
inventory; the interactive Preview instead keeps its persistent inventory.

## Task management read boundary

Preparation now posts /api/v1/messages/tasks synchronously (create, append,
automatic approval) and retains the returned Server Task ID. Small worlds verify
the bounded Messages detail against independent Item states and Results. Large
worlds discover only actually executed message IDs from Lab, then use public
results:load in batches of 100; missing Results still fail the complete witness.
The UI's 100-row preview is never promoted to a full-task count. Quantities come
from the Task list's Score observations. The retired Campaign cache, asynchronous
submission queue and Messages metrics API are not proof inputs. The existing
three manual actions plus automatic delivered still require four committed receipt
facts per message. Boot also restarts Server and reads the same saved Task and reply.
