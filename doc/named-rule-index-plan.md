# Named Rule Index Cutover

Status: implemented; named Rule acceptance and local proof runs passed.

Baseline: working tree over 9680e1646, including the completed Task/Rule reference decoupling slice.

## Delivered mechanism

- Task creation accepts a fixed named ruleId, the existing allocationRule DSL, or neither; inputs are exclusive.
- worker.country is the first named Rule. Its country Handler owns facts/index materialization, bounded eq/in/ANY/ID queries and membership recheck.
- INDEXED_TASK resolves a Matching-owned Task binding, queries before HOT/hold, rechecks after hold and enters the existing exact assignment closure.
- The new path does not use Match Demand or Candidate Cache. The original DSL path remains explicit and retains its original proof workload.
- Binding HASH values contain workerGroupId and ruleId. Named Rules have no persisted definition; DSL content definitions remain shared.
- Only DSL Tasks carry maximumCandidateWorkers. Allocation mechanism and finite lifecycle are independent.
- Country selectors use explicit op/values objects; ANY and Worker ID wire shapes remain unchanged.

## Owner references

- [Matching](../worker_matching_jvm/README.md#named-rule-queries)
- [Dispatch](../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md#named-rule-index-flow)
- [Task resource](../kernel_jvm/doc/resource-model/task-resource-model.md)
- [Server](../server_jvm/README.md)

## Proof record

- Kernel/Pacer/Matching/Server unit tests passed: 50 / 110 / 15 / 320; product caller unit tests passed: SMS 10 and Messages 5.
- Redis Owner passed all 120 tests, including the named Rule shared index, malformed bindings, eq/in rotation and membership changes.
- The 100-Item Matching budget was measured as HMGET + EVAL + EVAL; invalid over-budget input sends no command.
- Runtime Boundary passed all 16 tests. Actual WebSocket/Socket Workers execute two finite indexed Tasks, including country changes, repeatable Result reads/export, a retained Reporter observation after Task closure and no Task-specific Queue/Cache calls.
- Worker Dynamic Matching passed with 1,000 Workers and 150,400 Items, Prepare delta zero.
- Worker Correctness passed initial, live-properties and Host restart phases. Worker Convergence Health passed state/server recovery and in-flight Task fault recovery. Every runner cleaned its own unique test scope.
- Frontend lint, typecheck and 141 tests passed; schemas and debug query input follow the new API without adding a Rule management page.
- Docs Contract, Kernel contract manifest and proof-selection checks passed, including 22 checker tests.
- Distribution unit tests (2), product composition (6), Runtime archive launch and Worker SDK archive consumer proofs passed with xaMassVersion=0.5.0.
- Product Coexistence passed the 12-Worker functional and lifecycle scenarios, then functional proof from a freshly extracted Preview ZIP. Archive verification and 23 product proof/launcher tests also passed.
- Android Emulator is selected for CI; the fixed Linux/KVM API 33 lane was not executed in this Windows checkout. These local results do not claim a completed CI Proof Gate.

## Local proof artifacts

The 2026-09-12 runs retain safe summaries under these ignored build directories:

- `build/named-rule-correctness-20260912/evidence`
- `build/named-rule-dynamic-20260912/evidence`
- `build/named-rule-convergence-20260912/{state,task-fault}/evidence`
- `build/named-rule-product-functional-20260912/summary.json`
- `build/named-rule-product-lifecycle-20260912/summary.json`
- `build/named-rule-product-zip-20260912/summary.json`
- `build/named-rule-preview-archive-20260912.json`

## Limits and cutover

The original DSL and its asynchronous lifecycle are not retired by this slice.
The measured command budget is not a throughput or latency claim. No cross-owner
transaction, fallback, replay, retry queue or background Rule lifecycle was added.

Stored Task configs and binding values require an explicitly scoped stop and
rebuild; no old-layout compatibility reads are installed. This implementation
only operates on isolated test scopes and does not clean non-test data.
