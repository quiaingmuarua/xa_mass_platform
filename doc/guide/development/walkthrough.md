# 沿一个能力看完整接入

Status: current code-backed Worker capability walkthrough.

以现有 `extension.worker.string.md5.batch` 为例：Host 提供字符串摘要能力，
Batch Checks 用纯契约将摘要解释为“前缀是否为 0”，Server 与 Kernel 继续使用通用任务路径。
这条链展示新增能力需要接在哪里，不要求为每个 Event 新建传输或调度机制。

## 1. 业务 Handler 与协议

[StringUtilityWorkerEvents](../../../worker_simulator_jvm/src/main/java/com/xa/mass/workersimulator/StringUtilityWorkerEvents.java)
通过 `WorkerEventDefinition.extension("string.md5.batch", ...)` 注册短能力名；
Core 形成完整名 `extension.worker.string.md5.batch`。
输入为 `{values:[...]}`，输出保留各输入关联及 MD5 答案。

Definition 的 resolver/handler 负责本地输入和业务输出，
不读 Task/Item lease，也不自己构造第二套 Result 路由。
同一 Definition 用于 TASK 与 DIRECT_CALL。
完整调用、错误、并发与运行终止规则见
[Worker Core Message Path](../../../transport/worker-core/README.md#message-path)。

修改算法或协议先读
[能力测试](../../../worker_simulator_jvm/src/test/java/com/xa/mass/workersimulator/StringUtilityWorkerEventsTest.java)。
已有消费者使用完整 Event Name；不兼容输入/输出应明确变更相关声明和调用者，
不能在 Dispatcher 中暗加 alias 或 fallback。

## 2. Host 真正安装能力

[Simulator](../../../worker_simulator_jvm/src/main/java/com/xa/mass/workersimulator/WorkerSimulator.java)
汇集编译好的 Definitions，Group 配置选择其实际安装集合；
[products.json](../../../worker_simulator_jvm/config/products.json) 为 Demo 显式选择批量 MD5。
“写了一个 Handler 类”和“运行的 Worker 已加载它”是两件事。

`platform.worker.events.snapshot` 返回该进程真正装配的完整事件名。
Server 的 Group `eventCodes` 是独立声明，不会安装 Handler，也不能作为安装证明。
数量、身份和属性由 [Simulator Owner](../../../worker_simulator_jvm/README.md#one-configuration-one-entry)维护。

## 3. 宿主准备 Group 和资源

[Preview Boot 配置](../../../server_boot_jvm/src/main/resources/application-preview.yaml)
声明 `demo-sim` 的事件，以及 Any Pool/`worker.any`。
它们与 Worker Host 的配置各司其职。Project 负责 Task 准入和归属；
Group 的 Pool/query 启用关系不由 Project 或检查页面生成。

沿源码检查初始化顺序：Group 准备、场景依赖校验、Adapter 和场景准入。
缺少依赖应在启动暴露，不靠运行时 fallback 补装资源。

## 4. 场景解释结果

[AppCheckContracts](../../../scenarios/app-checks-jvm/src/main/java/com/xa/mass/scenario/appchecks/AppCheckContracts.java)
提供 `md5-prefix-demo-batch-v1`，负责种子输入关联、结果结构和前缀判定；
它不持有网络、Redis 或调度资源。

[Preview 检查配置](../../../scenarios/app-checks-jvm/src/main/resources/app-checks/preview-configuration.json)
把 `md5-prefix` 绑定到该契约、Demo Group、批量事件与 Any 查询。
`seedsPerItem=10`、`unitPrice=1` 是这个示例的配置值，不是平台通用法则。
Task 首次创建保存有效配置；后续保存配置不会重写旧 Task。

修改这一层先读
[契约测试](../../../scenarios/app-checks-jvm/src/test/java/com/xa/mass/scenario/appchecks/AppCheckContractsTest.java)
和[分组测试](../../../scenarios/app-checks-jvm/src/test/java/com/xa/mass/scenario/appchecks/AppCheckSeedGroupsTest.java)，
再按变化选择 Boot/真实 Worker 的证明。

## 5. 从 API 到用户答案

[第一个任务](../first-task.md)提供可复算的三个输入和期望答案。
用户创建、导入、确认费用并启动后，检查 Task/Item、真实结果和 CSV：
不是只看 HTTP 201，也不是只看“连接正常”。

本地协议测试、场景契约测试、Boot 真实 HTTP/Redis/Worker 测试、source/ZIP acceptance
分别证明不同边界。选择依据在
[TESTING](../../../TESTING.md#selection-decision)和
[Proof Registry](../../testing/proof-registry.md)。
只有这次运行的报告能说明当前版本通过；链接到测试源码只是定位断言。
