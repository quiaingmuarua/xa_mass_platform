# Kernel Scheduling Mainline

Status: current cross-owner scheduling flow and scale boundary.

The [Kernel index](README.md) links mechanical and policy contracts. This
page explains their handoffs; encoding, individual round limits and legal
transitions remain with the owning documents.

## Independent Scheduling Truth

Task, TaskItem and Worker Scores are independent, opaque scheduling
coordinates. They are neither resource write locks nor projections of network
state. The [Task Score](../../kernel_jvm/doc/score/task-score-band-scheduling.md)
owner controls Task scheduling visibility;
[TaskItem Score](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md)
controls claim, retry and final outcome;
[Worker Score](../../kernel_jvm/doc/score/worker-score-band-scheduling.md)
controls acquisition, lease and serviceability eligibility.

## Dispatch Mainline

```text
Server creation
  -> local Matching admission of optional Pool supply declarations
  -> complete Kernel Task descriptor; Item selector validated by Matching

Kernel Main Scheduler
  -> bounded due RUNNING observation -> INITIAL initialization
  -> complete NORMAL descriptors shared with Producers
  -> refill, Task dispatch and optional Serviceability

Task dispatch
  -> due Items; TTL/exhaustion settlement
  -> Group + messageId-to-WorkerQuery map -> fixed Matching query functions
  -> Pool stock with original fences / direct identity hints
  -> strict observed / current execution acquisition
  -> exact Item claim -> Command
  -> ACTIVE recheck before exact Task close or idle park
```

The Main Scheduler supplies every Producer's root Task/Group identities.
Finite Tasks require explicit approval before INITIAL processing; managed Calls
use their Group's registered reusable Task. Task lifecycle and descriptor storage
belong to the [Task Owner](../../kernel_jvm/doc/resource-model/task-resource-model.md).
Producers discover only vertical resources under those inputs. Busy Producers
skip that snapshot; they do not accumulate a pending source queue. Assembly and
lifecycle are defined in
[Pacer Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md).

