# XA Mass Platform 中文介绍

XA Mass Platform 是一个通用的分布式任务调度平台。

它要解决的不是简单的“建一条任务记录，然后等人来查状态”，而是一类更偏运行时的问题：

- 把一批结构化工作项交给一批异构的执行端
- 根据能力、路由和状态选择合适的 worker
- 跟踪每个工作项的执行结果
- 在任务级别收敛为可判断、可审计的最终状态

这类场景常见于：

- 批量爬虫、批量采集、批量触达
- IM / Bot / 会话式消息处理
- LLM Agent 调度
- RPA / 设备任务分发

## 这个项目想做什么

从项目定位看，XA Mass Platform 想做的是一个“任务运行时内核”，而不是一个传统的 CRUD 后台。

它更关心下面这些问题：

- 一个任务是否还能继续接收新的工作项
- 当前有哪些工作项处于 ready、leased、retry、final 状态
- 某个 worker 是否有能力执行某类事件
- 某个任务在并发、重试、超时、回调乱序下是否还能正确收敛

所以这个项目的重点不是页面、表结构或管理后台，而是：

- 生命周期
- 匹配与派发
- 结果回写
- 重试与超时
- 终态收敛

## 这个项目怎么理解最顺

可以把 XA Mass Platform 理解成四层：

1. 任务壳 `Task`
2. 运行时工作项 `TaskWorkRuntime`
3. 传输层 `transport`
4. 执行端 `worker`

一个任务真正跑起来的大致主线是：

`Task shell -> item append -> runtime enqueue -> dispatch binder -> transport delivery view -> result convergence -> task state`

用人话解释就是：

1. 先创建一个任务壳
2. 再往任务里追加具体工作项
3. 工作项进入运行时队列
4. 引擎为这些工作项匹配可执行的 worker
5. 通过 polling / websocket / socket 等适配器把任务送出去
6. worker 回写执行结果
7. 引擎根据结果、重试、超时和 intake 状态收敛整个任务

## 当前最核心的模型

这个项目刻意把“任务真相”拆开了，没有把所有语义都塞进同一个对象里。

当前最核心的三个真相来源是：

- `Task.contract`
  - 表示任务契约，当前主要区分 `SESSION` 和 `BATCH`
- `Task.intakeStatus`
  - 表示任务是否还能继续接收新的工作项，当前主要是 `OPEN` 和 `SEALED`
- `TaskWorkRuntime`
  - 表示热路径上的运行时真相，例如 ready、delay、lease、retry、result、counter

这套拆分背后的意思很简单：

- `Task` 负责描述任务壳和生命周期语义
- `TaskWorkRuntime` 负责执行层面的实时状态
- 兼容性投影、消息明细、尝试历史，不应该反过来成为运行时真相

## 为什么要区分 SESSION 和 BATCH

项目当前明确支持两种主要任务契约：

- `BATCH`
  - 更适合批量任务，比如爬虫、批处理、批量分析
- `SESSION`
  - 更适合会话式或持续接收消息的任务，比如 IM、Bot、持续事件流

这两者共享同一个 engine 内核，但在策略上会有分流：

- `BATCH`
  - intake sealed 后，所有 work final 可以自动关闭任务
  - 更偏向吞吐和批量调度
- `SESSION`
  - 当前 work 集合清空并不代表任务结束
  - 更偏向低延迟和持续接收消息

项目当前的方向不是拆成两套 engine，而是在同一个内核里，通过 contract 和 workload profile 做显式分流。

## 运行时与存储的边界

理解这个仓库时，一个很重要的原则是：运行时真相和控制面存储不是一回事。

可以粗略分成三层：

1. 控制面存储
   - 任务壳、worker 注册、规则定义、提交者等
2. 运行时状态
   - ready queue、lease、retry、runtime counters
3. trace / audit
   - 事件流、追踪、诊断、审计信息

当前代码主要已经明确了前两层：

- 控制面存储放在 `platform_infra/mass-storage-*`
- 运行时状态放在 `platform_infra/mass-runtime-*`

这意味着：

- 不是所有状态都应该落数据库
- 不是所有查询模型都应该反推运行时行为
- 不应该为了“方便看”就让消息投影重新主导热路径

## 传输层是显式子系统

这个项目不是默认把 worker 当成某种 WebSocket 客户端来设计的。

当前 transport 是一个独立子系统，明确分成：

- `transport_api`
- `transport_runtime`
- `polling-adapter`
- `websocket-adapter`
- `socket-adapter`

这代表项目想守住一个边界：

- engine 只负责运行时内核
- transport 只负责任务投递、结果回写和系统事件通道
- 某个具体协议不能重新定义任务内核

## 项目入口怎么看

如果你只是想快速跑起来或定位主要入口，可以先记这几个模块：

- `xa-mass-server`
  - Spring Boot 可运行入口
  - 负责 HTTP API、控制台和前端壳
- `xa-mass-sdk`
  - SDK 主入口
  - 适合嵌入到 JVM 应用中
- `xa-mass-engine`
  - 任务生命周期、派发、结果处理的核心内核
- `platform_infra`
  - 运行时和存储基础设施
