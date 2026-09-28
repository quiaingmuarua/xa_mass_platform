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

- 预热 100/s，持续 30 s（首轮 CI 显示 15 s 不足以消除第一次重复的 JIT 影响）；测量窗口 30 s；HTTP 等待 1 s，客户端超时 5 s。

**饱和吞吐模式**：窗口内不发 HTTP，没有发压端限制。

| 路径 | 做法 |
| --- | --- |
| `sat-task-any` | 每个 Group 一个有限 Task，先追加积压再批准，测完成速率 |
| `sat-task-targeted` | 同上，Item 按 workerId 轮询指定 |

- 积压量：每个 Group 15 万 Item，覆盖 30 s 窗口内最高约 10000/s 的完成速率（6 万时本地 16 核机器在窗口内做完了积压）。窗口内某个 Group 的积压被做完，即判 `invalid`（`items-exhausted`），需加大积压量。
- 每个 Group 新建一个有限 Task，在批准前追加完全部 Item（批次 100、16 路并发，耗时记为 `seedMillis`）。批准两个 Task 的时刻即窗口开始，窗口 30 s。
- 窗口结束时先记录两个 Group 仍持有租约的 Worker 数，再关闭两个 Task，等待租约全部释放后，用 `results:export`（仅成功结果）统计完成数。平台没有公开的 Item 计数 API，这是唯一不绕过 Owner 的计数方式。
- 完成速率 = 完成数 / 窗口秒数；窗口结束时仍持有的租约可能在关闭后才完成，因此报告 `± 持有租约数 / 窗口秒数` 作为误差上界。
- Handler 接近 0 时，饱和状态下全部 Worker 都会持有租约，Worker 数必然成为上限（2026-09-28 参考机：约 5700/s）。这是本模式的预期状态，不判 `invalid`。主结果是**每个 Worker 的周转时间** = Worker 数 x 窗口秒数 / 完成数，即一次租约周期（获取、投递、执行、Result、释放、再次到期）的平台耗时，与 Worker 数无关。同时报告完成速率和每秒检查的 Item 数（此时 B=1000 的每轮检查上限也可能生效）。

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

## 5. 用例状态与有效性判定

| 状态 | 含义 | 是否参与趋势与路径比较 |
| --- | --- | --- |
| `passed` | 负载在路径容量之内，资源都未被触顶 | 是 |
| `saturated` | 提供速率超过该路径容量（优先于"Worker 可用"判据：过载时租约积压会占满全部 Worker，这是饱和的结果，只记为 `workersExhausted`）：响应超出等待时间，在途请求触达上限后到达的请求无法发出。报告 `admitted/s`（已接收速率，不是完成上限；完成上限由饱和模式测量） | 只作为"超出容量"的事实，不参与数值比较 |
| `invalid` | 发压端自身跟不上，或某个 Group 没有空闲 Worker；数值不可比 | 否 |
| `failed` | 违反正确性底线或快速失败规则 | 否，且 lane 失败 |

判定 `invalid` 的条件（测量窗口内任一触发即成立）：

| 判据 | 条件 | 来源 |
| --- | --- | --- |
| Worker 可用 | 每个 Group 在每次可用采样中都至少有 1 个空闲 HOT Worker。Handler 接近 0 时，大量 `held-hot` 表示租约在等待 Result 收敛释放，属于机制积压，只作为 `leaseHeldPeakRatio` 报告，不判无效 | 每 5 s 一次有界公开读取（记录其成本） |
| Item 充足（饱和模式） | 窗口内积压始终 > 0 | 窗口起止各读一次 Item 计数 |
| 候选充足 | 候选缺口比例低于 1%（**S2 只报告不判定**，首批 CI 数据校准后再启用；targeted 路径的缺口包含目标 Worker 正忙，不代表资源不足） | Dispatch 事件聚合 |
| 发压充足（开环） | 没有在途上限拒绝时，调度延迟 p99 <= 100 ms。出现在途上限拒绝说明是服务端响应变慢，判为 `saturated` 而不是 `invalid` | Harness |
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
| 0 标定（约 10 s） | Harness JVM 上固定的 MD5 负载（单线程与 4 线程，各预热 1 s、测 2 s）；5000 次顺序 Redis PING 的往返时间 | 结果随该 job 的用例记录；趋势比较中与历史中位数偏差超过 25% 的 job 标记 `host-unqualified`，不作判定（不自动重排） |
| 0.5 job 预热 | 启动后先跑一个丢弃的用例：开环 job 用本 job 最高速率的 `task-any`，只有饱和模式的 job 用 `task-any-1000` | 结果只记录在 `warmup`，不进入用例统计 |
| 1 启动（约 60 s） | 2000 个 Worker 连接、HOT、Properties 就绪 | 120 s 超时即失败 |
| 2 冒烟（约 1 min） | `task-any` 500/s 一次，检查正确性底线 | 失败则停止，不跑后续矩阵 |
| 3 矩阵 | 本 job 负责的用例 | 按 7.2 快速失败 |

