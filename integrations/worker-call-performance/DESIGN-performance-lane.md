# Performance Lane 重新设计

Status: proposal（设计评审稿，尚未实现；实现前以现有 [README](README.md) 为准）。

## 1. 目的与非目标

**目的**：在可配置资源都不成为瓶颈的前提下，测量各调用路径在不同速率下的性能，
以及调度路径本身的吞吐上限，并比较路径之间的差异。

**非目标**（归属其他 lane）：

| 不测 | 归属 |
| --- | --- |
| 有限资源下的周转效率（Worker 复用、候选回收、池耗尽） | Dynamic Matching / Convergence Health |
| 争用下的 Group 隔离与公平性 | Convergence Health 或专门的调度 proof |
| 断连、重启、失败重试与恢复 | Loaded Recovery / Worker Correctness |
| 生产 SLA、真实设备容量、跨机网络延迟、长时间 soak | 不在本仓库 CI 范围 |
| 配置参数本身的取舍（例如 B=100 与 1000 对比） | 单独的配置实验，不进本 lane |

判定原则：**正确性底线可以阻断；性能数值不直接阻断，只进入趋势比较。**

## 2. 参数分类

### 2.1 资源参数：统一上调，保证不成为瓶颈

| 参数 | 生产默认 | 本 lane 取值 | 处理 | 说明 |
| --- | --- | --- | --- | --- |
| 分配上限 B `xa.mass.kernel-pacer.assignment-batch-limit` | 100 | **1000** | 上调 | 单 Task 检查预算约 `10 x B` Items/s；1000 为允许上限 |
| Worker 数 / Handler | — | **2 Group x 1000** / MD5 | 上调 | 单个 Host JVM；Handler 耗时接近 0 |
| Pool 补货目标 `task-rpc.refill-by-worker-group[<group>]` | 按 profile | `any / {} / 1000`（>= B） | 上调 | 避免 Refill 先成为瓶颈 |
| Adapter `report-queue-capacity` | 1000 | **10000** | 上调 | Report 不因容量丢弃或回压 |
| `task-rpc.max-probe-items-per-round` | 256 | **1000** | 上调 | 256 / 100 ms 约 2560/s，离 2000/s 太近；1000 为允许上限 |
| `task-rpc.max-waiters` / `max-pending-observations` | 10000 / 100000 | 不变 | 审计 | 2000/s、等待 1 s 时约 2000 个等待者 |
| `direct-call.*` 等待与在途上限 | 3000 / 10000 / 1000 / 10000 | 不变 | 审计 | 已超过 2000/s、等待 1 s 的需要 |
| Tomcat 线程 / 虚拟线程 | 200 / 关闭 | 不变 | 审计 | `items:call` 与 `direct-calls` 通过 DeferredResult 返回，等待期间不占 servlet 线程 |
| Harness 在途上限 | — | 4096 | 审计 | 2000/s、等待至多 1 s 时约 2000 在途 |
| Item TTL | 120 s | 不变 | 审计 | 窗口远小于 TTL |

审计表的权威版本是 runner 中的 `LANE_KNOB_AUDIT`，每次运行写入 `evidence/knob-audit.json`。每一项都附有"如何证明未被触顶"的依据，由 S2 的有效性判定落实（见第 5 节）。

### 2.2 机制常数：不调整，正是被测对象

| 常数 | 值 | 所属 |
| --- | --- | --- |
| Dispatch 节拍 / Initialization 节拍 | 50 ms / 100 ms，完成后计时 | Pacer Dispatch |
| Task Score slot 门控 | 100 ms | Task Score Owner |
| Refill 节拍与每轮预算 | 50 ms；1000 行，回收每 Group 100 | Pacer Refill |
| Result 通道批次与并发 | 批次 100；成功 6..10，全局 10；空闲 100 ms | Result Convergence |
| Item claim 租约 | 5 s | Pacer Dispatch |
| 单飞生产者 | 每个 Producer 同时最多一轮 | Main Scheduler |
| Adapter 单次 Command 消费上限 | 100（`command-consume-limit` 已是最大值） | Server 投递契约 `DirectCallService.MAX_CONSUME_LIMIT`；超过会被 HTTP 400 拒绝 |

当某个机制常数成为实际瓶颈时，这是本 lane 的**发现**，报告需标注对应的归因指标（第 4 节）。

## 3. 固定环境与用例

### 3.1 环境

