# Task ANY 2k：100 / 1,000 调度上限对照

新的实例上限已经贯穿 Item 检查、按需候选补货和下游资源操作。真实 Redis 证明了
超过 100 个身份可以完成候选化、Matching、描述读取、租约、claim 和 Command 发布。
但本次性能测量**没有证明稳定的 2,000 次成功调用/s，也不能给出可信的提升比例**。
六次无 JFR 对照都出现发压受限；较大的上限没有消除候选不足、HTTP 排队和 RPC
等待表竞争。默认值保持 100。

## 版本、环境与证据

- 当前分支 `main`，基准 HEAD 为 `8ab6826fe5272539fb380d471a84ecbfd058f12e`，
  带本次未提交的调度上限改动。测量开始时 tracked diff 的 SHA-256 为
  `069840aac3c386c3e10c2fabbad37b4300d6decb193ff9cca024ef0702aa1409`。
- 九次使用同一套新构建的 18 个 Server、Host、Harness JAR；逐一 SHA-256 相同。
  Server JAR 指纹为
  `bdd028d8dec3c9ae95b2fadfe9042c1ade7f431164d9492fe661a61824ddd3eb`。
  后续文档、测试和发行构建不属于这套测量产物。
- 本地 Windows / Ubuntu 22.04 WSL，Java 21.0.12.1，Docker Redis 7.4.10。
  六次对照及前两次 JFR 将全部四类进程固定在 CPU `0,2,4,6`，即本机四个独立物理核。
  这不是 lane 的 Ubuntu 24.04 参考主机，`referenceHost=false`。
- 固定 `rpc-any-2000`：1,000 个真实 Worker，20 秒、100/s 预热，120 秒、2,000/s
  测量；每次计划 240,000 次调用。单次业务等待 1 秒、客户端超时 5 秒、在途上限
  4,096、Task TTL 120 秒、Result 补查上限 180 秒，均未调整。
- JVM 保持 `-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError`；DEFAULT Pacer 的
  50ms 完成后间隔、独立的 100ms Score 时间槽及其他资源配置未变。每次使用独立
  Server/Host/Harness、Redis 容器和测试 scope；测量期间没有并行构建。
- 前六次依次为 `100→1000、1000→100、100→1000`，JFR 关闭。第七、八次分别为
  100 和 1000 的独立 JFR；第九次仅作 CPU 争用诊断，不纳入三组对照。

[安全聚合数据](2026-09-23-assignment-ceiling.json.gz)保存配置、全部产物指纹、
延迟、分阶段速率、Redis 命令成本、资源覆盖和 JFR 聚合；不含消息正文、Properties、
业务回复或 Worker/Item 身份。原始运行数据位于本地
`build/assignment-batch-20260923/`，私有 JFR 和 Host 文件不作为公共工件。

## 六次无 JFR 对照

| 次序 | 上限 | 实际发送/s | 窗口成功响应/s | 成功响应 p50 / p95 / p99，ms | 补查 succeeded / failed |
| --- | ---: | ---: | ---: | --- | --- |
| 1 | 100 | 1,882.00 | 652.28 | 307 / 973 / 1,996 | 148,103 / 77,702 |
| 2 | 1,000 | 1,852.01 | 846.21 | 534 / 1,512 / 2,114 | 188,943 / 33,227 |
| 3 | 1,000 | 1,784.63 | 750.86 | 598 / 1,358 / 2,001 | 180,266 / 33,856 |
| 4 | 100 | 1,809.79 | 470.01 | 471 / 1,698 / 2,481 | 131,740 / 85,201 |
| 5 | 100 | 1,925.75 | 818.88 | 249 / 537 / 1,709 | 162,214 / 68,872 |
| 6 | 1,000 | 1,925.52 | 1,370.87 | 422 / 966 / 1,640 | 214,103 / 16,951 |

六次 `generatorLimited=true`，满足完整发压条件的对照组数为 **0/3**。测量结束时
HTTP 在途数依次为 2,288、2,009、1,933、2,978、1,799、640。第六次后 90 秒达到
2,000/s 的实际发送，成功响应 1,660.53/s；这不能替代整个 120 秒的验收。

九次 runner 均满足其 Result 收敛检查：已受理调用补查后的 `not_observed=0`。
这里的 `passed` 不等于全业务成功，更不等于 2k 容量达标。表中仍有大量 failed
Result；提交结果未知的调用不计入已受理分母，不能把最终成功数除以发送总数来
宣称受理后成功率。原响应未观察到、后续 Result 成功和窗口成功吞吐是不同指标。

对应资源与 Redis 成本如下；命令增量使用测量窗口内首末采样点，实际覆盖约
118.4–119.3 秒，完整边界间隔见数据文件。

