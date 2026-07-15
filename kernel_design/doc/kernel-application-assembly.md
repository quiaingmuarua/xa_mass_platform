# Kernel Application Assembly

Status: current Python executable-spec external application boundary.

## Purpose

`KernelApplication` is the only stable external entry to the executable kernel:

```text
CLI / FastAPI example
  -> KernelApplication
     -> Task lifecycle commands and owner operations
     -> private Redis composition root
     -> assignment-dispatch background application
```

The application constructs each owner once for one Redis prefix. It exposes
commands, not runtime objects. Callers cannot obtain Task/Worker score cores,
candidate runtime, matcher, pacers, Redis keys, suffixes, or lane ranks.

## Public Commands

```text
register_worker_group
register_worker
update_worker_dynamic_attributes
create_task
approve_task
append_task_items
consume_deliver_seeds
```

Inputs and results reuse existing caller-owned descriptors and runtime result
types. Assembly does not create mirrored request DTOs. The FastAPI example owns
its HTTP request models because they are protocol-edge translations.

`create_task` selects the initial PRE_REVIEW owner code internally. Worker
registration selects the default lane rank internally. `approve_task` is an
explicit lifecycle command that validates Task metadata and current score band,
then requests `PRE_REVIEW -> PRE_DISPATCH_VISIBLE`. It returns
`TaskApprovalResult` without exposing score evidence.

## Zero Configuration

Both forms use the same immutable internal defaults:

```python
application = KernelApplication()
application = KernelApplication.from_json("{}")
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
  -> start allocation, activation, and Item-dispatch loops

KernelApplication.stop()
  -> no-op before start or after clean stop
  -> signal and join internal loops within stopTimeoutMillis
  -> keep the application started if stop times out
```

Business commands require a successful start. Construction establishes the
composition graph but performs no Redis I/O. The private process root does not
close the Redis client on stop, so a clean application instance may restart.

## External Hosts

The built-in CLI starts the application with defaults or one JSON file:

```text
python -m kernel_design.executable_spec.assembly
python -m kernel_design.executable_spec.assembly --config kernel.json
```

The FastAPI example under `kernel_design/examples/` uses lifespan to start and
stop the same application. It imports only the assembly package and never
reconstructs lifecycle or score transitions.

The example is not a production server. Authentication, Task query/list,
result-routing, transport submit, and production API compatibility remain out
of scope.

## Guardrails

- Do not re-export the private Redis composition root.
- Do not expose owner runtime instances as application properties.
- Do not add a second environment-variable or CLI configuration path.
- Do not let HTTP handlers perform score reads or transitions.
- Do not turn internal Pacer configuration into public JSON without a concrete
  operational requirement.
- Do not add transport or result loops to assignment-dispatch lifecycle.
