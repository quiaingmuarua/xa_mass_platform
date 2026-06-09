> Archived on 2026-06-09.
>
> Historical implementation record only. Current scenario task launcher config
> behavior lives in `integrations/xa-mass-scenario-launcher/README.md`. Worker
> launcher config remains deferred and should use a new roadmap if pursued.
> Do not use this roadmap as proof of current behavior.

# Scenario Launcher Human Config Roadmap

Status: implemented mainline; worker config remains deferred by Non-Goals.

## Summary

`integrations/xa-mass-scenario-launcher` is currently useful as an external SDK
proof tool, but it is still shaped mostly for agent-driven shell commands and
checked-in JSON fixtures. Human development first needs a config-file driven
task launcher that can keep common startup short while still allowing richer
task setup when the scenario grows.

First target:

```text
java -jar xa-mass-scenario-task-launcher.jar --config scenario.local.json
```

Worker launcher config is intentionally deferred. It continues to use the
existing `--scenario-dir` / `workers.json` fixture path until a later roadmap or
slice defines `workers[]` human config.

The task config file is launcher ergonomics only. It must not become a new
platform catalog, scheduling DSL, worker selector, credential bootstrap bypass,
or server startup substitute.

## Current Code Observations

- `ScenarioLauncherOptions` reads only CLI flags and environment variables:
  `baseUrl`, `webSocketUrl`, `taskApiKey`, `workerApiKey`, `scenarioDir`, and
  `maxPollingWorkers`.
- `ScenarioFiles.load(...)` reads `workers.json` and `tasks.json` from one
  `scenarioDir`.
- Both `ScenarioTaskLauncherMain` and `ScenarioWorkerLauncherMain` call the same
  `ScenarioFiles.load(...)` path today, so both entrypoints currently require
  both fixture files even when only one side is being launched.
- `TaskScenarioSpec` currently contains `apiKey`, `itemBatchSize`, and an
  untyped `body` map.
- `TaskScenarioSeeder` creates one task shell from `body`, then appends items
  from `body.items`. It already chunks with `DEFAULT_ITEM_BATCH_SIZE=500`.
- `TaskScenarioSeeder` treats `body.eventCode` as item-append identity only; it
  does not include `eventCode` in `TaskCreateRequest`.
- `ScenarioExpander` supports counted worker specs and task `generatedItems`,
  which is useful for proof fixtures but is not a good human data-input shape
  for real item lists.
- The checked-in `integrations/samples/dev/scenario/tasks.json` mixes task
  config, inline items, and generated fixture items.
- Credentials are currently raw CLI/env values. The default task key is
  `crawler-task-api-key`, but that key must already exist in server storage
  through seed/import or normal host credential setup.
- Credential precedence is currently asymmetric, but this roadmap only changes
  the task side: task specs may override the global task key through
  `TaskScenarioSpec.apiKey`; the existing worker-key behavior remains unchanged
  until worker config is designed.
- `MassPlatform` supports connect and request timeouts, but the scenario
  launcher currently constructs clients with `HttpClient.newHttpClient()` and
  does not expose timeout config.
- Recent launcher diagnostics now explain missing task API-key credentials and
  seed requirements, but they do not change the boundary: the launcher consumes
  credentials; it does not create them.

## Owner Decision

This roadmap belongs to `integrations/xa-mass-scenario-launcher` with companion
doc updates in `integrations/README.md`, `integrations/xa-mass-scenario-launcher/README.md`,
and `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` when caller behavior
changes.

The launcher remains an external Java SDK adopter:

- it may read human-friendly config
- it may map local input rows into task item payloads
- it may invoke public task and worker APIs through `sdk/xa-mass-java-sdk`
- it must not add server/engine dependencies
- it must not seed task, worker, WorkerGroup, AdapterNode, NodeGroupBinding, or
  runtime truth
- it must not introduce new server endpoints or new public-contract DTOs merely
  to support local config

## Boundary Decision

Use a three-layer task-launcher model:

```text
launcher config     = server URL, task credential source, task runtime knobs, scenario files
task scenario config = project/user/contract/execution/sharedConfig/action/item source
item source         = real data files such as JSON array, JSONL, or text
```

Action aliases are caller-side conveniences only:

```text
actions.resolve_phone:
  human name -> eventCode / payload mapping
```