| 次序 | Server 平均核数 / 峰值 RSS MiB | Redis 平均核数 | EVAL 次数 / 总秒数 | HMGET 次数 / 总秒数 |
| --- | --- | ---: | --- | --- |
| 1 | 2.60 / 1,453 | 0.266 | 621,931 / 21.41 | 473,586 / 4.71 |
| 2 | 2.64 / 1,495 | 0.264 | 663,807 / 24.23 | 453,445 / 4.82 |
| 3 | 2.65 / 1,504 | 0.260 | 628,531 / 27.18 | 436,593 / 5.39 |
| 4 | 2.68 / 1,441 | 0.245 | 561,388 / 19.52 | 441,536 / 4.13 |
| 5 | 2.40 / 1,485 | 0.288 | 670,252 / 17.61 | 505,794 / 4.04 |
| 6 | 2.45 / 1,470 | 0.305 | 806,855 / 28.46 | 486,869 / 4.91 |

此处 EVAL 包含所有 Owner 的 Lua，HMGET 包含 Lua 内部读取，不能简单相加为总成本。
不同运行的实际发压和成功工作量不同，命令总量更少也不等于单位业务成本更低。

## JFR：扩大上限实际改变了什么

以下是同一 120 秒测量区间内的聚合。不同阶段按各自事件边界计数，窗口边缘的
Command、候选和完成数不要求相等；阶段耗时不能简单相加为端到端时间。

| 指标 | 上限 100，共享四核 | 上限 1,000，共享四核 | 上限 1,000，Server 独占四核 |
| --- | ---: | ---: | ---: |
| 实际发送/s | 1,914.36 | 1,779.46 | 1,936.89 |
| 窗口成功响应/s | 725.43 | 637.43 | 1,238.93 |
| Dispatch 轮数 / 平均耗时 ms | 960 / 48.21 | 488 / 158.66 | 675 / 108.07 |
| Item 原始候选数 → Matching 结果数 | 95,965 → 92,041 | 483,601 → 89,456 | 670,962 → 166,706 |
| HOT 请求行数 → 实际返回候选数 | 121,197 → 91,469 | 1,004,297 → 88,464 | 1,208,924 → 165,924 |
| Worker 执行确认拒绝数 | 0 | 0 | 4 |
| Command append 调用数 / 平均 ms | 940 / 11.23 | 430 / 36.04 | 598 / 21.12 |
| Server 平均占用核数 | 2.57 | 2.75 | 3.66 |
| HTTP active 峰值 / maximum | 200 / 200 | 200 / 200 | 200 / 200 |
| HTTP queued 峰值 | 2,264 | 2,692 | 2,462 |
| RPC Result probe 次数 | 33,240 | 9,120 | 20,876 |
| probe 累计读取 ID / 命中 | 2,875,751 / 87,081 | 1,905,137 / 76,580 | 2,062,360 / 148,795 |
| probe 平均耗时 ms | 2.93 | 10.35 | 4.38 |
| GC pause 总秒数 | 8.88 | 11.63 | 8.02 |

上限 1,000 的共享四核案例每次检查约 991 个原始 Item，但每次 Matching 平均只给出
183 个候选。新的上限已经生效；它没有制造额外可用 Worker。补货按观察到的缺口
请求，不承诺读满，也不会为空读、资格拒绝补扫描。Worker 确认几乎没有拒绝，当前
主要缺口位于 Matching 返回候选之前，不能归因于大量执行 lease 竞争失败。

上限 1,000 的轮次也更长，Command append 是其中一项成本，不能当作全部原因。
该次 Adapter consume 平均 95.17ms、report append 59.09ms；对应 Server servlet
初始处理均值 28.80ms、12.27ms。差额同时包含客户端、网络、CPU 调度及 servlet
外排队，当前证据不能精确分摊到某一队列。

`TaskRpcWaitRegistry.tryRegister` 的超过 10ms monitor 等待事件在两次共享 JFR
中分别为 4,056、7,701；超时路径 `remove` 为 1,868、2,558。HTTP executor 达到
200/200 且有数千排队，与大量 Result 轮询共同构成具体压力。RPC 使用异步
`DeferredResult`，这些数据不能解释成“200 个 HTTP 线程同步等待 1 秒”。栈事件数
也不是 CPU 百分比。

JFR 聚合采集完整，不表示每个抽样 Item 的链路完整。共享两例的 Item trace 均为
`complete=false`：有未执行、未在原等待内观察到以及重复阶段的样本。上限 1,000
时，能确定位置的超时样本中 1,341 个在 claim 开始之前，98 个在 claim 后、Result
写入前；另有 662 个缺失或不明确，不能用前两项覆盖全部失败。

## 资源诊断与剩余问题

第九次只改变角色的 CPU 放置：Server 仍为 `0,2,4,6`，Host、Harness、Redis 共用
额外的 `8,10,12,14`；总可用物理核由四个变为八个。其余参数和产物不变。Server
获得了更多实际 CPU 时间，窗口成功响应从独立共享 JFR 的 637.43/s 变为 1,238.93/s。
这是资源争用影响的诊断信号；两次都发压受限且仅各运行一次，不能报为性能提升比例。