- Ubuntu 24.04、4 逻辑 CPU、Java 21、Redis 7.4.10，preset DEFAULT，B=1000。
- 2 个 Group（`perf-a`、`perf-b`），各 1000 个 Java Worker，1 个 Host JVM，1 个 WebSocket Adapter。
- 每个 job 只启动**一次** Redis、Server、Host。用例之间复用，并通过"平静门"隔离（第 6 节）。
- 开环用例通过 `items:call` 调用各 Group 的托管 Task（Project 为 `perf-lane`），每个用例使用新的 Message ID，上一个用例的 Item 在平静门前必须全部收尾。饱和用例（S3）使用新建的有限 Task，结束时关闭。

### 3.2 用例矩阵

**开环速率模式**：按计划到达时间发送，一次调用对应一个 Item，负载平均分到两个 Group。

| 路径 | 速率（总计） | 选择方式 |
| --- | --- | --- |
| `task-any` | 500 / 1000 / 2000 每秒 | `items:call`，Any Pool |
| `task-targeted` | 500 / 1000 / 2000 每秒 | `items:call`，按 workerId 轮询 |
| `direct` | 500 / 1000 / 2000 每秒 | Adapter 范围的 `direct-calls`，按 workerId 轮询；作为传输层对照 |

- 预热 100/s，持续 15 s；测量窗口 30 s；HTTP 等待 1 s，客户端超时 5 s。

**饱和吞吐模式**：窗口内不发 HTTP，没有发压端限制。

| 路径 | 做法 |
| --- | --- |
| `sat-task-any` | 每个 Group 一个有限 Task，先追加积压再批准，测完成速率 |
| `sat-task-targeted` | 同上，Item 按 workerId 轮询指定 |

- 积压量：每个 Group 至少 10 万 Item，并满足"窗口内积压不归零"（第 5 节），不满足则判无效并在下次加大。
- 追加在窗口之外完成；批准时刻即窗口开始；窗口 30 s。

## 4. 指标

每个用例都记录：

| 类别 | 指标 |
| --- | --- |
| 结果 | 完成吞吐（窗口内得到结果的 Item/s）；1 s 内成功的比例（开环）；排空后成功率 |
| 延迟 | 发起到结果的 p50 / p95 / p99（开环）；样本数 |
| 路径差异 | 同一 job、同一速率下 `task-*` 相对 `direct` 的延迟比与吞吐比 |
| 调度归因 | 每轮 Dispatch 耗时（p50/p99）与轮次；每秒检查的 Item 数；候选缺口比例（可认领 Item 中未拿到候选的比例）；严格获取 STALE 比例；Refill 轮次与入池数 |
| 成本 | 每个完成 Item 的 Redis 命令数（commandstats 差值）与 Server CPU 秒 |
| 资源充足判据 | 第 5 节各项的实际观测值 |

调度归因只开启 Server 上默认关闭的 `xa.mass.TaskDispatch` Owner 事件（`lane-attribution.jfc`，无采样），运行结束后按用例窗口离线聚合（`LaneAttribution`）。`--lane-attribution off` 可关闭，用于测量开销；开销超过 3% 时改为只在诊断模式启用。Worker 从 RECOVERY 回到可分配的停留时长没有现成事件，暂不提供。

## 5. 有效性判定（资源充足）

测量窗口内以下任一条件被触发，该用例判为 **invalid**：不报性能数值，也不参与趋势比较。

| 判据 | 条件 | 来源 |
| --- | --- | --- |
| Worker 充足 | 各 Group HOT Worker 数的最小值 >= 执行中数量的 2 倍 | 每 5 s 一次有界公开读取（记录其成本） |
| Item 充足（饱和模式） | 窗口内积压始终 > 0 | 窗口起止各读一次 Item 计数 |
| 候选充足 | 候选缺口比例低于 1%（**S2 只报告不判定**，首批 CI 数据校准后再启用；targeted 路径的缺口包含目标 Worker 正忙，不代表资源不足） | Dispatch 事件聚合 |
| 发压充足（开环） | 没有未发出的请求，且调度延迟 p99 <= 100 ms | Harness |
| 机器合格 | 第 7 节 Stage 0 标定在合格区间内 | Stage 0 |

## 6. 正确性底线与平静门

- **正确性底线（阻断）**：
  - 每个被接受的 Item 在排空预算内都有观察到的结果；
  - 结果内容和执行者正确，没有协议或关联错误；
  - 线程数、FD 不超过上限（512 / 8192）。
- **排空预算**：沿用现有公式 `max(180, TTL + ceil(accepted / (10 x B)))`，在 B=1000 下基本就是 180 s。实际排空通常在秒级结束。
- **平静门**：每个用例开始前，等待两个 Group 的 Worker 都是 HOT 且已连接、上一个用例的 Task 已收尾、结果通道空闲。30 s 内达不到时，本 job 后续用例全部判 invalid，因为环境已被污染。

## 7. 执行结构与快速失败

### 7.1 Stage

