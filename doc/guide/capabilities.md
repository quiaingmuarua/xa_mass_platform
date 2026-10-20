# 能力与实测范围

Status: current capability guide with explicitly dated evidence.

当前可提供的功能见[指南首页](index.md)。下面保存一份可复核的性能与恢复证据，
用于理解已执行的 workload；它不是当前 checkout 的新测试结果，也不是生产 SLA。
产品能力、配置验收规模、实测吞吐和期望目标必须分别表达。

## 固定证据快照

本节数据来自北京时间 **2026-10-20 03:39** 的
[Worker Call Performance run 39310825184](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/39310825184)，
被测 commit 为 `2699da1e321b38d5d622f91ac07d2d9c11505834`。
数值固定引用 [run-record.json](https://github.com/quiaingmuarua/xa_mass_platform/blob/e69d5f57e597a3e7ac2268a31b897386a0b32dcc/runs/20261019T193906Z-39310825184-1.json)，
`laneConfigVersion=3`。更新数据时必须一起更新日期、commit、配置与不可变证据链接，
不能仅替换表中的最大数值。

以下性能测量使用 `DEFAULT` Pacer preset，并有 **lane 专用配置覆盖**：

| 配置 | 该版本生产默认值 | 本次 lane 值 |
| --- | ---: | ---: |
| `xa.mass.kernel-pacer.assignment-batch-limit` | 100 | 1,000 |
| Adapter `report-queue-capacity` | 1,000 | 10,000 |
| `xa.mass.task-rpc.max-probe-items-per-round` | 256 | 1,000 |

各 Group 的 Any 补给目标也显式设为 1,000。完整覆盖、审计理由和配置生成逻辑见
[被测版本 runner](https://github.com/quiaingmuarua/xa_mass_platform/blob/2699da1e321b38d5d622f91ac07d2d9c11505834/integrations/worker-call-performance/run_worker_call_performance.py)，
测量边界见[同版本 Performance Owner](https://github.com/quiaingmuarua/xa_mass_platform/blob/2699da1e321b38d5d622f91ac07d2d9c11505834/integrations/worker-call-performance/README.md)。
`DEFAULT` 在此指策略 preset，不能把这些成绩当成未调整配置的默认部署能力。

### 深 backlog 完成吞吐

环境为 4 个逻辑 CPU、Java 21、Redis 7.4.10，以及上述 Pacer/Runtime 配置，
两个 Group 各 4,000 Worker，总计 8,000。每 Group 在批准前预置 600,000 Items，
每轮热身 15 秒，再测 30 秒；每条路径独立运行三轮，均为 `passed`。

| 路径 | 三轮中位数（完成 Items/s） | 最小值 | 最大值 |
| --- | ---: | ---: | ---: |
| Any Pool | 9,773.27 | 9,650.03 | 10,040.00 |
| 指定 Worker | 10,155.37 | 10,000.00 | 10,560.00 |

完成口径是测量窗口内成功 `RESULT_STORED` Owner 调用返回事件，结合完整生命周期
claim/publication/store 与唯一、有效的导出结果对账。它不是 Redis 精确提交时间，
也不是 HTTP 客户端实际收到答案的速率。测量期间没有继续提交 HTTP 输入。

### 每秒 2,000 次到达的调用

此组使用 2,000 个 Worker，**两个 Group 合计**每秒 2,000 次请求。
下表为各路径三轮指标的中位数，不是把三轮原始请求合并后重算的 percentile；
三轮状态均为 `passed`。

| 路径 | p50 响应延迟（ms） | p99 响应延迟（ms） | HTTP 成功响应比例 |
| --- | ---: | ---: | ---: |
| Task Any | 136.43 | 245.46 | 1.0 |
| Task 指定 Worker | 132.29 | 240.84 | 1.0 |
| Direct Call | 4.74 | 28.33 | 1.0 |

成功响应比例不等于严格的 1 秒 deadline 达成率。
open-loop 的 `completedPerSecond` 使用结束后 drain 所得成功结果数除以到达窗口时长，
不能解释成“所有结果都在那个窗口内完成”。Direct Call 是投递对照路径，
没有 Task/Item 调度语义，不能用它的延迟替代任务延迟。

## 恢复与稳定性证据

同 commit 的 [Worker Loaded Recovery run 39301111034](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/39301111034)
成功执行了其固定场景。该 lane 的配置为准备 15,000、收缩后保留 10,000 Workers，
初始/保留收敛门槛 14,800/9,900，40 个 Task、共 200,000 Items，
以及一次 TERM、两次 KILL 的 Server 恢复过程。

这些数字是该场景的配置和验收门槛，**不是从运行中测得的最大承载量**。
成立的是该故障序列下命名工作、完整结果、状态与资源证据满足断言；
不扩展成任意 Worker 数、任意故障或无限持续运行保证。
详细断言仍以 [Loaded Recovery Owner](../../integrations/worker-loaded-recovery/README.md)为准。

## 比较数字前先看状态

| 状态 | 阅读方式 |
| --- | --- |
| `passed` | 该配置下证据有效；结合重复次数和 spread 读数值 |
| `saturated` | 到达触及 in-flight 上限；`admittedPerSecond` 是准入速率，不是完成吞吐上限 |
| `invalid` | generator、Worker 余量、backlog 或测量证据未满足前提，不能拿数字做性能比较 |
| `failed` | 正确性底线或 fast-fail 触发，需要查看具体失败证据 |

run-record 的 median/min/max 只统计 `passed` 轮次，必须同时看 `n` 和状态数量。
不同 Worker 数、配置 hash、测量版本或非 reference host 的记录不能直接混成一个
“平台 TPS”。nightly trend 是回退提示，不自动给出生产容量结论。

## 不从这些结果推出什么

- 不声称生产系统稳定提供 10,000 TPS。另一个 local capacity experiment 的 10k 是验收目标，
  要求五轮 Any 及各轮全部 30 秒子窗口满足条件；它不是这份 nightly 的通过条件。
- 不声称业务 Handler 的外部服务、Android 真机或真实短信具备相同吞吐和延迟。
- 不声称大量活跃 Task、任意 Group 分布、严格公平或多租户隔离已经得到证明。
- 不把源码中存在测试、最新工作区或文档构建成功当作这些运行结果的延续。

实际部署使用自己的 Handler、资源与工作集按
[Performance Owner](../../integrations/worker-call-performance/README.md#world-and-configuration)
建立可比证据；正确性选择仍走[验证入口](verification.md)。
