# 能力与实测范围

Status: current capability guide with explicitly dated evidence.

[当前功能](index.md)与下列固定条件的观测分开阅读。这里提供可追溯的摘要，
不表示当前 checkout、当前主机或未来运行已经通过。精确 workload 和判据由对应 Owner 维护。

<a id="external-public-closeout"></a>
## 外部公网验收收尾

| Workload／用途 | 日期与被测版本 | 已完成结果 | 边界与原始证据 |
| --- | --- | --- | --- |
| 公网 Performance 七日观测的前四日 | 2026-11-20–23；kit 4.4.4 `01f795013ee9ded817304eba26e40805a21ddb89`，Runtime 4.4.5 `520b7ce314de24ad42ea079f97491919a24852b4` | 每日两个 Group 各 5,000 Worker；Any／targeted／Direct × 500／1,000／2,000 到达/s，一次重复、每 case 30 秒；四轮共 36 项 `passed`，1,260,000 正式响应与成功 Result 闭合；429／未知提交／客户端超时为 0，四轮自动 cleanup confirmed | 七日窗口尚未结束；不是 saturation、最大 TPS 或长期 SLA。停止波存在 KERNEL best-effort 证据丢弃，不能由 case 通过推定调度证据完整。[四轮观测与原 Run](../../integrations/worker-call-performance/observations/external/2026-11-23.json) |
| 公网 10k 停止波证据预算复验 | 2026-11-23；同一 kit 4.4.4／Runtime 4.4.5，Report 每队列预算 1,000 → 20,000 | 原 workload 得到 8 项 `passed`、1 项 `saturated`；`task-any-2000` 因在途上限缩为 10 秒测量窗；实际计划 275,000、发送 273,141、not-sent 1,859，全部已发送 Result 在 drain 后成功；429／无 HTTP 响应／未知提交／客户端超时为 0；自动 cleanup confirmed | 未缩短时的名义计划为 315,000；提前结束后的 40,000 次没有尝试。完整核对本轮 10,000 身份均为 network `unknown`／调度 `RECOVERY`，原 20 常驻连接仍在；所核对日志未见新增同类 Report WARN。不证明持续 2,000 TPS、每瞬间状态或 Android 问题已修复。[Run 45116963479](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45116963479)、[证据及维护失败记录](../../integrations/worker-call-performance/observations/external/2026-11-23.json) |
| 持久 Java Host 的空闲与断网恢复 | 2026-11-18；Host 4.4.2 `ccf676801663f953169f2d03058cbe7236a01123`，Runtime 4.4.0 | 原 20 个身份，75 分钟有界空闲观察；一次约 120 秒出站 DROP 后，GCP 在恢复后 59.619 秒确认 20 connected；进程与输入保留 | 有采样间隔和 TCP 换连；不证明零瞬断、所有故障或 Android 恢复。[原始观测](../../integrations/worker-call-performance/observations/external/2026-11-18.json) |
| 旧公网 Loaded Recovery 基础档 | 2026-11-12 运行，11-13 收尾；Runtime／kit 4.2.0 `aaed8d648c19e2f6332f37b4e831b0b194d5553b` | 1,000 → 999 Worker，两阶段各 10 Task × 5,000 Items，100,000 结果及完整导出，`diagnostic-passed` | 原公网 15k → 10k 目标及直接公网 10k 对照未通过建连；调查按 1,000 档结束。与上面的 Performance 是不同 workload。[收尾结果](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/closeout.json) |
| Runtime 升级与 RPC 回收现场验收 | 2026-11-19；Runtime 4.4.5 `520b7ce314de24ad42ea079f97491919a24852b4`；复用原 scope／SQLite，RPC 回收显式启用 1h／24h | 三 Item 有限任务与三次新 RPC 的 MD5 核对通过；首轮维护删除 892,741 inputs、250,774 Scores、69 Results；原 20 Worker 经显式 Lab start 恢复，清理已确认 | 这是运维验收；不证明自动重连、容量或未来数据量下的回收时延。制品／配置指纹、计数口径及限制见[固定升级记录](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#bounded-onsite-acceptance-and-closeout) |

前四日的 15,709 个、预算复验的 6,841 个 HTTP 已接收但初读未见 Result 的请求，
均在原身份下 drain 成功；它们不是未知提交。cleanup 的 Worker `unknown` 是连接投影，
不等于请求结果 unknown。Runner 成功也不把原有 `saturated` 改写为 `passed`。
预算复验的维护中发生五次配置读取权限失败，修正后显式重置启动限制；常驻 20 Worker
经原 Lab 手工恢复且 Host 进程不变，这不属于自动重连证明。较早停启的原因仍未知。
维护开始至恢复就绪确认约 4 分 20 秒，不是逐瞬间测得的停机时长；验收后本次恢复点已删除，
两台主机操作记录均已关闭，旧 scope 与 SQLite 数据保留。
资源样本的来源和覆盖范围保留在原始记录中：四轮未采集 Server／Redis／JFR 的负载期数据，
复验另有一次现场资源读取；不能把 Nginx 多进程 RSS 相加值当成独占内存。

早期 4.4.0–4.4.3 的 `invalid`、`saturated`、未知提交和清理不确定性继续保留在两份
[11-18](../../integrations/worker-call-performance/observations/external/2026-11-18.json)／
[11-19](../../integrations/worker-call-performance/observations/external/2026-11-19.json)观测中。
后续成功不改写原始状态，也不授权重放。公网 Loaded 的网络定位与未证实归因留在
[固定收尾报告](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/closeout.md)
及其[证据索引](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/evidence-index.json)，已结束调查不作为待办。

当前公网 CI 使用既有 Performance consumer。手动测量与
[限期七日观测](../../integrations/worker-call-performance/README.md#seven-date-external-observation)
共用执行机制；日期配置不是运行结果，缺席也不能补写为通过。旧 Loaded 公网 Actions 路线已退役，
本地 Loaded Nightly 与包内交付检查仍保留。操作入口见[运维指南](operations/deploy.md#外网观测入口)。

## 源码 Nightly 的固定性能快照

以下不是最新 main 成绩，而是北京时间 **2026-10-20 03:39** 的
[Run 39310825184](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/39310825184)，
被测 commit `2699da1e321b38d5d622f91ac07d2d9c11505834`，`laneConfigVersion=3`。
原始聚合见固定 [run-record](https://github.com/quiaingmuarua/xa_mass_platform/blob/e69d5f57e597a3e7ac2268a31b897386a0b32dcc/runs/20261019T193906Z-39310825184-1.json)。

环境为 4 个逻辑 CPU、Java 21、Redis 7.4.10、`DEFAULT` Pacer preset，带 lane 专用覆盖：
assignment-batch-limit 为 1,000、report-queue-capacity 为 10,000、max-probe-items-per-round 为 1,000，
各 Group Any 补给目标 1,000。完整条件见[被测版本 Owner](https://github.com/quiaingmuarua/xa_mass_platform/blob/2699da1e321b38d5d622f91ac07d2d9c11505834/integrations/worker-call-performance/README.md)
与[配置生成](https://github.com/quiaingmuarua/xa_mass_platform/blob/2699da1e321b38d5d622f91ac07d2d9c11505834/integrations/worker-call-performance/run_worker_call_performance.py)。
这些覆盖值不能作为默认部署能力。

| Workload | 固定条件 | 三轮结果（全部 `passed`） |
| --- | --- | --- |
| 深 backlog，Any Pool | 两 Group 各 4,000 Worker；每 Group 预置 600,000 Items；热身 15 秒、测量 30 秒 | 完成中位数 9,773.27 Items/s，min 9,650.03、max 10,040.00 |
| 深 backlog，指定 Worker | 同上；测量期间无新增 HTTP 输入 | 完成中位数 10,155.37 Items/s，min 10,000.00、max 10,560.00 |
| Open-loop，Task Any | 2,000 Worker；两个 Group 合计每秒 2,000 次到达 | 各轮指标中位数：p50 136.43ms、p99 245.46ms，HTTP 成功比例 1.0 |
| Open-loop，Task 指定 Worker | 同上 | p50 132.29ms、p99 240.84ms，HTTP 成功比例 1.0 |
| Open-loop，Direct Call | 同上，投递对照路径 | p50 4.74ms、p99 28.33ms，HTTP 成功比例 1.0 |

深 backlog 的完成时间来自成功 `RESULT_STORED` Owner 调用返回事件，并与完整生命周期和唯一有效导出核对；
它不是 Redis 精确提交时间或客户端收到答案的时刻。Open-loop 的 `completedPerSecond` 包含窗口结束后的 drain，
不能理解为全部结果在到达窗口内完成。表中 percentile 是各轮指标的中位数，未合并原始请求重算；
成功比例也不是严格的一秒 deadline 达成率。Direct Call 不创建 Item，不能用其延迟替代任务延迟。

## 恢复与稳定性证明的固定规模

[本地 Loaded Recovery](../../integrations/worker-loaded-recovery/README.md)使用 15,000 prepared／10,000 retained Worker，
连接门槛 14,800／9,900；四个阶段各 50,000 Items，共 200,000 Items，含一次 TERM、两次 KILL 的 Server 恢复。
这是当前 proof 的配置与验收范围，不是一次新运行结果或测得的最大承载量。
执行进展、完整导出与资源采样共同支持该 lane 的结论；不能推广为所有 Worker 同时 HOT、任意故障或无限持续运行。

## 如何比较与更新

| 状态 | 阅读方式 |
| --- | --- |
| `passed` | 条件满足后再读指标、重复次数与 spread |
| `saturated` | 到达触及 in-flight 上限；admitted rate 不是 Server 最大完成吞吐 |
| `invalid` | 测量前提不成立，不参与性能比较 |
| `failed` | 正确性底线或 fast-fail 触发，查看原始失败证据 |

更新摘要时同时更新日期、commit、环境／配置、统计口径、状态和不可变证据，
不只替换最大数字。不同 workload、Worker 数、measurement 版本和配置不能混成一个平台 TPS。
保留仍有价值的代表性结果，替换已失效的叙述，过程留在既有观测与 Git；不新增逐轮维护日记。

这些结果不证明 Android 真机、外部业务服务、真实短信有相同性能，也不证明大量活跃 Task、
任意 Group 分布、多租户隔离或严格公平。选证明从[验证入口](verification.md)开始；
部署版本与实时状态从[运维入口](operations/deploy.md#release-preview)核对。
