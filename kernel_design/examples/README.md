# Kernel HTTP Examples

Status: external protocol example for the isolated executable spec.

Install the example-only dependencies:

```text
python -m pip install -r kernel_design/examples/requirements.txt
```

Start the Kernel command process:

```text
python -m kernel_design.examples.kernel_command_server
```

Start the independent Worker polling process:

```text
python -m kernel_design.examples.worker_adapter_server
```

Their default addresses are:

```text
Kernel Command Server   127.0.0.1:18080
Worker Adapter Server   127.0.0.1:18081
```

Both accept the same optional `--config kernel.json`; `--host`, `--port`, and
`--log-level` configure only the selected HTTP process.

The Kernel host composes `KernelApplication` and `ResourcesCommandClient`.
Its lifespan starts only the scheduling application. It exposes resource
upsert and Task commands, not DeliverSeed consumption or SeedResult append.

The [Worker Adapter Server](worker-adapter-server.md) composes only
`DeliverSeedConsumerClient` and `SeedResultCommandClient`. It has no scheduling
lifecycle:

```text
POST /workers/{workerId}/commands:poll
POST /workers/{workerId}/results
```

The Worker-facing command/result envelopes belong to the Adapter protocol.
DeliverSeed and SeedResult remain the only Kernel contracts across this
boundary.

Task lifecycle routes include explicit approve and close commands. Close is
available for both Task types and does not expose a terminal score.

Dynamic attribute mutation is intentionally absent until the assembly installs
a real dynamic-attribute handler owner.

These are not production servers. They intentionally omit authentication,
Worker identity proof, Task query/list, result projection, pending/ack,
production push transport, and API compatibility policy.