### 7.2 快速失败规则

只对以下几类信号提前终止，性能数值本身永不触发快速失败：

| 编号 | 信号 | 动作 |
| --- | --- | --- |
| F1 | 窗口前 10 s 已出现在途上限拒绝（`in-flight-cap`），或调度延迟 p99 > 100 ms（`generator-lag`） | 分别判为 `saturated` 或 `invalid`，跳过剩余窗口 |
| F2 | 排空阶段连续 30 s 没有新结果，且仍有待观察 Item | 本用例失败 |
| F3 | 按当前观察速率预测，排空无法在预算内完成 | 本用例失败 |
| F4 | 结果或执行者错误、协议或关联错误 | 立即失败 |
| F5 | 线程 / FD 超上限、进程意外退出 | 立即失败，终止 job |
| F6 | 平静门超时 | 本 job 剩余用例 invalid |

### 7.3 Job 划分（路径比较只在同一台 runner 内进行）

已在 `worker-call-performance.yml` 中实现（S4），通过手动触发 `suite=lane` 运行；定时任务切换到本 lane 放在 S6。放在现有 workflow 中而不是新文件，是因为 GitHub 只允许手动触发默认分支上已存在的 workflow。

- `lane-build`：先跑 Harness 与 runner 单测，再编译一次 Server、Host、Harness，上传为 artifact `performance-lane-build-<attempt>`（保留 1 天）。
- `lane`（matrix，`fail-fast: false`）：`rate-500`、`rate-1000`、`rate-2000` 各在一台 runner 上跑 3 条开环路径，`saturation` 跑两个饱和用例；路径顺序按重复次数轮换（ABC / BCA / CAB）。各 job 下载同一份构建产物，以 `--skip-build` 运行，上传 `lane-<job>-<attempt>`。
- `lane-summary`：下载各 job 证据，按预期 job 列表合并（`--merge-lane`）。任一 job 缺失或失败时，合并结果为失败；路径比值在合并时重新计算，仍只比较同一 job 内的用例。
- 耗时预估（重复 3 次）：构建约 3 min；最慢的开环 job 约 9 个用例 x 约 80 s ≈ 12 min；总墙钟约 16 min，略高于原定 15 min，以首次运行实测为准。
- 机器标定（Stage 0）与 job 级预热已在 S5 前加入。基线版本 JAR 按 commit SHA 缓存尚未实现：A/B 每次在同一 runner 上重新编译基线（约 2 min）。PR 冒烟仍待决定。

## 8. 趋势与比较

实现见 `lane_trend.py`（纯函数）与 runner 的 `--merge-lane`、`--lane-history`、`--lane-case`。

- **运行记录**：合并步骤为每次运行生成紧凑记录 `run-record.json`：运行身份（run id、ref、sha、事件、时间）、`laneConfigVersion`、每个 job 的主机标定、每个用例各重复的状态计数，以及通过（`passed`）的重复里各指标的中位数、最小值、最大值。`lane_record=true` 时，summary job 把记录追加到数据分支 `perf-lane-data` 的 `runs/` 目录（只有该 job 有写权限）。
- **Nightly 趋势**：
  - 只和同一 ref、同一 `laneConfigVersion` 的最近 7 次记录比较，不足 7 次时报告 `insufficient-history`。分支上的运行不会影响 `main` 的趋势。
  - 所有数值先按主机标定换算到历史中位数主机速度（延迟与周转乘以速度比，吞吐除以速度比）；本次 job 与历史中位数速度偏差超过 25% 时不作判定。
  - 当前中位数减去（或加上）本次半极差后，仍落在历史 `[min, max]` 之外、且方向变差，标记 **suspect**。用例多数状态和历史不同（例如 `passed` 变成 `saturated`）也标记 suspect。只报告，不判失败。
- **手动 A/B（按用例、按需停止）**：
  - 用 `baseline_ref` 加 `lane_case` 触发。单个 runner 上 A、B 各自启动环境，按 ABBA 顺序交替跑选定用例，每个环境都先做 job 级预热；每跑完一组判断一次，最少 2 组、最多 5 组。
  - 主指标：开环为成功调用 p99，饱和为每 Worker 周转时间。波动带取 `main` 最近记录中该用例主指标的相对极差中位数（下限 5%）；不足 3 条记录时用 10% 并标注 provisional。
  - 最近 2 组都在带内：`no-difference`；都在带外且同向：`worse` / `better`；5 组后仍无结论：`inconclusive`。用例不是 `passed` 的组不计入判定。
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