They must not redefine platform truth. In the platform, `eventCode` remains the
handler/capability identity. Worker universe selection remains explicit through
task config such as `sharedConfig.workerGroupId`, `workerGroupIds`, or other
current task shared-config selector fields; `group` / `action` aliases must not
silently scan workers or infer a worker group.

Path resolution rules:

```text
config-internal relative paths = resolved relative to the config file directory
config-internal absolute paths = used as-is
CLI --config relative path     = resolved by the process current working directory
CLI --scenario-dir path        = keeps current process-cwd behavior unless SLC-0 explicitly changes it
```

Examples: `credentials.taskApiKeyFile`, `tasks[].items.path`, and example
data-file paths are config-internal paths and therefore resolve relative to the
config file. This makes human config portable when launched from different
directories.

Entrypoint validation rules:

```text
task launcher config mode   = tasks required, workers rejected if present
worker launcher config mode = deferred; existing worker launcher behavior remains unchanged
```

The old `scenarioDir` fixture loader may continue to read both `tasks.json` and
`workers.json` for the legacy `--scenario-dir` path during the transition. New
task config-file mode reads `tasks[]` directly from the config file and must not
route through `scenarioDir`. If a config file contains top-level `workers`, fail
fast with a clear message that worker config is deferred and the worker launcher
still uses `--scenario-dir` / `workers.json`.

Credential precedence must be explicit and tested. First implementation should
preserve current behavior unless SLC-0 records a different decision:

```text
task credential   = task spec apiKey > CLI --task-api-key > MASS_TASK_API_KEY > config credentials.taskApiKey/File > default
worker credential = deferred; existing worker launcher precedence remains unchanged
```

The current task/worker asymmetry is acknowledged only to avoid accidentally
changing worker behavior while implementing task config. A later worker config
slice must make its own explicit credential precedence decision.

`eventCode` resolution is append-scoped. A task-level `eventCode` or resolved
`actions.*.eventCode` is used to build `TaskItemBatch.eventCode`; it must not be
treated as a `TaskCreateRequest` field or a task-shell truth.

## Target Config Shape

The config should support a minimal file:

```json
{
  "credentials": {
    "taskApiKeyFile": "./secrets/task-api-key.txt"
  },
  "tasks": [
    {
      "project": "telegram",
      "userId": "telegram-loader",
      "eventCode": "telegram.resolvePhone",
      "items": {
        "type": "txt",
        "path": "./data/phones.txt",
        "field": "phone"
      }
    }
  ]
}
```

And a richer file:

```json
{
  "server": {
    "baseUrl": "http://127.0.0.1:8088",
    "connectTimeoutSeconds": 5,
    "requestTimeoutSeconds": 30
  },
  "credentials": {
    "taskApiKeyFile": "./secrets/task-api-key.txt"
  },
  "runtime": {
    "taskItemBatchSize": 100
  },
  "actions": {
    "resolve_phone": {
      "eventCode": "telegram.resolvePhone",
      "paramMap": {
        "phone_number": "phone",
        "client_id": "bind_client"
      }
    },
    "import_contact": {
      "eventCode": "telegram.importContactsAndDelete",
      "paramMap": {
        "phones": "phones",
        "client_id": "bind_client"
      },
      "jsonFields": ["phones"]
    }
  },
  "tasks": [
    {
      "project": "telegram",
      "userId": "telegram-loader",
      "contract": "BATCH",
      "action": "resolve_phone",
      "sharedConfig": {
        "workerGroupId": "telegram-workers"
      },
      "executionSpec": {
        "batchSize": 100,
        "workloadClass": "BULK"
      },
      "items": {
        "type": "jsonl",
        "path": "./data/phones.jsonl"
      }
    }
  ]
}
```

Config precedence:

```text
CLI override > environment variable > config file > existing built-in default
```

This global precedence keeps agent shell proofs easy while making human startup
config-file first. Per-spec credential precedence is narrower and must follow
the credential-specific rule above.

## Non-Goals

1. No server API changes.
2. No engine/runtime scheduling changes.
3. No new public-contract DTOs.
4. No credential creation from launcher config.
5. No task lifecycle command support in the task launcher.
6. No worker registration seed/import replacement.
7. No platform catalog replacement through `actions`.
8. No migration of `integrations/samples/dev/scenario` path naming in this
   roadmap.