隔离后 Server 平均使用 3.66/4 核、HTTP queued 峰值仍为 2,462；最终有 210,305 个
succeeded、22,118 个 failed、0 个 not_observed。仅给发压器和 Redis 更多 CPU 没有
达到 2k 成功吞吐。第九次 Item trace 还发生 698 个每 key 容量溢出事件、截断 22 个
测量 key，不能以该 trace 形成完整链路比例；未截断的 Owner 批量事件仍提供阶段成本。

这轮确定了三个边界：

1. 固定 100 的处理门槛已经移除。Matching 实际供应和调度轮次耗时仍限制实际工作量；
   配置 1,000 不代表每轮发送 1,000。
2. 共享四核的设施争用会放大退化。隔离后仍存在 Server CPU、HTTP 排队、RPC 全局
   等待表竞争和 Result 重复探测成本，不能将问题全部归于 CI 设施。
3. 当前 Worker release 还有独立的代码与观测风险：Result Policy 使用 JVM 时间，
   `releaseObservedScores` 用 Redis 当前时间槽拒绝过旧的释放时间；
   `DefaultWorkerExecutionResultEvents` 不消费返回状态。`WORKER_RELEASE` 事件只表示
   调用返回，不证明全部实际释放。本轮没有量化这种拒绝，不能用本轮数据断言其占比；
   [此前的归因](2026-09-21-task-any-attribution.md)记录的是此前版本的专门诊断。

本次没有修改 release、RPC、HTTP、调度间隔或 Transport 机制。后续改动应分别验证
容量释放结果和 RPC 注册/移除锁的持有成本，随后在不受发压限制的固定环境复测，
而不是继续增大配置或放宽成功口径。

资源采样覆盖九次完整测量窗口，每角色首尾边界及最大间隔记录在安全数据中；最大
间隔为 1.759 秒，没有 sampler 线程失败或分钟级盲区。RSS、线程、FD 是采样观测峰值，
不能当作严格上限。Redis `commandstats` 还计入 Lua 内部命令，聚合调用数不能当成
客户端网络往返数；耗时同样不应跨外层 Lua 和内部命令重复相加。

## 默认配置回归与验证边界

本地回归使用 Windows Java 21.0.1、Redis 7.4.10 和默认 assignment ceiling 100。
已通过 JVM Owner 构建与测试、259 个 Redis Owner 测试、22 个 Runtime Boundary
测试、9 个真实装配组合测试。新增完整批次证明后来补强了命令数断言，五个案例再次
通过：1,000 个候选仍用一次候选化 Lua，描述与 Facts 读取也没有按旧上限重新分页。

真实进程结果：

| 证明 | 结果 |
| --- | --- |
| Worker Correctness，100 Workers | initial、live-properties、restart 均通过 |
| Dynamic Matching，1,000 Workers / 150,400 Items | 全部阶段及 runner 审计通过 |
| Convergence Health：Task fault | 通过 |
| 源码双产品 functional / pool-selection / lifecycle、App Checks | 通过 |
| 新 Runtime ZIP 与 Preview 归档检查 | 通过 |
| 新 Preview ZIP functional / pool-selection、App Checks | 通过 |
| 统一前端 lint、类型检查、204 个测试和正式构建 | 通过 |
| Docs Contract 与 106 个选路代表路径 | 通过 |

**Convergence Health 的 state 场景首次失败，独立复跑通过。** 首轮在业务工作提交
之前，一名已重新连接的 Phone Worker 持续处于 `recovery`，
`property-restart-hot-scenario-phone-number-workers` 在原 300 秒期限后失败。
第二轮使用新进程与新 scope，保持相同产物、参数和 oracle，完成了 Server 重启、
身份保持及原工作收敛。没有重发首轮业务动作或将首轮结果改为成功。

首轮摘要保留在本地
`build/assignment-ci-20260923/convergence-state/state/evidence/`；独立复跑位于
`convergence-state-repeat/state/evidence/`。总运行清单仍记录首轮失败，因此不能
宣称所有尝试全绿或这个稳定性问题已修复。当前网络事件和 Serviceability 主路径
没有在本次修改；现有机制只从 NORMAL Task 提供 Group 做主动恢复，闲置 Group
不能依靠等待保证恢复。首轮未记录到造成该状态的具体事件顺序，根因仍需另行定位。

Android 模拟器证明按用户要求未运行。本节为本地选路验证，不代表远端 Proof Gate
已经执行或通过。安全聚合数据中的回归清单保留首次失败和复跑结果。

## 复测入口

现有 runner 和手动 workflow 接受 `assignment-batch-limit`，默认 100。使用已经
构建的同一产物，依次传入 `100、1000、1000、100、100、1000`，每次独立 output：

```bash
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite rpc-diagnosis --case rpc-any-2000 --skip-build \
  --assignment-batch-limit 1000 --output-root build/rpc-limit-1000
```

本地非参考环境需显式 `--allow-nonreference-host`。单独诊断增加 `--diagnostics jfr`，
不混入正式对照。上面的通用命令本身不固定 CPU；复现本轮环境还需按本报告同时固定
三个 JVM 和 Docker Redis 的 CPU affinity，不能只限制启动脚本而让 Docker 使用全部核。
