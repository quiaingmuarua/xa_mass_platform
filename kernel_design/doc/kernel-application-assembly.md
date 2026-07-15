# Kernel Application Assembly

Status: current Python executable-spec external application boundary.

## Purpose

The executable spec exposes four narrow process boundaries:

```text
FastAPI / SDK
  -> ResourcesCommandClient
     -> WorkerGroup and Worker registration

CLI / FastAPI
  -> KernelApplication
     -> Task lifecycle commands
     -> private Redis composition root
     -> assignment-dispatch and result-routing background applications

endpoint-manager process
  -> DeliverSeedConsumerClient
     -> consume assigned DeliverSeeds without KernelApplication lifecycle

endpoint-manager process
  -> SeedResultCommandClient
     -> append SeedResults to one unified runtime queue
```

Both boundaries expose commands, not runtime objects. Callers cannot obtain
Task/Worker score cores, candidate runtime, matcher, pacers, Redis keys,
suffixes, or lane ranks.

## Public Commands

```text
ResourcesCommandClient
register_worker_group
register_worker

KernelApplication
create_task
approve_task
append_task_items

DeliverSeedConsumerClient
consume_deliver_seeds

SeedResultCommandClient
append_seed_results
```

Inputs and results reuse existing caller-owned descriptors and runtime result
types. Assembly does not create mirrored request DTOs. The FastAPI example owns
its HTTP request models because they are protocol-edge translations.

Worker registration selects the default lane rank internally and initializes
the Worker HOT score without requiring the scheduling process to be running.
`create_task` selects the initial PRE_REVIEW owner code internally.
`approve_task` is an explicit lifecycle command that validates Task metadata
and current score band, then requests `PRE_REVIEW -> PRE_DISPATCH_VISIBLE`. It
returns `TaskApprovalResult` without exposing score evidence.

Dynamic attribute mutation is not a public assembly command. The executable
spec has no installed external handler registry, so exposing that command would
advertise a route that cannot perform a real owner update.

## Zero Configuration

Both forms use the same immutable internal defaults:

```python
application = KernelApplication()
application = KernelApplication.from_json("{}")
resources = ResourcesCommandClient()
resources = ResourcesCommandClient.from_json("{}")
deliver_seeds = DeliverSeedConsumerClient()
seed_results = SeedResultCommandClient()
```

The optional JSON contract is:

```json
{
  "redis": {
    "url": "redis://localhost:6379/15",
    "prefix": "default"
  },
  "assignmentDispatch": {
    "workerAllocationIntervalMillis": 100,
    "runningActivationIntervalMillis": 100,
    "taskItemDispatchIntervalMillis": 100
  },
  "resultRouting": {
    "intervalMillis": 100
  },
  "stopTimeoutMillis": 5000
}
```

Every field may be omitted. Unknown fields, malformed JSON, empty strings,
wrong types, and non-positive numeric values fail during construction. Batch,
scan, lease, claim, suffix, score, and lane policy remain internal constants;
they are not configuration merely because the first composition root needs
them.

## Lifecycle

```text
KernelApplication.start()
  -> reject duplicate start
  -> Redis PING fail-fast
  -> start result-routing loop
  -> start allocation, activation, and Item-dispatch loops

KernelApplication.stop()
  -> no-op before start or after clean stop
  -> stop assignment-dispatch loops
  -> stop result-routing loop
  -> keep the application started if stop times out
```

Task commands require a successful application start. DeliverSeed consumption
and SeedResult append use independent clients with no `start` or `stop`, so an
endpoint-manager process does not own scheduler lifecycle. Construction
establishes the composition graph but performs no Redis I/O. The private process
root does not close the Redis client on stop, so a clean application instance
may restart.

`ResourcesCommandClient`, `DeliverSeedConsumerClient`, and
`SeedResultCommandClient` have no `start` or `stop`. Each may use a separate
redis-py pool while sharing the same URL, prefix, and Redis owner truth.

## External Hosts

The built-in CLI starts the application with defaults or one JSON file:

```text
python -m kernel_design.executable_spec.assembly
python -m kernel_design.executable_spec.assembly --config kernel.json
```

The FastAPI example constructs `KernelApplication`, `ResourcesCommandClient`,
and `DeliverSeedConsumerClient` from one resolved configuration. Lifespan starts
and stops only `KernelApplication`; resource and DeliverSeed routes use their
independent clients. The Local Function Adapter example additionally uses
`SeedResultCommandClient`.

The examples are not production services. Authentication, Task query/list,
result projection, production transport, and API compatibility remain out of
scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add scheduler lifecycle methods to `ResourcesCommandClient`.
- Do not restore dynamic attribute mutation until a real handler owner and
  assembly contract exist.
- Do not add a second environment-variable or CLI configuration path.
- Do not let HTTP handlers perform score reads or transitions.
- Do not turn internal Pacer configuration into public JSON without a concrete
  operational requirement.
- Keep result-routing and assignment-dispatch as separate internal application
  lifecycles even though `KernelApplication` composes both.