9. No full generic ETL engine. Item readers are bounded launcher inputs.
10. No worker launcher config mode, `workers[]` schema, or worker credential
    file support in this roadmap. Worker launcher keeps the current
    `--scenario-dir` / `workers.json` path.

## Do Not Start With

Do not start by making `tasks.json` larger and calling it a config file.

That would keep the current problem: task config, credential source, runtime
knobs, and item data remain coupled to one fixture shape. Start by introducing a
launcher config owner and precedence rules, then move real item sources out of
inline task JSON.

## SLC-0: Inventory And Schema Decision

Goal: classify current launcher inputs before changing behavior.

Scope:

1. Inventory current CLI/env fields in `ScenarioLauncherOptions`.
2. Inventory current `tasks.json` shape and record that `workers.json` config
   is deferred.
3. Classify current task `body` keys into:
   - task shell fields
   - `sharedConfig`
   - `executionSpec`
   - item append `eventCode`
   - inline item data
   - proof-only generated items
4. Decide first-version config schema and names:
   - `server.baseUrl`
   - `server.connectTimeoutSeconds`
   - `server.requestTimeoutSeconds`
   - `credentials.taskApiKeyFile`
   - `runtime.taskItemBatchSize`
   - `actions.*.eventCode`
   - `actions.*.paramMap`
   - `actions.*.jsonFields`
   - `tasks[].items`
5. Decide path resolution rules and add them to the roadmap if they change:
   - config-internal relative paths resolve from the config file directory
   - CLI paths keep process-cwd behavior unless explicitly changed
6. Decide task launcher config validation:
   - task launcher requires tasks only
   - top-level `workers` is rejected with a clear "worker config deferred"
     message
   - worker launcher config mode is deferred
7. Decide credential precedence, including current per-spec task and worker
   key behavior, without changing worker behavior.
8. Decide that resolved task `eventCode` remains append-scoped and does not
   enter `TaskCreateRequest`.
9. Decide that `scenarioDir` is not part of the new config-file schema. Existing
   `--scenario-dir` remains a legacy CLI fixture path for checked-in samples.
10. Decide that first-version runtime knobs are limited to
    `runtime.taskItemBatchSize`; any append delay/jitter knob requires a later
    named decision and tests.

Acceptance:

- A written schema decision exists in this roadmap or a sibling inventory.
- Every current task launcher input is mapped to config, kept as CLI/env, or
  marked proof-only/deferred. Worker-only inputs are explicitly deferred.
- The decision explicitly says `actions` are caller-side aliases and not
  platform truth.
- Relative path behavior, task-entrypoint config validation, credential
  precedence, and timeout field mapping are documented before SLC-1 begins.

Verification:

```powershell
rg -n "record ScenarioLauncherOptions|record TaskScenarioSpec|record WorkerScenarioSpec|generatedItems|body" integrations/xa-mass-scenario-launcher/src
```

## SLC-1: Config File Loading And Precedence

Goal: add `--config <path>` to the task launcher without changing existing
worker launcher behavior.

Scope:

1. Add a typed launcher config model under
   `integrations/xa-mass-scenario-launcher`.
2. Parse JSON config files with Jackson.
3. Add `--config <path>` to the task launcher through
   `ScenarioLauncherOptions`.
4. Implement precedence:
   `CLI override > environment variable > config file > existing default`.
5. Keep existing CLI/env behavior working for agent proofs.
6. Add validation errors with field names, not generic Jackson exceptions.

Acceptance:

- `--config scenario.local.json` can supply base URL, timeout fields, and task
  credential source fields. It must not supply `scenarioDir`; `scenarioDir`
  remains a legacy CLI fixture path.
- Config-internal relative paths are resolved relative to the config file
  directory; absolute paths are used as-is.
- CLI `--config` relative paths keep process-cwd behavior.
- Config mode supports task-only files. Top-level `workers` is rejected with a
  clear worker-config-deferred message; existing worker launcher behavior remains
  unchanged.
- Existing `ScenarioLauncherOptionsTest` coverage continues to pass.
- New tests prove config value loading, CLI override behavior, relative path
  resolution, timeout mapping, and task-entrypoint validation.
- `connectTimeoutSeconds` and `requestTimeoutSeconds` must actually affect
  `MassPlatform` / `HttpClient` construction. Do not merely parse the fields or
  pass a custom `HttpClient` that bypasses `MassPlatform.connectTimeout(...)`.
