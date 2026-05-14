# xa-mass-trace

Status: current trace operator module.

`xa-mass-trace` owns the first operator-facing trace query surface for XA Mass
Platform.

It does not define a second trace schema. Canonical trace write-path ownership
stays in `platform_infra/mass-trace-sink` through `ExecutionEvent`,
`ExecutionEventType`, and the configured sink implementation.

## 30-Second Start

Read this module first when you need one of these:

- reconstruct a task or task-work lifecycle from canonical trace output
- validate whether a lifecycle/integration test preserved canonical event
  emission
- inspect trace artifacts without falling back to compatibility projection or
  MDC logs

Fast read order:

1. [../doc/TRACE_CONTRACT.md](../doc/TRACE_CONTRACT.md)
2. `src/main/java/com/xa/mass/trace/operator/TraceOperatorService.java`
3. `src/main/java/com/xa/mass/trace/cli/XaMassTraceCli.java`
4. `src/main/java/com/xa/mass/trace/query/TraceQueryBackend.java`
5. `src/main/java/com/xa/mass/trace/query/DuckDbTraceQueryBackend.java`
6. `src/test/java/com/xa/mass/trace/operator/TraceOperatorServiceIntegrationTest.java`
7. `src/test/java/com/xa/mass/trace/cli/XaMassTraceCliIntegrationTest.java`

## Role

- query canonical trace JSONL output through DuckDB
- own the operator-facing trace read/analyze service surface
- provide a local CLI as an adapter over the operator service
- reconstruct task/work timelines without falling back to compatibility
  projection or ad hoc engine logs
- validate trace artifacts against the canonical schema and event registry

## What It Does Not Own

- trace schema definition
- runtime correctness or lifecycle truth
- `TaskDetailStore` compatibility residue
- server-owned HTTP trace APIs
- ClickHouse or other remote backend wiring

## Current MVP

Current commands:

- `timeline`: task or task-work ordered event timeline
- `stats`: grouped event counts with optional filters
- `validate`: JSONL + canonical schema/event-registry validation
- `analyze`: scenario-oriented trace analysis for known integrated flows

Current backend:

- local JSONL trace output queried through DuckDB

Current operator seam:

- `TraceOperatorService` owns request/response-shaped timeline, stats,
  validate, and scenario-analysis use cases
- the CLI is a thin adapter over that service

Planned adapter/backend seam:

- operator use cases stay stable while adapters may expand beyond CLI and
  query backends may expand beyond local DuckDB

Current operator/testing rule:

- if an integration, E2E, or chaos scenario claims trace visibility, use this
  module or the same query backend path to read canonical sink output
- do not treat raw MDC log text or compatibility projection as the default
  trace proof surface
- scenario diagnosis should live here, not as scattered hand-written event
  expectations across unrelated tests

Current built-in scenario analyzers:

- `single-message-success`
- `duplicate-callback-replay`

## Test Pairing Workflow

For trace-observed integration or E2E verification:

1. run the real scenario so canonical `.jsonl` trace files are produced
2. run `validate` on the trace path first
3. run `analyze` for the known scenario when a built-in analyzer exists
4. run `timeline` for the target `taskId` when you need operator drill-down
5. run `stats` when the scenario is about event presence, volume, or grouped
   severity/event-type behavior

This keeps the proof path aligned:

- runtime/host scenario produces canonical sink output
- `xa-mass-trace` reads the same canonical artifacts
- scenario checks and trace drill-down stay on the operator-facing read path
  rather than on raw logs or test-local ad hoc event logic

## Command Examples

Run through Maven from the repository root:

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="timeline --path trace-events --task-id task-123"
```

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="stats --path trace-events --task-id task-123 --json"
```

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="validate --path trace-events"
```

```bash
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli -Dexec.args="analyze --path trace-events --scenario single-message-success --task-id task-123 --json"
```

## Verification

The MVP is integration-tested in both directions:

- canonical `JsonlExecutionEventSink` output is queried end-to-end by
  `TraceOperatorService`
- the CLI is integration-tested as an adapter over that same service path
- validation rejects malformed or non-canonical trace rows
- this module is the default observation surface for trace-observed
  integration/E2E scenarios until a remote backend becomes a verified mainline

Read with:

- [../doc/TRACE_CONTRACT.md](../doc/TRACE_CONTRACT.md)
- [../platform_infra/mass-trace-sink/README.md](../platform_infra/mass-trace-sink/README.md)
