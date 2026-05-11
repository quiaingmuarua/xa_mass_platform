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
