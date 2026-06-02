# XA Mass Platform 中文介绍

Status: current human-facing Chinese introduction.

这份文档面向第一次了解项目的人。它解释项目是什么、主线怎么走�?
应该从哪里开始读。更严格的内核约束、owner 边界和验证命令，请看
[`doc/`](./doc/) 和各模块 README�?

## 一句话理解

XA Mass Platform 是一个通用的分布式任务调度平台�?

它解决的不是“保存一条任务记录然后查状态”的 CRUD 问题，而是一个更�?
运行时内核的问题�?

```text
一批结构化 work items
  -> �?eventCode / capability / routing / reachability 匹配 worker
  -> 通过 transport 分发
  -> worker 执行并回写结�?
  -> runtime 收敛每个 item 和整�?task 的状�?
```

典型场景包括�?

- 批量爬虫、批量采集、批量处�?
- IM / Bot / 会话式消息处�?
- LLM Agent 调度
- RPA / 设备任务分发
- 需要异�?worker、重试、超时、结果收敛的自动化系�?

## 推荐阅读入口

如果你是第一次看这个仓库，推荐按下面顺序�?

1. [architecture/README.zh-CN.md](./architecture/README.zh-CN.md)
   - 人类友好的架构导�?
2. [architecture/quick-start.md](./architecture/quick-start.md)
   - 最�?SDK 主线示例
3. [architecture/add-worker-and-event.md](./architecture/add-worker-and-event.md)
   - 如何添加 event �?worker capability
4. [sdk/README.md](./sdk/README.md)
   - SDK 嵌入式使用入�?
5. [sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md](./sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md)
   - 外部 worker / polling worker 接入入口
6. [doc/README.md](./doc/README.md)
   - 内核 contract、baseline、runbook 索引

## 当前项目定位

当前项目已经不是一个简单的 `engine + demo` 结构，而是在逐步形成完整�?
产品壳和运行时内核边界：

- `xa-mass-engine`
  - runtime kernel owner
  - 负责 task lifecycle、assignment、result、retry、release、terminal convergence
- `platform_infra`
  - runtime / storage / trace 基础设施
  - `TaskWorkRuntime` �?`TaskResultRuntime` 是当前热路径核心 truth
- `transport`
  - worker delivery、presence、result ingress 的数据平�?
  - polling / websocket / socket �?peer adapters
- `sdk/xa-mass-embedded-sdk`
  - 推荐�?JVM 集成入口
  - 面向 embedding app、worker、自动化脚本和宿主应�?
- `xa-mass-server`
  - Spring Boot 参考宿主、轻量后台产品骨架、HTTP/API、IAM/API key、控制台后端、验证入�?
- `integrations/xa-mass-worker-pack`
  - 内置/示例 worker 能力和调�?worker
- `xa-mass-trace`
  - trace operator CLI，用于本�?timeline / stats / validation

## 当前主线

项目当前的主线执行路径是�?

```text
Task shell
  -> item append
  -> TaskWorkRuntime enqueue
  -> assignment and matching
  -> dispatch binder
  -> transport delivery
  -> worker execution
  -> result ingest
  -> TaskResultRuntime visible final row
  -> task progress / terminal convergence
```

用人话解释：

1. 先创建一�?task shell�?
2. 再往 task �?append work items�?
3. 每个 item 携带 `eventCode`�?
4. SDK/intake 可把 `eventCode`、project 解析成显�?worker group selector；engine 再根�?group capability、reachability、rules 和资源状态选择 worker�?
5. transport 把已�?assignment �?work 送给 worker�?
6. worker �?`eventCode` 执行本地 handler�?
7. worker 提交 result�?
8. runtime 判断 retry、finality、资源释放和 task 收敛�?

## 核心概念

### Task

`Task` 是生命周期壳。它描述任务级事实，例如�?

- project
- user
- contract
- intakeStatus
- sharedConfig
- executionSpec

当前任务创建是两步主线：

```text
createTaskShell(...)
  -> 创建任务�?

appendTaskItems(...)
  -> 添加真正可执行的 work items
```

### Task Contract

当前主要有两�?task contract�?

- `BATCH`
  - 更适合批量任务
  - intake sealed 后，所�?item final 可以推动 task 自动 terminal
- `SESSION`
  - 更适合会话式或持续接收消息的任�?
  - 当前 work 集合清空不代�?session 结束

### Event

`eventCode` �?capability identity�?

worker 能不能处理某�?item，核心看它是否声明了对应 event binding�?

当前 event metadata 包括�?

