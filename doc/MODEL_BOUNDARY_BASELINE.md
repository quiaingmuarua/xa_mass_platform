# Model Boundary Baseline

This file defines the current canonical model boundaries. Its purpose is to stop future work from blending HTTP, SDK, command, and gateway transport contracts into one another.

## 1. Boundary Rules

- Each boundary layer may have its own models.
- Each boundary layer should have one canonical truth.
- Model names must expose the layer they belong to.
- Avoid the same class name meaning different things in different modules.
- Transport metadata, protocol frame metadata, and business payload should not silently share ownership of the same fields.

## 2. Current Canonical Boundaries

### HTTP API boundary

- Canonical response envelope: `com.xa.mass.api.model.ApiResponse<T>`
- Canonical request contract: typed request models at controller boundaries
- shared unknown-field capture base: `com.xa.mass.api.model.AbstractUnknownFieldRequest`
- task API request models live under `com.xa.mass.api.model.task.*`
- worker API request models live under `com.xa.mass.api.model.worker.*`
- `/status/api/tasks/**` and `/status/api/workers/**` should expose this envelope directly
- worker debug/status shell JSON endpoints should prefer this envelope too, even though they are not the main public API boundary

### SDK boundary

- Canonical public task-create requests: `com.xa.mass.sdk.model.MassTaskCreateRequest` for plain creates and `com.xa.mass.sdk.model.MassTaskRequest` for mode/payload-aware creates
- Canonical public capability definition: `com.xa.mass.sdk.event.EventDefinition`
- `EventDefinition.code` is the globally unique capability identity
- `EventDefinition.projectCodes` is scope metadata, not part of the identity key
- Engine DTOs are internal conversion targets, not public SDK surface

### Mock command boundary

- Canonical command router kernel now lives in `xa-mass-core` under `com.xa.mass.command.*`
- Canonical command response envelope: `com.xa.mass.command.model.CommandResponse<T>`
- dev-app mock/tool routes remain under `com.xa.mass.mock.command.*`
- This envelope is only for process-local command execution and the current dev-app mock command channel
- It must not be treated as HTTP API response contract

### Gateway transport boundary

- Canonical queue/transport wrapper: `com.xa.mass.gateway.queue.Envelope`
- `Envelope` owns delivery metadata such as `rawJson`, queue target, and trace metadata

### Gateway protocol boundary

- Canonical protocol frame: `com.xa.mass.gateway.model.massMessage.MassMessage`
- Canonical protocol header companion: `com.xa.mass.gateway.model.massMessage.MessageContext`
- `MassMessage` is the message frame, not an HTTP response and not a task business entity
- `msgType + subMsgType` classifies a wire frame only; it must not be promoted into the business/control capability identity model

### Protocol payload helpers

- `com.xa.mass.gateway.model.massMessage.MessageAckPayload` is only for lightweight transport/protocol acknowledgements
- It must not be treated as a general response model

## 3. Current Known Debt

- some status/demo shell endpoints outside the canonical API boundary still use ad-hoc raw maps
- some non-task API controllers still use `Map<String,Object>` request bodies instead of typed request models
- `Envelope`, `MassMessage`, and `MessageContext` still overlap on routing metadata such as worker and project context
- `MassMessage.payload` remains `JsonElement`, so payload contracts are only partially typed
- some gateway/runtime names still reflect historical tuple routing even though global SDK event codes are now the mainline capability model

## 4. First-Stage Convergence Order

1. Remove same-name/different-meaning collisions such as command `ApiResponse`
2. Remove overly broad names such as `MessageResult` when they only represent ack payloads
3. Converge HTTP APIs on one external envelope
4. Freeze gateway protocol header fields before further payload expansion
5. Replace controller-edge raw maps with typed requests on canonical APIs
