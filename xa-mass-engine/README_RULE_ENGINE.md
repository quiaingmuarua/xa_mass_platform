# Rule Engine Notes

This document describes the active rule-matching surface in `xa-mass-engine`.

## Purpose

The engine no longer hard-codes task-to-device matching around a single device-country assumption.
The current mainline evaluates `DeviceMatchContext` through QLExpress rules and treats routing country as a task input that should normally be satisfied by token/account-facing signals.

## Active Components

### `DeviceMatchContext`

Location: `com.xa.mass.engine.model.DeviceMatchContext`

Responsibilities:

- Build a stable rule-evaluation context for one `device + token + task` candidate
- Expose strong-typed mainline fields and auxiliary attribute maps together
- Keep routing-country diagnostics explicit instead of hiding them in ad hoc device filters

### `RuleConfig`

Location: `com.xa.mass.engine.rules.RuleConfig`

Responsibilities:

- Provide default, advanced, project-specific, and loose matching rules
- Keep the default routing-country rule aligned with current semantics

## Default Rules

Current default rule set:

1. `basic_device_check`

```ql
isDeviceAvailable == true && isDeviceLocked == false
```

2. `token_status_check`

```ql
isTokenAllocatable == true && isTokenAvailable == true
```

3. `routing_country_match`

```ql
tokenAttributeCountryMatchesRoutingCountry == true || tokenChannelMatchesRoutingCountry == true
```

4. `app_support_check`

```ql
supportsProject == true
```

5. `device_load_check`

```ql
appCount < 10
```

## Context Keys

### Device

- `deviceId`
- `deviceStatus`
- `deviceGroupId`
- `deviceAttributes`
- `agentVersion`
- `supportedProjects`
- `isDeviceAvailable`
- `isDeviceLocked`

### Token

- `tokenId`
- `tokenStatus`
- `tokenChannel`
- `tokenAttributes`
- `isTokenAllocatable`
- `isTokenAvailable`

### Task

- `taskId`
- `taskName`
- `taskProject`
- `taskRoutingCountryCode`
- `taskStatus`
- `taskTargetNumber`
- `batchSize`
- `runTaskMinDeviceCnt`

### Derived Signals

- `appCount`
- `supportsProject`
- `deviceGroupIdEqualsRoutingCountry`
- `tokenChannelMatchesRoutingCountry`
- `tokenAttributeCountryMatchesRoutingCountry`

## Example Rules

Token attribute routing:

```ql
tokenAttributes['country'] == taskRoutingCountryCode
```

Fallback to token channel:

```ql
tokenChannelMatchesRoutingCountry == true
```

Project-specific example:

```ql
supportsProject == true &&
appCount <= 5 &&
agentVersion.startsWith('1.0') &&
(tokenAttributeCountryMatchesRoutingCountry == true || tokenChannelMatchesRoutingCountry == true)
```

## Boundaries

- `deviceAttributes` and `tokenAttributes` are auxiliary labels only
- Lifecycle, lock, and online truth must continue to come from strong-typed fields and managers
- `taskRoutingCountryCode` is a routing hint, not a claim that the task itself owns country truth
- Device `deviceGroupId` can still appear in diagnostics, but it is no longer the mainline country truth source

## Guidance

- Prefer explicit context keys over flattened aliases
- Prefer end-to-end tests for routing behavior over isolated expression-only confidence
- If matching semantics change, update `RuleConfig`, `DeviceMatchContext`, and the mock E2E routing coverage together
