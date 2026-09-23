# Task ANY 2k：调度节奏、容量回收与资源争用归因

本轮已定位到三个不同层次：单 Task 的现有调度节奏限制，Worker 释放被拒绝却未向上
呈现的实现问题，以及 CI 上 HTTP、锁与 GC 的额外压力。继续缩短 Command append
成本仍有价值，但仅靠这一处批量化无法使当前单 Task 稳定完成 2,000 次调用/s。

最直接的运行证据是：相同当前产物、相同 CPU 分配、1,000 个真实 Worker，连续
120 秒准确发出 240,000 次请求，Task ANY 窗口内成功响应为 993.2/s，Direct 为
1,999.85/s。两者均未受压测端限制。Task 最终另有 60,600 个 failed Result；Direct
全部 240,000 次成功。这证明这组环境能承担共享投递路径的 2k 工作负载，Task 路径
存在额外限制；Direct 不承担 Task 调度、持久化和后续观察，不能替代 Task 容量验收。

## 版本与实验边界

- 分支为 `main`，HEAD 为 `5697beb8a528e81937bbcec43a8a8bde7f5e08fe`。
- 本地使用当前未提交的同 key Command append 批量实现，以及上一轮的 Runtime
  Boundary fixture 修正。没有更改 Pacer、Score、Matching、HTTP、RPC 等生产参数。
- 普通本地 Server JAR SHA-256 为
  `21cc52b0f55856459b39af126fb00ceb0b724c8ac1c86b4cff011beae3e032f8`。
  CI 使用提交版本，不能把 CI 与本地差异全部算作硬件或批量化收益。
- 本地为 Windows 上的 Ubuntu 22.04 WSL、Java 21.0.12、Docker Redis 7.4.10。
  CI 为 Ubuntu 24.04、4 个逻辑 CPU。不是同一参考环境。
- 本地四个正常诊断案例均启用既有 JFR，沿用现有 runner、真实 Server/Host/Harness
  进程和独立 disposable Redis。没有用实现调用、伪造 Report 或调整等待期限替代闭环。
- shared 模式将 Server、Host、Harness、Redis 都限制在逻辑 CPU 0–3；isolated 模式
  分别使用 0–3、4–7、8–11、12–15。Server 始终只有四个逻辑 CPU；每个 JVM 保留
  `-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError`。运行之间没有并行构建干扰。
- 各组合只执行一次，目的为归因。没有完成无 JFR 的三轮交替参考验收，不能据此
  宣称改进比例、平台容量上限或生产 SLA。

完整本地数据位于 `build/performance-attribution-20260921/`，其中
`attribution-evidence.json` 为聚合索引。每例的 `environment.json` 记录 CPU 分配、
产物指纹和进程清理；`evidence/case-summary.json`、`server-diagnostics.json` 保存
安全汇总。原始 JFR、Host 文件和私有阶段数据保留在 private 目录，不作为公开工件。

## 当前 CI 观察

检查了当前 HEAD 的 [无 JFR 手动运行 35592271804](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/35592271804)
和 [JFR 运行 35594446989](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/35594446989)。
这两次不是 scheduled nightly；最近的
[scheduled 35531151506](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/35531151506)
使用较早提交 `a5d1f886`，只作为历史背景，不能直接归因到某次代码变更。

当前提交无 JFR 的历史 Task suite 使用 100 Workers、30 秒测量窗口：

| Case | 实际发送/s | 窗口内成功响应/s | 原响应成功率 | 压测端受限 | 补查 succeeded / failed / not_observed |
| --- | ---: | ---: | ---: | --- | --- |
| any-100 | 100.0 | 99.2 | 100% | 否 | 3,000 / 0 / 0 |
| any-500 | 500.0 | 325.9 | 66.54% | 否 | 15,000 / 0 / 0 |
| any-1000 | 1,000.0 | 203.9 | 21.73% | 否 | 30,000 / 0 / 0 |
| any-2000 | 1,416.0 | 61.6 | 5.47% | 是 | 37,825 / 4,604 / 0 |
| targeted-500 | 500.0 | 439.3 | 88.87% | 否 | 15,000 / 0 / 0 |

`any-1000` 在没有压测端限制时已经明显退化，因而不能将差表现全部归咎于发压器。
`any-2000` 受限，不能拿 61.6/s 作为服务器容量定值。补查分母为已接受请求，
不一定等于发送总数；原响应超时后补查成功不会重写原响应的结果和延迟。

JFR CI 的 any-2000 有以下同时出现的压力信号：

- HTTP executor 达到 200/200 active，观测到 queued 峰值 2,810。
- Adapter command consume 远端调用均值 449.9ms，report append 为 420.4ms；
  对应 Server servlet 初始阶段均值分别为 29.1ms 和 16.6ms。差额包含 servlet
  外的排队、客户端及网络等成本，不能全部精确归给某一个队列。
