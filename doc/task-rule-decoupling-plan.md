# Task 与 Rule 解耦计划

Status: complete; implemented and verified locally on 2026-09-12.

基线：`9680e1646`。本轮实现已完成；以下保留实施决策与验收边界。
当前机制以 [Matching Owner](../worker_matching_jvm/README.md#shared-rule-binding)、
[Task Owner](../kernel_jvm/doc/resource-model/task-resource-model.md) 和
[Pacer 分配策略](../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md) 为准。

## 实施与验证记录

- 完成 Matching 自有 Task 绑定、共享定义及单次 Lua 写入/批量解析；旧接口与旧读取路径已删除。
- Kernel/Pacer 仅统一 Task ID 寻址，Task 描述符、Score、hold、确认和 claim 未扩展。
- Server 创建及 Runtime View、测试替身、契约清单与 Owner 文档已同步。新增持久设施仅关联 HASH。
- Kernel / Pacer / Matching / Server 单元测试分别为 50 / 107 / 15 / 318 项通过。
- Redis Owner 全量 117 项通过；随后补强的“合法字段之外含额外字段”损坏记录场景单独复跑通过。
  真实 Redis 验证绑定并发、未知结果重试、跨 Owner 创建失败及一次写入/100 Task 一次读取的命令预算。
- Runtime Boundary 全量 16 项通过，包括真实 Worker 执行两个共享 Rule 的 Task，及关闭其中一个后的独立执行。
  修正既有 Serviceability 夹具的批准空 Task 竞态：先追加 Item 再批准，保留原恢复断言和时间预算。
- Worker Dynamic Matching 通过：1,000 Worker、150,400 Item，Prepare 增量为零，进程和控制文件不变。
  证据：`build/task-rule-dynamic-matching-20260912-1535/evidence/worker-dynamic-matching.json`。
- Worker Convergence Health 的 `state` 和 `task-fault` 均通过，覆盖 Server 重启、Worker 中断、恢复及结果保留。
  证据目录：`build/task-rule-convergence-health-20260912-1543/`。
- Docs Contract（含 15 项检查器测试）、证明选择契约（含 7 项检查器测试）、旧接口/依赖残留扫描和差异检查通过。
  分支证明选择脚本只读取已提交差异；本轮另用相同过滤器检查工作区路径，不将空分支差异当作无需证明。

上述均为本地执行证据，不表示已运行完整 CI、Android、发行或 Product 集成。
测试只使用并清理独立 `test_*` scope；没有执行非测试数据切换。
Handler 与物化索引仍是后续工作，本轮不将过渡实现固化为最终架构。

## 1. 本轮目标与完成边界

Matching 拥有 `taskId → ruleId` 关联和可复用的 Rule 定义。Kernel/Pacer
只使用 Task ID，不接触 Rule ID，也不引入另一份 Candidate 身份。

```text
Server 校验请求与 Group，生成 taskId
  → Matching 建立 Task 绑定并保存共享 Rule
  → Kernel 创建原有 Task 描述符

Pacer 按 Task 需求取得并持有有界 HOT Worker 集合
  → Matching 按 taskId 批量读取自身绑定与 Rule
  → 按原 Task 顺序匹配并发布 Candidate
  → Kernel 按 taskId 消费 Candidate
  → 精确确认 Worker hold，claim Item，投递 Command
```

这是引用解耦的第一步。保留外部 `allocationRule`、现有 DSL、PRECOMPUTED
异步 Demand、Matching 消费生命周期及按 Task 隔离的 Candidate Cache。
内部 Rule ID 暂时由内容派生；它不是最终的命名 Handler ID。

后续方向仍是：Rule 对应一组关联的函数，负责维护 eligibility index 和消费其查询；
PRECOMPUTED 表示提前物化 eligibility。Handler、索引模型、Item query 扩展及异步
Demand 的退出不属于本轮，不增加相关占位接口或动态框架。

## 2. Owner 与契约切换

| Owner | 本轮改动 | 保留的职责边界 |
| --- | --- | --- |
| Kernel | 将实际承载 Task ID 的 Candidate 地址参数明确命名为 `taskId` | Task/Item/Worker Score、Candidate Cache、hold、确认与 claim |
| Pacer | 同步 Task ID 契约和调用方 | 优先级、缺额、HOT 观察、初始 hold、Demand 编排与派发 |
| Matching | 拥有 Task 绑定、共享 Rule 定义及批量解析 | Facts、规则解释、按输入顺序匹配；不读取 Kernel Task 元数据或生命周期 |
| Server | 创建时调用绑定入口；Runtime View 按 Task ID 读取 Rule | 参数准入、跨 Owner 编排和原有 HTTP 投影 |

### Kernel/Pacer 的精确范围

- `TaskRuleMatchDemand.TaskCandidateNeed.candidateId` 改为 `taskId`；Cache、
  分配需求、消费调用及对应测试中的地址参数同步修改，不保留旧 accessor。
- 不增加 Kernel 操作、Rule DTO、Rule 查询依赖或 Task 描述符字段。
- Task 描述符及其 Redis 形状、Worker/Item Score 编码、dirty 协议、exact-score
  确认、Item claim、重试、执行结果和 Task 收敛保持现状。
- Candidate Cache 的物理 key、过期、容量和消费保持现状，仍按 Task ID 寻址。
  两个 Task 共享 Rule 不代表共享 Candidate Cache、候选容量或 Worker hold。
- ON_DEMAND 的 ANY、显式 Worker ID 和 country 索引路径不新增 Task→Rule 查询。
  不改变 Task 模式、有限 Task 与托管 Call Task 的准入条件。

### Matching 公共契约

在现有 `WorkerMatchingCatalog` 中替换 Rule 操作：

```text
bindTaskRule(taskId, workerGroupId, allocationRule)
  → 现有 MutationResult

loadTaskRules(taskIds)
  → Map<taskId, MatchingRule 或 null>

MatchingRule(ruleId, workerGroupId, allocationRule)
```

删除 `createCandidateRule`、`loadCandidateRules` 和 `CandidateRule`，无兼容别名。
Rule ID 由 Matching 生成和解析；Kernel/Pacer 不接收 `MatchingRule`。
保留现有结构校验与 DSL 解释边界，不借此改变规则接受范围。

### Server 调用与投影

Task 创建保持“校验请求与 Group → 生成 taskId → Matching 绑定 → Kernel 创建”
顺序。Runtime View 用 `loadTaskRules` 按 Task ID 获取规则并核对 Group，继续返回
原有 `allocationRule` 投影。不增加 HTTP 字段、Rule 管理 API 或配置项。

空规则、PRECOMPUTED 对 Facts 的现有要求，以及 ON_DEMAND 的空 selector、
显式 ID 和其他 selector 的行为保持原状，不混淆省略字段与空对象。

## 3. Matching 存储、并发与成本

### 唯一关联与共享定义

沿用现有 Rule HASH key，改变 field 含义；只新增一个关联 HASH：

```text
xa_mass:<scope>:matching:candidate:rules
  ruleId → {"workerGroupId":"...", "allocationRule":{...}}

xa_mass:<scope>:matching:task:rules
  taskId → ruleId
```

Rule 定义保持固定的两个字段。ID 为 `rule-` 加上现有规范化 Rule JSON 的
UTF-8 字节 SHA-256 小写十六进制摘要，摘要输入包含 Group。
递归排序对象键；保留数组顺序、现有数字编码和运算符名称，不判断逻辑等价。
同 Group、相同规范化内容复用；不同 Group 不复用。

### 绑定：一次有界 Lua

输入先在 Java 校验和编码。一个 Owner 内 Lua 读取两份 HASH，完成检查后才写入：

| 情况 | 结果 |
| --- | --- |
| 定义或正确关联缺失，补齐缺失部分 | `APPLIED` |
| 定义与关联均已存在且一致 | `UNCHANGED` |
| Task 绑定到其他 Rule，或已有定义字节不一致 | `CONFLICT`，不覆盖 |
| 输入不合法 | `INVALID`，不执行写入 |
| Redis 错误或结果未知 | 异常，沿用 Server 503 |

两个 key 的类型及已有值均在写入前检查，避免已知拒绝条件产生部分写入。
Lua 不被描述为具备故障回滚能力；未知结果后，使用相同 Task ID 和内容重试可以
补齐缺失状态。已有正确关联但定义缺失时，只能由匹配原 ID 的注册输入补齐。
不迁移 Task 绑定，不自动覆盖损坏定义，不增加后台修复。

Matching 写入与 Kernel Task 创建分别提交。后者失败时保留已写入数据，不跨 Owner
回滚；整次 HTTP 创建不因此获得幂等保证。Task 结束或关闭不删除共享 Rule 或关联，
本轮不增加 Rule 生命周期、引用计数或垃圾回收。

### 读取：一次有界 Lua

`loadTaskRules` 最多接受 100 个唯一 Task ID，去重保序；空批次直接返回空 Map。
一次 Lua 依次 HMGET 关联、去重 Rule ID、HMGET 唯一定义。返回关联和去重后的定义，
避免同一 JSON 随 Task 数量重复传输。

Java 严格校验定义字段、ID 格式和内容摘要；摘要使用存储的原始规范化字节，
不经数字解析再编码。缺失、损坏或内容身份不符时，对应 Task 返回 `null`。
Redis 类型错误等基础设施故障仍抛出；不回退到旧的 Task ID Rule 布局。

### 消费与资源成本

每个 Demand 批量解析 Rule；同一 Rule 在批内只规范化一次，不增加跨批缓存。
仍按原 Task 顺序匹配，使用各 Task 自己的容量；仅将 Cache 接受的 Worker
从本批剩余 held pool 移除。不能因为 Rule 相同而把同一 hold 复制到多个 Task。

Rule 缺失、损坏、非法或 Group 不符时跳过对应 Task，不回退为 ANY。
保持现有消费异常、队列拒绝、过期检查及后续正常缺额轮次的行为；未使用的 hold
自然过期，不补偿释放、不重放 Demand，也不增加恢复扫描。

成本验收仅约束变更的 Owner 操作：

- 绑定：一次 Lua 客户端往返。
- 最多 100 个 Task 的 Rule 读取：一次 Lua 客户端往返，共享定义只传一次。
- 保留现有 Candidate 计数及发布的逐 Task 操作；不把上述预算扩大为整条分配链路
  的固定命令数、吞吐量或延迟承诺。
- 新增持久设施仅一个关联 HASH；新增线程、队列、连接 Owner、调度器和后台任务均为零。

## 4. 实施顺序、证明与清理

这是一次完整契约切换，以下为实施顺序，不是保留两套运行路径的阶段：

1. 完成 Matching 绑定、共享定义和批量读取，切换 Server 创建与 Runtime View。
2. 切换 Matching 消费，统一 Kernel/Pacer 的 Task ID 契约及全部调用方和测试替身。
3. 更新架构检查、Kernel 契约清单及 Owner 文档，清除旧接口、旧模型与旧读取路径。

Matching 架构检查允许显式的 Task ID 和 Matching 自有绑定 key，继续禁止依赖
Kernel Task 元数据/生命周期、Score Owner、Pacer 实现及 Server。修改过时限制，
不删除整个边界检查。实施时同步更新根架构与变更契约、Matching Owner、Pacer
分配/派发文档、Server 创建/Runtime View 文档及证明说明；当前文档不提前宣称切换完成。

| 证明范围 | 必须覆盖的场景 |
| --- | --- |
| 定义身份 | 相同内容共享、跨 Group 分离、对象键排序、数组顺序和数字编码、无运算符等价化 |
| 并发与失败 | 同 Task 同参并发、同 Task 不同规则冲突、不同 Task 共享定义、未知结果重试、缺失阶段补齐、损坏数据拒绝 |
| 原子检查 | 拒绝或错误 key 类型在写入前发现；不因冲突创建孤立的新定义 |
| Task 隔离 | 同 Rule 的不同 Task 独立缓存、容量、优先级、消费和生命周期；一个结束不影响另一个 |
| 调度保护 | 同一 held Worker 不因共享 Rule 重复分配；dirty、过期和旧 exact-score 被拒绝；确认和 claim 保持现有规则 |
| 降级边界 | 规则缺失不退化为 ANY；创建后半段失败不影响其他 Task；Matching 消费失败不增加补偿路径 |
| 外部兼容 | Task 创建与 Runtime View、空规则及现有 DSL；ON_DEMAND ANY/ID/country 不访问绑定 |
| 命令预算 | 绑定一次 Lua，100 Task 读取一次 Lua，共享定义去重传输，无逐 Task 确认读 |

先执行受影响的 Owner/Pacer 测试，再执行完整模块单元测试：

```powershell
.\gradlew.bat :kernel_jvm:test :kernel_pacer_jvm:test :worker_matching_jvm:test :server_jvm:test
```

按照 [TESTING.md](../TESTING.md) 和 [Proof Registry](testing/proof-registry.md)
运行 Redis Owner、Runtime Boundary、Worker Dynamic Matching、Worker Convergence
Health、Docs Contract、Kernel 契约清单及证明选择检查。

Redis 并发与命令数必须用独立 `test_*` scope 的真实 Redis 证明。Runtime Boundary
增加真实 Worker 执行两个共享 Rule 的 Task、并在其中一个结束后继续另一个的场景；
同时断言共享定义和独立关联，否则只验证两个 Task 能执行不足以证明切换。
保留 Facts 更新使旧 Candidate 惰性失效的既有证明。

本轮不改变 Worker 协议、Handler、Adapter 或 SDK。仅在实施实际触及这些边界时，
按证明选择规则补跑 Worker Correctness 和对应 Transport 测试。

## 5. 数据切换与交付

Rule HASH 的 field 语义改变，沿用已选的停机重建策略，不增加旧格式兼容读取。
只在明确指定目标 scope 后停止相关进程，使用精确 scope 的 `SCAN + UNLINK`
清理运行数据并重建。Kernel Task 形状没有改变，不代表旧 Matching 数据能直接复用。
实施和证明均不得默认清理非测试 scope。

完成时必须同时满足：Kernel/Pacer 仅以 Task ID 交接；Matching 独占关联和共享定义；
Task 元数据与调度状态机没有扩大；共享 Rule 未耦合候选、租约或 Task 生命周期；
全部调用方、契约与文档反映新边界；测试区分源码检查、单元结果和真实运行证明。

本轮完成后再讨论 Handler 与物化索引的切换。内容派生 ID、当前 DSL 和异步 Demand
是本次明确保留的过渡实现，不应写成最终 Matching 架构约束。