- Config parsing does not log or echo raw credential values.

Verification:

```powershell
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am "-Dtest=ScenarioLauncherOptionsTest,ScenarioLauncherConfigTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

If `ScenarioLauncherConfigTest` does not exist when this slice starts, add it or
replace the command with the concrete config-loader test class introduced in the
slice. Do not rely on options parsing tests alone to prove config-file loading.

## SLC-2: Credential Source Files

Goal: support human-friendly API-key files without making launcher config a
credential bootstrap path.

Scope:

1. Support:
   - `credentials.taskApiKeyFile`
   - optional direct `taskApiKey` only for explicit local use
2. Trim trailing newline and whitespace from key files.
3. Reject missing or blank key files with clear messages.
4. Keep `MASS_TASK_API_KEY` and `--task-api-key` as task credential overrides.
   Do not change `MASS_WORKER_API_KEY` or `--worker-api-key`; those remain
   existing worker-path behavior outside this roadmap.
5. Preserve current per-spec credential behavior unless SLC-0 explicitly
   chooses a different rule:
   - `tasks[].apiKey` may override the global task credential.
   - worker credential precedence is documented as deferred and unchanged.
6. Resolve task credential file paths relative to the config file directory.
7. Add `.example.json` samples but do not commit real local key files.
8. Add or update `.gitignore` only if new local config/key paths are introduced.

Acceptance:

- Human users can keep API keys in local files and run launcher with one
  `--config` argument.
- Task credential precedence is covered by tests. Worker credential behavior is
  not changed by this roadmap.
- Missing credential files report the resolved path without printing secret
  values.
- The launcher still fails with HTTP 401 when the key is not present in server
  storage; diagnostics must explain credential existence rather than imply a
  route mismatch.
- Raw key values are not printed in help, diagnostics, or test names.

Verification:

```powershell
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am "-Dtest=ScenarioLauncherOptionsTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## SLC-3: Real Item Sources

Goal: move task items from inline fixture JSON toward real data file inputs.

Scope:

1. Add a bounded `ItemSource` model for:
   - JSON array file
   - JSONL file
   - text file with one value per nonblank line
2. Support `field` for text inputs, such as `"field": "phone"`.
3. Support `paramMap` mapping from input row fields to submitted item fields.
4. Support `jsonFields` for fields that should be parsed as JSON before submit.
5. Keep current inline `body.items` and `generatedItems` as fixture-compatible
   paths until samples are converted.
6. Add clear validation for unsupported type, missing path, invalid JSON, and
   non-list JSON array.
7. Resolve item source paths relative to the config file directory.

Acceptance:

- A task can create a shell from config and append items read from `.json`,
  `.jsonl`, or `.txt`.
- Items are appended through the existing `TaskClient` / `TaskHandle` path; no
  new server route is introduced.
- `eventCode` used for append is explicit either on task config or resolved
  from an action alias.
- Resolved `eventCode` is used only for `TaskItemBatch.eventCode`; tests must
  prove it does not enter the create-task request body as a task shell field.
- `paramMap` and `jsonFields` are applied before batching.
- Large item files are handled without requiring every item to be embedded in
  `tasks.json`. First implementation may read into memory, but the roadmap must
  leave streaming as a follow-up if memory becomes a real limit.

Verification:

```powershell
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am "-Dtest=TaskScenarioSeederTest,ScenarioFilesTest,ScenarioLauncherOptionsTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

If `ScenarioFilesTest` does not exist when this slice starts, add it or replace
the command with the concrete item-source test class introduced in the slice.

## SLC-4: Action Alias And Task Config Normalization

Goal: make human config concise without hiding platform task semantics.

Scope:

1. Add action alias resolution:
   - `tasks[].action` resolves to `actions[action].eventCode`
   - task-level `eventCode` overrides or conflicts are validated explicitly
2. Support task-level fields outside the old `body` map:
   - `project`
   - `userId`
   - `sourceRef`
   - `contract`
   - `sharedConfig`
   - `executionSpec`
   - `itemBatchSize`
3. Preserve current `body` map support during this roadmap unless a later slice
   removes it with tests and docs.
4. Reject ambiguous config, such as unknown action alias or action alias plus a
   conflicting `eventCode`.
5. Do not derive worker group from `actions.group` or action name.
6. Keep resolved `eventCode` append-scoped; do not add it to
   `TaskCreateRequest` or model it as task-shell truth.

Acceptance:

- Users can define `actions` once and reference them by short name in tasks.
- Task shell creation still maps to `TaskCreateRequest`.
- Item append still maps to `TaskItemBatch.eventCode`.
- Tests prove the create request does not carry `eventCode` while append
  batches do.
- Worker selection remains explicit in `sharedConfig`, not inferred from
  action alias naming.

Verification:

```powershell
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am "-Dtest=TaskScenarioSeederTest,ScenarioLauncherOptionsTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## SLC-5: Samples, Docs, And Human Runbook