| Stage | 内容 | 失败处理 |
| --- | --- | --- |
| 0 标定（约 20 s） | 固定 CPU 小基准与 Redis 往返基准 | 超出合格区间：标记 host-unqualified，整个 job 重排一次 |
| 1 启动（约 60 s） | 2000 个 Worker 连接、HOT、Properties 就绪 | 120 s 超时即失败 |
| 2 冒烟（约 1 min） | `task-any` 500/s 一次，检查正确性底线 | 失败则停止，不跑后续矩阵 |
| 3 矩阵 | 本 job 负责的用例 | 按 7.2 快速失败 |

### 7.2 快速失败规则

只对以下几类信号提前终止，性能数值本身永不触发快速失败：

| 编号 | 信号 | 动作 |
| --- | --- | --- |
| F1 | 窗口前 10 s 已出现未发请求或调度延迟 p99 > 100 ms | 本用例 invalid，跳过剩余窗口 |
| F2 | 排空阶段连续 30 s 没有新结果，且仍有待观察 Item | 本用例失败 |
| F3 | 按当前观察速率预测，排空无法在预算内完成 | 本用例失败 |
| F4 | 结果或执行者错误、协议或关联错误 | 立即失败 |
| F5 | 线程 / FD 超上限、进程意外退出 | 立即失败，终止 job |
| F6 | 平静门超时 | 本 job 剩余用例 invalid |

### 7.3 Job 划分（路径比较只在同一台 runner 内进行）

- `build`：编译一次 Server、Host、Harness，上传为 artifact。基线版本 JAR 按 commit SHA 缓存。
- `rate-500`、`rate-1000`、`rate-2000`：每个 job 在同一 runner 上跑 3 条路径。路径顺序按重复次数轮换（ABC / BCA / CAB）。
- `saturation`：两个饱和用例，同样轮换顺序。
- nightly 每个用例重复 3 次，每个 job 约 9–10 min，总墙钟约 12 min。PR 冒烟重复 1 次。

## 8. 趋势与比较

- **Nightly 趋势**：每晚把每个用例 3 次重复的中位数和极差写入基线存储（位置待定，见第 10 节）。与同一用例最近 7 次 nightly 比较：中位数超出历史 `[min, max]` 且超出本次极差时，标记 **suspect**。附上调度归因指标，不判失败。
- **手动 A/B（按用例、按需停止）**：
  - 只选定用例，A、B 在同一 runner 上按 ABBA 交替，每跑完一组判断一次，最少 2 组、最多 5 组。
  - 差异落在 nightly 历史的同版本波动带内，判"无差异"并提前结束；差异方向一致且超出波动带，判"有差异"；否则继续加组。
  - 结论只说明差异是否存在，不作为速度提升的证明。
- 取消现有固定阈值（成功率 5 个百分点、p99 20%）。2026-09-28 的 A/B 显示，同版本三次重复的波动已大于这两个阈值。

## 9. 迁移与退役

- 退役 `task`、`direct`、`direct-diagnosis`、`rpc-diagnosis`、`nightly` 五个套件和各 fixture 版本，统一为本设计的单一清单。
- `baselines/` 下的历史报告保留为按版本记录的证据，并注明与新清单不可直接比较。
- 更新 workflow：`suite` 输入改为 `mode`（nightly / smoke / ab）。新增 `case`、`baseline_ref` 和 `max_pairs` 输入。PR 冒烟按路径过滤触发。
- 同步更新 README（重写）、TESTING、proof registry 和 AGENTS 中 Call Performance 相关的条目。

实现切片（每片可独立验证）：

| 切片 | 内容 | 验证 |
| --- | --- | --- |
| S1 | 固定环境（2 x 1000，B=1000）、资源参数上调与审计、一次启动、平静门 | 本地非参考机跑通启动与平静门；审计表完成 |
| S2 | 开环矩阵、有效性判定、快速失败、归因指标聚合 | Harness / runner 单测；CI 手动跑一次 |
| S3 | 饱和模式 | 积压不归零判据生效 |
| S4 | workflow 重构：build + matrix、artifact 复用、时间预算 | nightly 墙钟 <= 15 min |
| S5 | 基线存储、nightly 趋势比较、按用例 A/B | 连续 7 晚后开始产生 suspect 判定 |
| S6 | 退役旧套件，更新文档 | Docs Contract；旧入口不再可用 |

## 10. 已定与待定

已定（2026-09-28）：

- 基线存储：仓库内专用数据分支。
- Adapter：1 个，两个 Group 共用。
- nightly 每个用例重复 3 次。
- 实现从 S1 开始。

待定：

1. PR 冒烟是否启用及触发路径范围（S4 前决定）。
2. 调度归因事件是否默认开启（S2 测量开销后决定）。