Task supply declarations name Pools and carry refill targets; Item queries name
functions and carry function-local input. These are independent contracts.
Matching's Catalog coordinates target merging and bounded calls. Refill policies
own qualification and deficits; query functions interpret Item inputs; Pool
resources own bounded stock. Index resources are maintained from facts
independently of Pool demand. See the
[Matching resource composition](../../worker_matching_jvm/README.md#fixed-resource-composition).

For Pool supply, Pacer candidateizes due ordinary HOT without advancing time.
Matching admits that generation into at most one Pool with its own local TTL;
accepted identities leave the remaining supply batch. Stock is shared among Tasks
within a Pool, never copied to another Pool. Pacer separately recycles aged
generations. Direct acquisition does not notify or edit Pool stock. A Pool function consumes
stock; Kernel exact-acquires its due fence for execution. Direct workerId/worker.phone
functions return identity hints for atomic current due acquisition. Both write a
new execution fence; current/future holds cannot be preempted. Strict failure
never falls back to identity acquisition. Rejected/unmatched candidates await
bounded recycling, while unused local stock expires independently.

Pacer carries names and immutable
data without interpreting business queries, reading facts or constructing index
coordinates. Item queries cannot create refill demand. See
[Assignment and Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md).

## Results And Recovery

```text
Worker/Adapter Result evidence -> Server validates and selects its owner
  TASK -> Kernel Result Policy -> TaskItem and Worker semantic events
  KERNEL -> Serviceability Result Policy in every preset -> Worker semantic events
  SERVER -> Server Direct Call waiter
  SYSTEM -> platform event destination; admitted Properties observations enter Matching
```

SUCCESS stores the Result projection before separately requesting Item success
finality and exact Worker release. Later outcome observations instead advance
the existing Item Score before conditionally storing content, without Worker
lease changes or Task reopening. Score and Result each retain their own maximum
target; higher tag wins, with time advancing within the tag. The Result Owner
retains millisecond precision while Score uses its existing time slots.
Retryable FAILURE releases the correlated
Worker lease without writing an Item Result or deciding finality. Task dispatch
stores a failed marker before promoting an exhausted/expired Item. These are
independent Owner operations: Result observation does not prove finality and
there is no unconditional Result-to-Score replay or repair guarantee.

The [Result Policy](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
owns parsing, grouping and semantic publication;
[Result storage](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
owns the projection format and interruption windows. The
[HOT Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns the opaque fence across candidate generation, assignment and release.

Optional [Serviceability](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
combines bounded demanded-Group probes and Adapter evidence. Dispatch advances
its exact observation before offering a probe; Result interpretation invokes
the dedicated time-fenced Score operation. Neither Server nor Transport moves
Score, and Task result evidence must not infer network polarity.

## Scale And Liveness

The production model is deliberately vertical:

```text
small bounded active Task set
  -> many TaskItems per Task
  -> many Workers inside finite WorkerGroups
```

Fully occupied compatible Workers that keep completing work are normal
backpressure. Bounded scans, exact CAS and bounded index take can add short
convergence delay. Persistently due work and persistently idle compatible
Workers failing to form assignments across repeated eligible rounds is a
liveness defect. A full Task page alone does not establish starvation.

Within the Main-selected batch, Dispatch gives unserved Tasks a turn before
previously served peers, then uses least-recent-service order. Successful
Command publication advances this bounded process-local hint; empty rounds do
not. It prevents fixed-order monopolization of returning compatible capacity
without reserving Workers or promising equal shares and completion deadlines.

Do not add global Group discovery or durable fairness coordination. Massive active Task/Group
cardinality, multi-tenant isolation, sharding and fairness require a separate
architecture. Adding threads does not partition an Owner's hot Redis key.

## Boundaries And Proof

[Worker Delivery](worker-delivery-dispatch.md) starts with already-targeted
Commands. Events and hints may accelerate work but must not become correctness
prerequisites. Lease expiry restores resource eligibility after evidence loss;
it does not recreate a lost Result or repair separate finality writes.

Use [TESTING](../../TESTING.md) to select proof by claim. Focused policy tests
establish decisions, Redis Owner tests establish atomic fences, and Runtime
Boundary/system lanes establish their own finite cross-process relationships.
A passing layer does not substitute for another layer's evidence.

## Production And Proof Pointers

Use these entrypoints to trace one handoff at a time. The listed tests expose
representative assertions, not an exhaustive proof or a record of a successful
run. [Proof Registry](../testing/proof-registry.md) owns claims and nonclaims;
[TESTING](../../TESTING.md) owns commands and CI selection.

| Handoff | Production entry | Representative assertion |
| --- | --- | --- |
| Task and Item admission | [TaskCreationService](../../server_jvm/src/main/java/com/xa/mass/server/task/TaskCreationService.java), [TaskDataService](../../server_jvm/src/main/java/com/xa/mass/server/task/TaskDataService.java) | [Redis Task Owner](../../server_jvm/src/test/java/com/xa/mass/server/assembly/kernel/RedisTaskOwnerRuntimeIntegrationTest.java): complete descriptors, passive Item queries and exact approval/initialization |
| Fixed lifecycle and Main input | [KernelPacerRuntime](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/KernelPacerRuntime.java), [DispatchMainScheduler](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/DispatchMainScheduler.java) | [Runtime tests](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/KernelPacerRuntimeTest.java): fixed assembly, startup and shutdown; [module boundary](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/KernelPacerModuleBoundaryTest.java): supported runtime surface |
| Candidate generation before Pool qualification | [WorkerEligibilityRefillPolicy](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/WorkerEligibilityRefillPolicy.java), [Matching Refill coordinator](../../worker_matching_jvm/src/main/java/com/xa/mass/workermatching/PoolRefillCoordinator.java) | [Refill policy tests](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/dispatch/WorkerEligibilityRefillPolicyTest.java): only candidateized fences reach Matching; [Matching composition](../../worker_matching_jvm/src/test/java/com/xa/mass/workermatching/MatchingCompositionTest.java): shared resources and startup cleanup |
| Candidate admission, Item claim, Command | [TaskAssignmentDispatcher](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/TaskAssignmentDispatcher.java) | [Assignment tests](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/dispatch/TaskAssignmentDispatcherTest.java): strict/current partitioning, returned fences and partial-call failure; [Dispatch progress](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/dispatch/TaskDispatchProgressTest.java): returning capacity across Tasks |
| Result event to separate Owner operations | [TaskResultBatchPolicy](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/result/TaskResultBatchPolicy.java), [TaskItem events](../../kernel_jvm/src/main/java/com/xa/mass/kernel/task/DefaultTaskItemResultEvents.java) | [Event tests](../../kernel_jvm/src/test/java/com/xa/mass/kernel/task/DefaultTaskItemResultEventsTest.java): operation order and partial failure; [Redis Task Owner](../../server_jvm/src/test/java/com/xa/mass/server/assembly/kernel/RedisTaskOwnerRuntimeIntegrationTest.java): monotonic outcomes and independent content targets |
| Worker serviceability and exact leases | [Serviceability policy](../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/WorkerServiceabilityDispatchPolicy.java) | [Policy tests](../../kernel_pacer_jvm/src/test/java/com/xa/mass/kernel/pacer/dispatch/WorkerServiceabilityDispatchPolicyTest.java): recheck before Probe; [Redis Worker Owner](../../server_jvm/src/test/java/com/xa/mass/kernel/score/redis/RedisWorkerOwnerRuntimeIntegrationTest.java): real Redis fences and admission |
| Cross-owner execution | [Server delivery use case](../../server_jvm/src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) | [Runtime Boundary](../../server_jvm/src/test/java/com/xa/mass/server/integration/RuntimeBoundaryIntegrationTest.java): real execution including separate supply/consumption and direct queries; [Scenario Coexistence](../../integrations/scenario-coexistence/README.md): later business observations over shared Workers |
