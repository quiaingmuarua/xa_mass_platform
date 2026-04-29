# WebSocket Adapter Current Inventory

Status: historical refactor inventory, not current truth.

Use this file only when auditing older WebSocket refactor context. Current
ownership rules live in:

- [../WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md](../WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md)
- [../TRANSPORT_BOUNDARY_BASELINE.md](../TRANSPORT_BOUNDARY_BASELINE.md)
- [../AGENTS.md](../AGENTS.md)

Short summary of what remained adapter-owned during the refactor:

- server lifecycle
- session reachability
- frame codec and detection
- adapter input/output processors
- embedded runtime support defaults

Short summary of what was explicitly not adapter-owned:

- task lifecycle
- worker matching
- retry / terminal policy
- capability truth
- business execution

Do not extend this file with new architecture truth. Update the baseline docs
above instead.