- `PriorityClass`
- `ResponseMode`
- `TargetScope`

这些是描述�?metadata �?policy input，不�?runtime truth。它们不能直接决定：

- queue order
- result finality
- worker command lifecycle
- worker state

### Worker / WorkerGroup

当前 worker capability 主线已经从旧�?context 思路收敛�?worker/group/index�?

```text
WorkerGroup
  -> eventBindings
  -> candidate source index

Worker
  -> runtime execution identity
  -> belongs to a group
```

注册 worker 不等�?worker 已经在线�?
在线 truth 属于 transport presence�?
真正能不�?dispatch，还要经�?engine �?matching、rules、capacity、lock/resource policy�?

### Result

public result read 已经收敛�?runtime-owned result truth�?

```text
TaskResultRuntime
  -> stable-final visible result rows
  -> task-local result seq
  -> repair / barrier truth
```

server-local review materialization 可以作为 debug/audit/read-model residue，但不能作为
public `/results`、SDK result query、archive �?truth�?

## 推荐业务接入主线

如果你要把一个业务能力接入平台，推荐理解成这条链�?

```text
register event
  -> register project
  -> 声明 WorkerGroup �?eventBindings
  -> �?worker 注册到该 group
  -> create task shell
  -> append items with eventCode
  -> worker executes by eventCode
  -> worker submits result
  -> read runtime final results
```

这条线同时适用�?SDK 嵌入�?server / HTTP / 控制台产品壳，只是入口形式不同�?

## 模块地图

| 模块 | 作用 |
| --- | --- |
| `xa-mass-base` | shared base models、command/event runtime vocabulary、低�?channel primitives |
| `platform_infra/mass-runtime-api` | runtime queue/lease/counter/result contracts |
| `platform_infra/mass-runtime-memory` | embedded / test 默认内存 runtime |
| `platform_infra/mass-runtime-redis` | Redis-backed runtime 实现 |
| `platform_infra/mass-storage-api` | task/worker/rule storage contracts |
| `platform_infra/mass-storage-memory` | 内存 control-plane storage |
| `platform_infra/mass-storage-jdbc` | JDBC control-plane storage |
| `platform_infra/mass-trace-sink` | canonical execution event sink |
| `transport/transport_api` | transport-neutral dispatch/result/system-event contracts |
| `transport/transport_runtime` | transport runtime assembly、delivery、routing、presence glue |
| `transport/polling-adapter` | polling / pull worker adapter |
| `transport/websocket-adapter` | WebSocket adapter |
| `transport/socket-adapter` | socket adapter |
| `xa-mass-engine` | lifecycle、assignment、result handling、policy seams |
| `sdk/xa-mass-embedded-sdk-api` | stable embedded SDK-facing auth/catalog/event/model contracts |
| `sdk/xa-mass-embedded-sdk` | SDK embedding/runtime composition |
| `xa-mass-server` | Boot reference host、轻量后台产品骨架、HTTP controllers、IAM/API key、console backend |
| `xa-mass-testing` | perf、chaos、acceptance harness |
| `integrations/xa-mass-worker-pack` | sample/dev worker capabilities |
| `xa-mass-trace` | DuckDB-backed trace operator CLI |

## 不要误解的点

- engine �?runtime kernel，不�?CRUD backend�?
- worker registration 不是 worker online�?
- transport online 不是 worker capability�?
- `eventCode` 不是 task type，而是 capability identity�?
- trace �?evidence，不�?runtime truth�?
- projection �?compatibility/debug/audit residue，不�?public result truth�?
- 统一 event language 不等于统一 runtime，也不等于一�?generic event owner�?

## 下一�?

如果你想快速跑起来�?

- �?[architecture/quick-start.md](./architecture/quick-start.md)
- 再看 [sdk/README.md](./sdk/README.md)

如果你要接外�?worker�?

- �?[sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md](./sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md)
- 再看 [integrations/samples/README.md](./integrations/samples/README.md)

如果你要改内核：

- 先看 [AGENTS.md](./AGENTS.md)
- 再看 [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
- 然后按模块看 owner README，例�?[xa-mass-engine/README.md](./xa-mass-engine/README.md)

## 总结

XA Mass Platform 可以理解成一个面向“批量任�?+ 会话任务”的分布式运行时调度内核�?

它的重点不是把任务记录存下来，而是�?

- 正确接收 work items
- 正确匹配 worker
- 正确处理 dispatch、lease、retry、timeout、result callback
- 正确释放资源
- 正确收敛 item �?task 的最终状�?