- 30 秒内 Server 平均占用 2.460 核、Harness 0.728、Host 0.043、Redis 0.249。
  总量约 3.48/4 核；均值不能证明某个时刻仍有充足可运行资源。
- GC pause 合计 2.569 秒；不能把 GarbageCollection 的并发阶段总时长当成停顿。
- RPC Result probe 共 2,026 批，累计读取 421,724 个 ID，命中 1,590 个。
  `TaskRpcWaitRegistry.tryRegister/remove` 有大量超过诊断阈值的 monitor 等待。
  同步 Lettuce await 也是主要阻塞栈。栈样本数量不是 CPU 百分比。
- 613 个抽样提交中，有 519 个在 Claim 开始前 HTTP 等待就已超时，62 个链路不完整。
  551 个可配对样本的初始化到 Claim 中位数为 54.2 秒；548 个发布到 Result 写入
  样本的中位数约 110ms。主要排队发生在执行前。各分段样本集不同，不能相加分位数。

CI 上 Command append 均值 84.4ms 是长轮成本之一，但仍不能据此断言它是整个
退化的唯一原因；循环吞吐、容量返回和 HTTP 压力会相互影响。

## 本地真实进程控制

所有行保留现有单请求 1 秒业务等待、5 秒客户端超时、4,096 在途上限及 DEFAULT
Pacer。100 Worker 两例为原 `any-2000`；1,000 Worker 两例为现有
`rpc-any-2000` / `direct-step-2000`，不是通过修改同一 case 的 fixture 制造对比。

| 本地目录 | Workers / 测量秒数 | CPU 分配 | 实际发送/s | 窗口内成功响应/s | 压测端受限 |
| --- | --- | --- | ---: | ---: | --- |
| shared4-any2k-jfr | 100 / 30 | 共享四核 | 1,987.9 | 278.2 | 是 |
| isolated4-any2k-jfr | 100 / 30 | 按进程分开 | 1,996.2 | 307.9 | 是 |
| isolated4-rpc-any2k-jfr | 1,000 / 120 | 按进程分开 | 2,000.0 | 993.2 | 否 |
| isolated4-direct2k-jfr | 1,000 / 120 | 按进程分开 | 2,000.0 | 1,999.85 | 否 |

隔离资源没有让 100 Worker 场景接近目标。这一单次、受限对照只能支持继续排查
机制的判断，不能发布“隔离提升了多少百分比”的性能结论。

1,000 Worker Task 的后 90 秒成功响应为 999.1/s。原响应中有 120,202 次 succeeded、
119,798 次 not_observed；固定补查后为 179,400 succeeded、60,600 failed、0 未观察。
Direct 原响应全部成功，成功调用延迟 p99 为 100.3ms，包含计划发送滞后。

这些案例的 `passed` 代表现有有限测量契约通过：预置条件、协议、资源证据与
已接受 Result 的有限闭合成立。契约允许观测到 failed Result，因此 `passed`
不代表 2k 成功吞吐验收通过，也不能将失败 Result 计为成功工作量。

## 机制限制：单 Task 的 100ms 可见性与 100 条预算

现有 Owner 组合是：

1. [Task Score](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/score/TaskScoreBandCore.java)
   时间槽为 100ms；[Redis 实现](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/score/redis/RedisTaskScoreBandCore.java)
   的 `acquireSchedulingTasks` 排除当前槽，只返回严格早于当前槽的 Task。
2. [TaskDispatchPolicy](../../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/TaskDispatchPolicy.java)
   每轮每 Task 最多检查 100 Items，并把访问过的可 Claim Task 重写到本轮开始时间。
3. 同一 managed Task 的新 Item 不会把已经 RUNNING 的 Task 每次重新唤醒到旧槽。
   下一轮还需满足 50ms 的完成后间隔，不能并发补跑。

因此，在时钟正常、重写成功的当前路径下，持续积压的单 Task 常态约每 100ms
获得一次有效调度，100 条预算对应约 1,000 个 Item 检查/s。50ms 的 Producer
间隔不是完整节奏公式。慢轮、扫描延迟或缺少可用 Worker 只会进一步减少进展。
这是当前单 Task 的组合限制，不是整个平台多 Task 的固定全局上限。

1,000 Worker 实验中，120 秒恰好记录 1,194 个 Dispatch rounds、119,400 个 Item
检查和相同数量的发布；轮耗时均值仅 14.5ms。候选与确认阶段的窗口内请求
119,300 个，全部取得候选，Worker confirm 拒绝为零。边界轮跨越测量起止使不同
事件的计数相差一批，不是丢失证据。观察到约十轮/s，与源码组合一致。

这个场景里的 Command append 均值已降至 3.07ms，仍稳定停在约 1k/s。当前证据
足以排除“只要把 append 改快就能达到单 Task 2k”这一判断；不构成批量实现的
正式 A/B 收益结论。

