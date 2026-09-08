# Worker Dynamic Matching

Primary proof: continuous PRECOMPUTED work overlaps real Worker Properties
changes, independently observed facts and actual execution on eligible replicas.
This lane has its own claim and evidence; Correctness, Convergence Health and
Loaded Recovery retain their existing claims.

## Process And Evidence Boundaries

Python owns one Runtime Server, one Scenario Host, one Java Harness, private Lab
files and an exact Redis `test_*` scope. Java imports only the delivery JSON
contract and uses public Runtime and loopback Lab HTTP. It neither imports
implementations nor injects Reports or writes Redis.

The Scenario-only `extension.worker.lab.execution-witness` Handler captures its
actual Group and replica coordinate at Manager construction. An independent
bounded Host journal records entry/completion and the caller's synthetic token.
Tokens correlate to submitted message IDs; they never supply executor identity.
Task Result payload remains opaque. Every observed attempt must have an allowed
actual executor, including duplicate attempts. Every successful submitted token
must also have a completed execution witness. This is not exactly-once execution.

The runner waits for initial facts and identities, audits successful Prepare
traffic and control-file digests, then releases the Harness through private
ready/continue files. These files coordinate the proof only. Harness success is
`pending-runner-audit` until unchanged live Host/Server processes, 900 control
records and zero additional single/batch Prepare requests are independently
confirmed. The access log is unbuffered, unrotated and contains method/path/status
only; rejected Prepare attempts also fail the audit.

## Fixed World And Workload

- Two Groups of 500 Workers, one WebSocket Adapter and one Manager per Group.
- String: 200 stable A, 200 stable B and 100 mutable targets initially A.
- Phone: 500 cross-Group controls. Every Worker starts with an empty-string sentinel.
- Matching uses `worker.proofPool`, `worker.proofTarget=yes` for witnesses, and
  `platform.proofEnabled=yes`. Initial Platform Properties are written through
  their public API only after the first independent Worker facts observation.
- Three background Tasks: String A, String B and unrestricted Phone. Each has
  50,000 Items, 1,000 ms Handler delay, priority 50 and at most 500 candidates.
- Four witness Tasks each have 100 Items, 100 ms delay, priority 10 and at most
  100 candidates. All Tasks use the public finite PRECOMPUTED lifecycle with
  maxRetryTimes=3. Total: seven Tasks and 150,400 submitted Items.

Each Group has five 100-record files. Each String file contains 40 stable A,
40 stable B and 20 mutable targets, retaining same-file isolation controls
throughout the 1,000-Worker world. The Host journal is bounded at 655,360 records,
covering 300,800 normal entry/completion records with duplicate-attempt headroom.

All Tasks are fully seeded before their approval. The three background Tasks
are approved consecutively and must each show execution and successful Results
before dynamic operations begin. Before every subsequent mutation, an explicitly
observed nonterminal String background Task must retain unfinished work and
have an execution entry observed within ten seconds. A missing Task preview is
not nonterminal evidence. Preconditions fail rather than causing more load or
automatic retries of mutations.

## Scenario And Oracles

| Phase | Mutation and independent oracle |
| --- | --- |
| A baseline | Submit target-only B witness; three seconds with no execution or Result while background work runs. |
| A to B | Four Lab writers issue eight PATCH rounds over 100 targets, sequential per Worker and at least 500 ms between rounds, without remote observation waits. B witness subsequently succeeds on actual targets. |
| B stable | Submit target-only A witness; require no execution/Result for three seconds and retain it for final recovery. |
| Pool removed | Full PUT omits `proofPool` and prior delta fields. A target-only `$exists:false` witness succeeds. |
| Platform disabled | Restore B, change only target Platform Properties to `proofEnabled=no`, submit another B witness and establish a three-second negative window. |
| Platform enabled | Patch only Platform Properties to `yes`; the waiting B witness succeeds. Worker Properties remain unchanged. |
| Return A | Replace target Properties with their A baselines. The waiting A witness succeeds; drain all seven Tasks. |

Each PATCH changes correlated sequence/mirror values and adds a unique delta
key. Adapter and Runtime readers run independently during mutations. Every read
must equal a complete submitted snapshot whose local mutation succeeds; failed
or ambiguous mutation responses fail the entire proof. A burst must overlap
valid reads at both surfaces and new background successful Results. Checkpoints
require all final deltas and exact replacements, unchanged control Properties
and Platform maps, exact live Lab identity/run state and all 1,000 connections.

Adapter, Network and Scheduling observations page caller-known identities in
batches of at most 100. Runtime Preview requests 500 per Group and requires the
exact 500 independently established Lab identities in each response. This fixed
world fits the bounded preview; the API is never used to enumerate an unknown
fleet. A dedicated reader checks both Groups every 200 ms. Bootstrap requires
facts for all 1,000 Workers. Every property checkpoint requires the latest Map
for every changed identity within the same five-second budget. Lab file checks use four
bounded concurrent readers and still verify every Worker record. Result polling
starts only after a Task is approved.

Actual executors of witness Items must be among the 100 targets. Stable A/B
Workers cannot execute the opposite background rule, and no execution crosses
Groups. Previously confirmed background executions on mutable Workers may
continue through a change. Runtime Properties do not define an atomic scheduling
cutover. Dirty/confirmation ordering remains a Redis Owner proof.

Property checkpoints allow five seconds from the last mutation send to both
observations. Each HTTP request is bounded by two seconds and Adapter Direct
Call waits one second. Only temporary observation transport failures,
429/502/503/504 and Direct Call timeouts permit polling. Eligible witnesses have
60 seconds, dynamic operations 180 seconds, and workload approval through drain
600 seconds. These are fixed CI budgets, not production SLAs. Final acceptance
checks both Task terminal projections and the exact submitted successful Result
sets, independently of the execution journal.

The lane does not claim network-fault recovery, reliable SYSTEM replay, atomic
facts/Score writes, Task fairness, every Worker executing, throughput or soak.

## Run And Safe Evidence

Use Java 21, Python 3.11+ and Redis 7. Install `.github/scripts/requirements.txt`.
Ports 18082/18083/18086 must be free; the runner never attaches to existing
processes. An output directory must be fresh and below repository `build`.

```powershell
python integrations/worker-dynamic-matching/run_worker_dynamic_matching.py --redis-url redis://127.0.0.1:6379/15
```

CI selects `worker_dynamic_matching` through the production owner paths and
requires it in Proof Gate. Upload only `evidence/worker-dynamic-matching.json`,
`runtime-server.log` and `scenario-host.log`. Evidence contains safe identities,
phase counts, digests, observation timings, execution checks and runner audits.
Inventory, full Properties, access records, raw execution records, Harness logs
and private correlation mappings are excluded from uploaded artifacts.

Auxiliary checks: `:integrations:worker-dynamic-matching:test`, Python
`unittest discover -s integrations/worker-dynamic-matching -p 'test_*.py'`,
Java Manager tests and Scenario Host tests. These do not replace the real runner.