- `transport`
  - transport 合同、运行时和各类适配器
- `xa-mass-testing`
  - perf、chaos、transport harness 等验证工具
- `xa-mass-worker-pack`
  - 样例 worker、调试 worker、样例命令运行时

## 一条推荐的业务主线

如果你想从“这个平台到底怎么用”来理解项目，最顺的一条主线是：

`project 注册 -> submitter 注册 -> worker 注册 -> 创建 task -> 提交结果`

这条链同时适用于 SDK 嵌入和 Boot Shell / HTTP 验证，只是入口形式不同。

### 1. project 注册

`project` 是任务和能力的业务容器。

它回答的是：

- 这个任务属于哪个业务项目
- 这个项目允许哪些 `eventCode`
- worker 可以在哪些项目下提供能力

在 SDK 里，通常通过 `registerProject(...)` 注册项目元数据。

如果项目没有先注册清楚，后面的 task create、worker capability、submitter scope 都会缺少稳定业务边界。

### 2. submitter 注册

`submitter` 可以理解成“谁有资格代表某个业务主体发任务”。

它主要解决：

- 谁可以创建任务
- 谁可以访问哪个项目
- 谁可以触发哪些事件

在 SDK 里，通常通过 `registerSubmitter(...)` 注册，再通过凭证做认证。

对外的 HTTP 入口也遵守这套思路：不是任何人都能直接调任务接口，而是由 submitter 凭证代表一个被授权的调用方。

### 3. worker 注册

`worker` 是任务执行端，不等同于某个具体连接。

worker 注册主要声明：

- `workerId`
- 支持哪些 `eventCode`
- 属于什么 transport family
- 具体使用哪个 `adapterId`
- 所属项目和辅助属性

如果需要更细的路由或上下文隔离，还会继续注册 `workerContext`。

这里有一个很重要的认知：

- 注册 worker 不等于 worker 已经在线
- transport 在线也不等于它一定能接某个任务

真正能不能派发，最终仍然由 engine 按规则、能力和当前状态决定。

### 4. 创建 task

任务创建在当前主线上是两步，而不是一步：

1. 创建 task shell
2. 再追加 task items

也就是说，推荐理解成：

- `POST /api/v1/tasks` 或 `createTaskShell(...)`
  - 只创建任务壳
- `POST /api/v1/tasks/{taskId}/items` 或 `appendTaskItems(...)`
  - 真正把工作项送进系统

如果是 `BATCH` 任务，常见做法是：

1. 创建任务壳
2. 批量追加 items
3. `sealTask(...)`
4. 等待任务自动收敛到 terminal

如果是 `SESSION` 任务，当前 work 集合清空通常不代表任务结束，是否继续 append、何时 seal、何时终止，要看会话型业务自己的节奏。

### 5. 提交结果

worker 收到任务后，会按 `eventCode` 执行本地逻辑，然后把结果提交回来。

这里的关键不是“往某张表里写一条成功/失败记录”，而是：

- engine 要校验 active lease
- runtime 要应用这次结果
- 系统要判断是否重试、是否 final、是否释放资源
- 最后再决定 task 是否推进到新的状态

对于 pull / polling worker，提交结果通常是显式 API 或 session 方法。

对于 realtime worker，结果会沿着各自 adapter 的回写路径进入统一的 result ingest 主线。

所以“提交结果”在这个项目里本质上是：

- worker 报告执行结果
- engine/runtime 统一收敛执行真相

而不是“worker 自己直接改任务状态”。

## 为什么这条主线重要

很多人第一次看这类项目，容易把重点放在：

- 页面
- 任务表
- worker 在线列表

但 XA Mass Platform 更核心的是上面那条业务主线。

它真正要保证的是：

1. 先把业务边界定义清楚
   - `project`
   - `submitter`
   - `worker`
2. 再把任务送进来
   - `task shell`
   - `task items`
3. 再让 engine 统一调度和收敛
   - dispatch
   - result ingest
   - retry / expiry / terminal

如果按这条线理解项目，会比直接从 controller、DTO 或数据库表入手更顺。

## 适合谁看

这份文档更适合下面几类读者：

- 第一次接触仓库，想知道项目到底是干什么的人
- 想判断项目是否适合自己的任务分发场景的人
- 需要快速理解整体架构，再决定深入哪个模块的人

如果你接下来要改代码，建议继续往下读这些文档：

- [README.md](./README.md)
- [doc/README.md](./doc/README.md)
- [xa-mass-engine/README.md](./xa-mass-engine/README.md)
- [platform_infra/README.md](./platform_infra/README.md)
- [transport/AGENTS.md](./transport/AGENTS.md)
- [xa-mass-sdk/README.md](./xa-mass-sdk/README.md)
- [xa-mass-server/README.md](./xa-mass-server/README.md)

## 一句话总结

XA Mass Platform 可以理解成一个面向“批量任务 + 会话任务”统一调度问题的分布式运行时内核。

它的重点不在于把任务记录存下来，而在于：

- 正确接收工作项
- 正确匹配执行端
- 正确处理并发、重试、超时和回调
- 最终把任务收敛成可信的状态