Goal: make the human path discoverable and keep agent proof commands available.

Scope:

1. Add checked-in example config files, such as:
   - `integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json`
   - sample item files under an examples or sample data directory
2. Do not commit real credential files.
3. Update `integrations/xa-mass-scenario-launcher/README.md` with:
   - one-command config usage
   - credential file setup
   - config-relative path behavior
   - task-only config examples
   - worker config deferred note
   - server seed/import prerequisite
   - item source examples
4. Update `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` only for external
   caller behavior changes.
5. Update `integrations/README.md` if the launcher role or usage shape changes.

Acceptance:

- Human usage starts from a config file, not a long shell command.
- Human task examples do not require a `workers` section.
- Agent proof commands remain available and documented as overrides.
- README clearly says the launcher consumes credentials and public APIs; it
  does not create credentials or seed runtime truth.

Verification:

```powershell
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am test
rg -n -- "--config|taskApiKeyFile|jsonl|paramMap|jsonFields|worker config" integrations/xa-mass-scenario-launcher sdk/xa-mass-java-sdk integrations/README.md
```

## SLC-6: Residue And Guardrails

Goal: prevent the old proof-fixture shape from becoming the long-term human
interface.

Scope:

1. Scan for docs that still present long shell commands as the primary human
   path.
2. Scan for new server or engine dependencies in
   `integrations/xa-mass-scenario-launcher`.
3. Add or update a dependency/source guard:
   - launcher production code must not import server, engine, runtime, or
     worker-pack implementation packages
   - launcher task path must not call `/commands`
4. If any guard cannot be mechanized in this roadmap, record the guard gap in
   the roadmap or a sibling inventory with the exact manual review rule and the
   reason it cannot be made mechanical yet.
5. Decide whether `generatedItems` remains as a proof fixture feature or moves
   to examples only.
6. Decide whether old `body.items` inline data remains supported or becomes
   deprecated after real item sources are stable.

Acceptance:

- No server/engine dependency was introduced.
- No task lifecycle command route is reintroduced into task launcher.
- Dependency and `/commands` guards are either mechanical tests or explicitly
  recorded guard gaps with manual review rules.
- Human docs prefer `--config`.
- Any remaining inline/generated fixture support is explicitly classified.

Verification:

```powershell
rg -n "com\\.xa\\.mass\\.(server|engine|runtime|workerpack)|/commands|generatedItems|body.items" integrations/xa-mass-scenario-launcher/src/main integrations/xa-mass-scenario-launcher/README.md
.\mvnw.cmd -q -pl integrations/xa-mass-scenario-launcher -am test
```

## Risks

- Too much schema too early can turn the launcher into a DSL. Mitigation:
  implement config loading and credential files first, then item sources, then
  action aliases.
- Action aliases can accidentally look like platform catalog truth. Mitigation:
  require explicit `eventCode` and keep worker selection in task `sharedConfig`.
- Config examples can leak raw keys. Mitigation: use `.example.json` and local
  key-file paths; do not commit real key files.
- Large item files can pressure memory. Mitigation: first implementation may
  read into memory for simplicity, but keep item-source ownership narrow enough
  to move to streaming later.
- Backward compatibility can keep old and new human paths as two equal truths.
  Mitigation: keep CLI/env as overrides for tests, but make docs prefer config
  for human usage.

## Completion Criteria

The roadmap is complete only when:

1. `--config` works for the task launcher.
2. API keys can be read from local files.
3. Task items can be read from JSON array, JSONL, and text files.
4. Action aliases can map real input rows into task item payloads without
   redefining platform truth.
5. Human docs and examples prefer config-file startup.
6. Focused launcher tests and module tests pass.
7. A residue scan confirms no server/engine dependency, task-command route, or
   stale primary shell-only human path remains.