100 Worker isolated 场景则有 297 轮、29,648 个检查，只取得并发布 9,236 个
候选，且已确认的候选没有 Worker confirm 拒绝。这里还受可用容量返回闭环影响，
不能只用单 Task 预算解释约 308/s 的全部损失。这个运行的有限逐 Item trace
出现 198 次 per-key overflow、影响 4 个键；本节采用未依赖这些键的聚合计数，
不把局部链路称为完整追踪。

## 实现问题：释放时间拒绝与诊断误读

源码中存在一个需要修复的调用组合：

- [TaskResultBatchPolicy](../../../kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/result/TaskResultBatchPolicy.java)
  在调用 Worker 事件前采集 JVM 当前时间。
- [Worker Score](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/score/redis/RedisWorkerScoreCore.java)
  读取 Redis TIME 后，若调用方的释放时间早于 Redis 当前槽起点，就给整批返回 INVALID。
  这是现有 Owner 的合法检查，不应简单删除或放松旧 fence。
- [DefaultWorkerExecutionResultEvents](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/worker/DefaultWorkerExecutionResultEvents.java)
  忽略了返回状态；现有 `WORKER_RELEASE` JFR 事件将正常返回的事件调用计入 count，
  它没有证明对应 Worker 已完成释放。

为验证实际发生情况，额外构建了一份未交付的诊断 JAR，只在现有返回路径记录
TRANSITIONED / INVALID / STALE / NOOP 数量和时间落后量；不增加 Redis 读取，
不记录 Worker ID、Score 或 payload，也不改变结果、重试或时间选择。
该 JAR SHA-256 为 `a0ab6a30d4e5f04813e05d74bba53de53ab77e4c79b7233d2395eb11995e597e`，
与普通性能测量产物分开保存，不以它的吞吐作为改进对照。

`isolated4-any2k-release-observation` 的返回状态统计为：

| 阶段 | 释放尝试数 | TRANSITIONED | 因释放时间过旧而 INVALID | 涉及批数 |
| --- | ---: | ---: | ---: | ---: |
| 预热 | 2,000 | 1,990 | 10 | 1 |
| 30 秒测量 | 7,039 | 6,851 | 188 | 2 |
| 补查期 | 33,570 | 33,070 | 500 | 5 |

测量中请求时间最多落后 Redis 当前槽起点 33ms，STALE/NOOP 为零。汇总在该例
`evidence/worker-release-observation.json`。计数是释放尝试，不是唯一 Worker 数，
也不能直接当作失败 Item 数。被拒绝的调用没有执行释放写入，存在延后容量返回的
路径；本轮没有量化它造成的吞吐损失，也没有证明每次拒绝都必然等待完整 5 秒租期。
本轮不更改这一机械契约；后续修复需要覆盖跨槽、过期 fence 和新租约隔离。

## 设施、机制、代码各自的结论

| 类别 | 本轮已建立的依据 | 尚未建立的结论 |
| --- | --- | --- |
| 设施与竞争 | CI 共享四核，HTTP executor 满载、控制流远端调用延迟、锁等待及 GC pause 同时出现；隔离环境 Direct 2k 全部成功 | 未精确分解 CPU 竞争、JVM、HTTP 排队各占多少退化；不能直接归罪 GitHub runner |
| 调度机制 | 单 Task 槽节奏与 100 条预算吻合 1,194 轮/120 秒，充足 Worker 时仍约 1k/s | 不代表多 Task 或所有生产负载的全局容量上限 |
| 实现与观测 | 真实释放返回 INVALID 且被忽略；RPC 注册/轮询有锁等待与重复查询成本 | 尚未完成释放修复或 RPC 优化的独立 A/B，不能把全部低吞吐归给其中一处 |

后续优化顺序应为：先修复已观测的 Worker 释放时间调用问题并让诊断区分尝试与
实际过渡；再针对单 Task 2k 明确设计 Pacer 的预算与资格节奏；随后以控制变量验证
RPC 提交、Result 轮询和共享 HTTP 处理资源的成本。不能将多个 Owner 的 `100`
统一成一个常量，也不能通过绕过调度、放宽过期判断或延长等待来宣称达到目标。

CI 应保留 100 Worker 的快速周转压力场景，并使用现有对齐的 1,000 Worker / 120 秒
场景建立明确的 2k 目标。性能门槛需分别约束实际发压、正确成功吞吐、延迟、
失败与未闭合结果；现有 measurement passed 不能替代这些验收条件。Tracked 后续
观察仍需自己的持续负载与资源证明，不能从一次执行的 RPC 数字外推。

本轮新增内容仅为诊断记录与 Owner 说明。没有变更生产调度策略、资源配置、CI
阈值或 suite 选择，也没有以历史通过代替新的 2k 验收。
