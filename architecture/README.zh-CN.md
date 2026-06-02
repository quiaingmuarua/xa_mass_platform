# XA Mass Platform 架构导览

Status: human-facing Chinese architecture guide.

这个目录是给人读的架构入口，不是内核规范�?
严格�?contract、baseline、验证命令和 agent guardrails 仍然�?
[`../doc/`](../doc/) 里�?

## 推荐阅读顺序

1. [Quick Start](./quick-start.md)
   - 最�?SDK 主线：创�?task、append items、worker pull、submit result
2. [Mental Model](./mental-model.md)
   - 架构心智模型：task、event、worker、transport、result 怎么配合
3. [Add Worker And Event](./add-worker-and-event.md)
   - 如何添加一个新�?event �?worker capability

这三份目前主要是英文，代码示例是主内容。中文总览先放在本文件�?
[`../README.zh-CN.md`](../README.zh-CN.md)�?

## 一句话模型

XA Mass Platform 的核心是一条运行时主线�?

```text
task shell
  -> append items with eventCode
  -> runtime schedules work
  -> worker executes the selected event handler
  -> worker submits result
  -> runtime converges item and task state
```

它不是普�?CRUD 后台。它更像一个任务运行时内核�?

- 接收 work items
- 匹配 worker
- 分发 work
- 接收 result
- 处理 retry / timeout / release
- 收敛 task 状�?

## 最小业务接入流�?

通常你添加一个业务能力，会走这条线：

```text
register event
  -> register project
  -> 声明 WorkerGroup �?eventBindings
  -> �?worker 注册到该 group
  -> create task shell
  -> append task items
  -> worker pulls or receives work
  -> worker submits result
  -> read stable-final results
```

### 1. Event

`eventCode` 是能力身份�?

例如�?

```text
crawler.fetch-page
image.resize
bot.command
```

worker 端应该按 `eventCode` 找到本地 handler�?

### 2. Project

project 是业务容器。它说明某个业务域允许哪�?event�?

### 3. Worker

WorkerGroup 通过 `eventBindings` 声明一�?worker 能处理哪�?
`eventCode`，以及在哪些 project 下可用�?

worker 是真实执行单元。它绑定到某�?WorkerGroup，并携带自己�?
runtime identity、transport identity 和属性�?

注册 worker 不等于在线。在线和路由�?transport presence 管�?

### 4. Task

task 是生命周期壳。创�?task shell 只是创建壳，真正�?work 通过
append items 进入 runtime�?

### 5. Result

worker 只提�?result，不直接�?task 状态�?
runtime �?engine 会根�?active lease、retry budget、finality、barrier �?terminal
policy 收敛状态�?

## 当前 owner 边界

| 领域 | 当前 owner | 说明 |
| --- | --- | --- |
| task lifecycle | engine | task status、intake、terminal policy |
| ready/lease/retry | `TaskWorkRuntime` | 热路径可执行 work truth |
| public result | `TaskResultRuntime` | stable-final result rows |
| worker capability | WorkerGroup / WorkerCandidateIndex | capability candidate source |
| delivery/presence | transport | adapter routing、presence、delivery |
| trace | trace/audit plane | evidence，不�?runtime truth |

## 从哪里继�?

如果你要�?SDK 嵌入代码�?

- [`../sdk/xa-mass-embedded-sdk/README.md`](../sdk/xa-mass-embedded-sdk/README.md)

如果你要接外�?worker�?

- [`../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md`](../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md)
- [`../integrations/samples/README.md`](../integrations/samples/README.md)

如果你要�?engine�?

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../xa-mass-engine/README.md`](../xa-mass-engine/README.md)

如果你要�?HTTP/API�?

- [`../xa-mass-server/doc/INTERNAL_API_REFERENCE.md`](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md)

如果你要诊断生命周期�?

- [`../xa-mass-trace/README.md`](../xa-mass-trace/README.md)
- [`../doc/TRACE_CONTRACT.md`](../doc/TRACE_CONTRACT.md)
