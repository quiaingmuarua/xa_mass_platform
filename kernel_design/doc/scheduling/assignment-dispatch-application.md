# Assignment-Dispatch Application

Status: current Python executable-spec application lifecycle.

## Purpose

Assignment-dispatch pacers implement one bounded scheduling round. They do not
own timers or process lifecycle. `AssignmentDispatchApplication` is the
assembly-level driver that starts and stops those round operations:

```text
worker-allocation loop
  -> TaskWorkerAllocationPacer.allocate_candidate_workers(config)

running-activation loop
  -> TaskRunningActivationPacer.activate_running_visible_tasks(config)

task-item-dispatch loop
  -> TaskItemDispatchPacer.dispatch_task_items(config)
```

The application is not another scheduling plane and owns no score, descriptor,
candidate collection, Item record, or DeliverSeed queue.

## Loop Contract

The current executable assembly uses one thread per pacer entrypoint. Each loop:

```text
executes the first round immediately after start
runs at its own configured interval
runs only one round at a time
waits for the interval after the previous round returns
logs one failed round and continues the same loop
uses the normal bounded scan as its liveness mechanism
```

Independent threads preserve independent cadence. Allocation latency does not
block Item dispatch, and an Item-dispatch failure does not stop allocation or
running activation. No event is required to wake a loop.

The fixed-delay choice is deliberate. A slow round does not create overlapping
rounds or a catch-up burst. Limits remain owned by each pacer config; intervals
only decide when the application requests the next bounded round.

## Lifecycle

```text
application.start(config)
  -> reject duplicate start
  -> create three non-daemon threads
  -> run each first round immediately

application.stop(timeoutMillis)
  -> signal all loop waits
  -> allow an in-flight bounded round to return
  -> join every thread within one shared timeout
  -> raise TimeoutError if any round remains blocked
```

`stop` does not cancel Redis calls or claim a blocked round stopped. A clean stop
allows the same application instance to start again.

## Assembly Boundary

The constructor receives the three already-assembled pacers. It does not build
Redis clients, register Task or Worker resources, expose a facade over their
runtime owners, or choose policy configuration. The private Redis composition
root constructs those dependencies; `KernelApplication` starts this lifecycle
without exposing the pacers or their configs to external callers.

This application also does not consume DeliverSeed queues. Outbound delivery
has a different lifecycle, failure policy, and transport dependency and must be
started by its own application owner.

## Guardrails

- Do not move thread ownership into a pacer.
- Do not combine the three rounds into one sequential loop.
- Do not overlap two rounds of the same pacer.
- Do not turn append, result, heartbeat, or transport events into required
  wakeups.
- Do not let an application interval redefine scan limits, leases, matching,
  activation, Item claim, or score transition policy.
- Do not swallow application stop timeout or report blocked threads as stopped.
