# Product Coexistence Proof

Status: current shared business integration proof owner.

This proof witnesses [SMS](../../products/sms-reception/README.md) and
[Messages](../../products/message-campaigns/README.md) on the same real Server,
Redis scope, Adapter and Workers. Python owns two independent JVMs and a unique
`test_products_*` scope. It calls product APIs, public Runtime APIs and Host business
inputs. Only real `message.send` execution creates a simulated recipient record.
It never constructs Reports, calls Worker methods or reads Redis owner storage.
Single SMS and message receipts use the stable Worker coordinate `:inputs` API.
The runner retains fixture identity-to-coordinate addresses, not a second business
router; the Host validates the selected Sender/Sim before any input side effect.

## Small functional and lifecycle world

The mixed `demo-sim` Group has four Workers per country (12 total). SMS uses
the ON_DEMAND country index; Messages uses PRECOMPUTED country constraints.
A real SMS listener supplies the number
for a targeted PRECOMPUTED campaign; both execute on the same Worker and the SMS
listener subsequently receives input. The finite Task must automatically become
terminal before deliver/read/reply; another Task is explicitly closed first.
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

`productCompositionIntegrationTest` complements the process runner: four profile
combinations assert one resource set, API/Group/job gating and exact Console
forwards. Its fault fixtures use real Server services/Redis for an append whose
confirmation is lost, and a real Worker handler whose synchronous completion is
held while recipient actions publish newer content. They verify no approval or
resubmission after uncertainty, and no regression after late execution evidence.
Focused product/Host tests own input, capacity, publication and local conflict rules.

## Fixed 1,000 Worker workload

CN/US/GB = 700/200/100. SMS offers 200 applications/second for 60 seconds (12,000),
while Messages creates twelve 1,000-recipient campaigns at five-second intervals.
Finite SMS input runs at 300/second for at most 135 seconds. Every actually sent
message receives delivery, read and two replies through the Host recipient API.
The generators are bounded; persistent per-thread HTTP connections avoid measuring
ephemeral-port exhaustion as product performance. Mutations have no automatic retry.

After scheduled producers stop, convergence allows at most 120 seconds. All 12,000
SMS applications must establish, then agree with Host received/expired records;
an expired valid window is not fabricated SMS success. All 12,000 messages must be
sent and match their latest replies and associations; channel identities must be
unique. Evidence reports actual rates, percentile latency, queue observations,
errors, fingerprints and two JVM resource peaks. This fixed fixture proves neither
capacity limits, per-Task fairness, nor reliable receipt delivery under failure.

```powershell
python integrations/product-coexistence/run_proof.py --build --scenario functional
python integrations/product-coexistence/run_proof.py --scenario lifecycle --port 18540
python integrations/product-coexistence/run_proof.py --scenario load-1k --port 18560
python integrations/product-coexistence/run_proof.py --scenario functional --root <extracted-preview-root> --port 18580
```

Ordinary `product_coexistence` Proof CI runs small functional/lifecycle and fresh ZIP
functional proof. The dedicated Product Coexistence workflow accepts explicit
`workflow_dispatch` with `scenario=load-1k`; ordinary CI never selects that workload.
Artifacts contain only `summary.json` and archive fingerprints. Raw logs, source
Properties, messages/replies and private phase records remain excluded. Generic
monotonic Owner behavior remains owned by Redis Owner and Runtime Boundary.
Every invocation selects a fresh private inventory directory, even when reusing
an explicit output location. Host restarts within that invocation reuse its same
inventory; the interactive Preview instead keeps its persistent inventory.
