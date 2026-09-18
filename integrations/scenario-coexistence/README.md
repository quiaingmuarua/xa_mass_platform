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

## Small functional and lifecycle world

The mixed `demo-sim` Group has four Workers per country (12 total). SMS uses
the country Pool; Messages consumes messaging Pool stock with country constraints.
A real SMS listener supplies the number
for a campaign's additional phone condition; both execute on the same Worker and the SMS
listener subsequently receives input. The finite Task must automatically become
terminal before releasing the automatically generated delivered receipt (hold is
enabled before send), then before manual read/reply; another Task is explicitly
closed first. Lab already records DELIVERED while the platform retains SENT during hold.
Each receipt is checked independently through Result, Item Score and product
projection. No cross-owner atomicity is inferred. Normal checkpoints allow 5 seconds
from the business request to both platform and product observations; temporary
observation failures may be retried only within that deadline. Mutations are never
automatically replayed. Each functional/lifecycle phase is bounded to 180 seconds,
excluding startup, and uses complete snapshots and actual Worker identity.

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

Campaign preparation uses deterministic international recipient numbers and
independent recipientCountry/senderCountry fields. The functional world also
sends the same CN recipient list through a US-constrained query and an ANY
Messaging query, checking actual Worker identity and Lab reception. ANY does not
promise distribution among countries. Terminal export first observes held SENT
content, then later replies, and can be repeated after a new reply. Exported
payloads remain private; only pass/fail witnesses enter the summary. The world
remains 12 Workers and the existing 180-second phase/5-second checkpoint budgets.

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
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle --port 18540
python integrations/scenario-coexistence/run_proof.py --scenario load-1k --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario functional --root <extracted-preview-root> --port 18580
```

Ordinary `product_coexistence` Proof CI runs small functional/lifecycle and fresh ZIP
functional proof. The dedicated Scenario Coexistence workflow accepts explicit
`workflow_dispatch` with `scenario=load-1k`; ordinary CI never selects that workload.
Artifacts contain only `summary.json` and archive fingerprints. Raw logs, source
Properties, messages/replies and private phase records remain excluded. Generic
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
