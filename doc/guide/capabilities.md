# 能力与实测范围

Status: current capability guide with explicitly dated evidence.

当前可提供的功能见[指南首页](index.md)。下面保存一份可复核的性能与恢复证据，
用于理解已执行的 workload；它不是当前 checkout 的新测试结果，也不是生产 SLA。
产品能力、配置验收规模、实测吞吐和期望目标必须分别表达。

<a id="external-public-closeout"></a>
## 外部公网验收收尾

### 2026-11-19：4.4.3 传输观测完成，发压问题继续定位

保留原 10k、三档速率和判据的
[诊断运行 44333272615](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44333272615)
仍为 3 passed／3 invalid／3 saturated，2794 个 attempted/HTTP-zero 请求未知，未重放。
自动撤销在 8131ms 完成原 10,000 身份的完整扫描；独立核对确认本轮进程、凭据已清理，
常驻 20 connected、原 Host 与 Runtime 进程未重启。

该轮有私有客户端／Server JFR，属于诊断，不能作为无采集干扰的性能比较。
高档窗口的客户端 CPU 样本集中在 TLS 握手密码计算，TCP 连接数由 1465 增至 4104；
尚不能把整个路径的 saturated 归为 Server 容量上限。独立 TLS 控制观察到后续 11/11
连接成功恢复会话，因此不调整 TLS 会话缓存。

4.4.3 为共用 HTTP 客户端启用 HTTP/2，并修正完整响应超时边界，补充安全的阶段与协议计数；
workload、warmup、5 秒预算、Oracle 和退出状态保持不变。
完整手动 Proof 的 17 个 job 通过后发布了不可变 Release，GCP 仅平滑 reload 入口；
随后未使用 JFR 的正常
[10k 运行 44353588495](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44353588495)
产生 **9 invalid**：6 个 generator-lag、3 个缺少合格 Worker 采样。75,019 个完整正式响应
均为 HTTP/2，已取得对应成功 Result；另有 3,061 个未知响应、26,920 个 not-sent，
正式 429 为 0。500/s 的到达调度 p99 小于 11ms，执行派发 p99 为 198–232ms。
因此 HTTP/2 协商成功并不表示发压节奏已修复，也不证明始终只用一条 TCP 连接。
自动撤销在 8114ms 确认原 10,000 个身份为 unknown；独立核对确认本轮进程和凭据清理、
常驻 20 connected，原 Host 与 Runtime 进程保持。继续定位派发和连接换代问题；
不重放原请求、不修改判据，不把 Actions 成功当作性能有效。
后续同版本 Nginx 1.24 的有效本地对照，以原 4.4.3 客户端、4 CPU、ECDSA 和双向各
175ms 延迟，仅比较管理员连接复用预算 1000／100000。派发 p99 为 81.49／0.085ms，
FD 峰值为 1647／90，正式测量新建连接数为 1519／0，两组正式 5000 个结果均成功；
低预算 warmup 的 22 个 unknown 另行保留。这支持 4.4.4 入口复用候选，
尚不表示已修复 GCP 的全部延迟。原不兼容本地 Nginx 等无效对照不进入比较结论。
定位数据和边界见[2026-11-19 观测](../../integrations/worker-call-performance/observations/external/2026-11-19.json)。

### 2026-11-18：4.4.2 的 10k、空闲与受控恢复验收已完成

不可变 [4.4.2 Release](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.2)
来自 `ccf676801663f953169f2d03058cbe7236a01123`；
[完整手动 Proof 44293971501](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44293971501)
attempt 1 的 17 个 job 全部成功。制品摘要和发布记录见
[4.4 Release 说明](../../distribution/server/RELEASE-4.4.md#442-consumer-closeout-patch)。

Ali 的常驻 Host 已更新，20 个 Worker 身份及配置／凭据／库存指纹保持不变；
安装后的有界核对看到了 20 个 TCP keepalive Worker sockets 和 GCP 的 20 connected。
按需 Performance kit pins 也已选为 4.4.2。目标 GCP Runtime 仍为 **4.4.0**
（`b28a87ac918a34ffabb4feb8c63a29998a0610db`），没有随 kit 发布升级。

单次默认 10k、重复 1 次、包含所有既有 rate 的
[Actions 44297177821](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44297177821)
已成功结束；源码 Nightly jobs 按 external 选路正常跳过。九个正式测量均有结果：
500/s 三路 passed，1000/s 三路因 generator-lag invalid，2000/s 三路 saturated，
后者的 admitted rate 为 1287.7–1618.5/s。正式请求观测到 118,789 个成功 Result、
15,651 个 not-sent、0 个 HTTP 429；另有 **560 个 sent/HTTP-zero unknown** 保持未知，
没有重放。进程成功不使 invalid 数据变为可比较，也不表示所有计划请求都成功。

自动 cleanup 在原 30 秒预算内，以最多 8 个并发请求完成一次 100 页 × 100 IDs 的完整
扫描，耗时 8,048ms，10,000 个原身份均为 unknown，因此按既有 Owner 契约 confirmed。
拥有的进程及临时凭据已清理；准备态 300 秒 timer 已确认 armed，之后停止。
独立 operator 核对保留原始状态，看到 campaign 无 Java 进程／临时凭据、10,000 unknown，
常驻 20 connected。四份公开 artifact 的格式凭据检查为 0，且与私有 10,000 Worker IDs
的完整身份交集为 0；这只是该次公开投影的有界检查。

同轮 GCP 资源辅助记录只有后段 49 个样本、480 秒窗口（11:50–11:58 UTC），
其中包含 128 秒收尾空闲，不能作逐 case 资源归因或容量证明。Ali 四核 consumer 在
2000/s case 的 Harness 平均消耗约 2.75–3.272 核、峰值约 3.85 核，只支持 generator
受限方向，不能据此推出 Server 上限。完整四轮原始状态、计数与边界见
[脱敏观测](../../integrations/worker-call-performance/observations/external/2026-11-18.json)。

常驻 Host 从 11:42:07.733 UTC 起完成 4,500.018 秒（75 分钟）空闲观察，
进程没有重启，原 20 个逻辑身份及配置／凭据／库存保持不变，结束后 GCP 确认
20 connected。最大采样间隔为 5.1201 秒；观察到两次 TCP tuple 变化，其中一次为
接近一小时处的换连，原 20 个物理 socket 到 elapsed 3,558.77 秒时已全部替换。
因此这是有采样边界的连续运行与恢复证据，不能写成“75 分钟零瞬时断线”。

随后只执行一次该 Host 用户到 GCP 443 的出站 DROP，申请 120 秒，由独立清理机制
在 120.702 秒时移除，计数 620 个丢弃包。故障期间原 20 个 socket 均消失，
82.424 秒时首次观察到 replacement；恢复后本地 20 个新 TCP 在 3.951 秒内出现，
独立 GCP 观察在恢复后 **59.619 秒**确认 20 connected，满足声明的 180 秒有界目标。
进程没有重启、原身份保持不变；规则最终不存在，没有手工删除来补验收结果。

这些结果仅覆盖本次 Ubuntu／Java 21 环境、一次故障及其观察窗口；不推出 Android、
SOCKS、无限重试保证、所有半开路径、完整容量或长期 SLA。持久 consumer 的脱敏证据
与四轮调用观测分开保存在同一 JSON，既有失败和 unknown 不改写，也不表示实时健康。

### 2026-11-18：4.4.0／4.4.1 外部 Performance 观测

手动 Actions 已交付从 GitHub-hosted runner 经 SSH 调度 Ali consumer 的路径：
consumer 只运行已发布 kit，不构建源码；目标 GCP Runtime 仍为 **4.4.0**
（`b28a87ac918a34ffabb4feb8c63a29998a0610db`）。4.4.1 kit 独立固定为
`60752f8b51bdc9d291bcbde53de97d65592cd984`。下表是已经结束的有限观测，
不是当前主机健康状态，也不替代下节不同 workload 的 Loaded Recovery 结论。

| 运行 | Kit / Worker 数 | 正式测量 | 原始 runner 与清理结果 |
| --- | --- | --- | --- |
| [44265176327](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44265176327) | 4.4.0 / 10,000 | 9 个 case：1 passed、8 invalid；49,170 个 HTTP-accepted 调用取得成功 Result | runner failed；连接清理 `URLError/unconfirmed`，原始状态保留 |
| [44270827560](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44270827560) | 4.4.1 / 2,000 | 9 个 case：3 passed、5 invalid、1 saturated | runner passed；2,000 disconnected confirmed；丢弃的 job warmup 仍有 372 个 failed Result |
| [44275882139](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44275882139) | 4.4.1 / 10,000 | 9 个 case：500/s 三路 passed，1000/s 三路因 generator-lag invalid，2000/s 三路 saturated；119,892 个 HTTP-accepted 调用取得成功 Result | runner failed；进程及临时凭据已清理，连接清理仍为 `URLError/unconfirmed` |

完整脱敏聚合、各 case、配置指纹和原始四份 artifact 的 SHA256 见
[2026-11-18 外网观测](../../integrations/worker-call-performance/observations/external/2026-11-18.json)。
最后一轮仅调整了该环境的管理员入口限流：每秒 300 → 3000、burst 600 → 6000；
这是有配置指纹的运维变更，不是产品默认值或容量建议。该轮正式请求未观测到 429，
仍有 **266 个已发送但未收到 HTTP 响应的请求**；它们保留 unknown，不重放，
不能从 accepted Results 全部成功推断这些请求也已成功。

以下边界仍需保留：

- 原始连接 cleanup 未确认，与后续 operator 观察分别记录。最后一轮的独立核对看到
  campaign 无 Java 进程、临时凭据不存在、GCP 上游 WebSocket 为 0，网络投影为
  10,000 unknown；这不把原始 cleanup 改写成 confirmed。
- persistent20 的服务与 Lab `RUNNING` 曾同时存在，但 GCP 网络投影为 20 unknown、
  无相应上游连接。半开连接问题尚未在该轮修复；进程存活不等于可执行任务。
- 2k job warmup 的 372 个失败保持原样。一次 32 项有界只读诊断排除了这些样本的
  TTL 分支，指向 claim 预算耗尽；没有持久分配账本可证明离线历史 Worker 的因果。
- invalid 不可作性能比较，saturated 给出准入速率，不是 Server 极限 TPS。
  4.4.2 的新 10k 自动清理及本次常驻连接有界验收已完成，后续结果不改写上述原始记录。

### 2026-11-13：Loaded Recovery 基础档收尾

**2026-11-13 收尾决定：以已经通过的 1,000 Worker 作为基础验收，不再继续追高。**
被测 Runtime 与 Simulator kit 为 Release **4.2.0**，源码提交
`aaed8d648c19e2f6332f37b4e831b0b194d5553b`。以下是该版本、配置和运行日期的证据，
不是当前环境的实时健康状态、Server 容量上限或长期 SLA。

| 公网直连配置 | 已完成工作 | 结果 |
| --- | --- | --- |
| 1,000 → 999 Worker（当前基础档） | 两阶段各 10 Task × 5,000 Items，共 100,000 个结果与完整导出 | `diagnostic-passed`，2026-11-12 |
| 历史 100 → 99 Worker（已移除手动 CI 档位） | 两阶段各 10 Task × 5,000 Items，共 100,000 个结果与完整导出 | 仅保留历史 `diagnostic-passed`，2026-11-12 |
| 原 15k → 10k 目标及直接公网 10k 对照 | 建连阶段未通过，没有对应的完整工作量结果 | 未通过，不以小规模结果替代 |

两档成功运行来自本地外部 Java Host 经公网 HTTPS/WSS 连接 GCP，使用同一发布 JAR、
`default` Project、专用测试 Group、MD5 Handler 和 Any 供给/查询；没有 SSH 转发。
最高已通过的直连档位是 1,000 Worker。启动请求确认数不等于稳定连接数，多个独立运行
也不能简单相加成一次更大的同时在线验收。SSH 隔离对照仅保留为诊断，完全不计入上述验收。

排查已经得到以下边界，后续 Agent 应复用这些证据：

- 免费 GitHub x64 与 ARM Runner 均复现 TCP 建连超时。两轮分别使用连续的 4,096 个
  公网源端口；未应答 SYN 未到达 GCP。它支持出口 SNAT 分配限制的方向，**不是读取到了
  GitHub 内部配额，也不是所有 Runner 永久只有 4,096 连接的声明**。
- Runner 的 FD 上限为 65,536、临时端口为 10000–65535；实际进程 FD、CPU、内存未耗尽。
  GCP 同期也未见主机资源饱和、监听溢出或主机防火墙连接限额触发。
- 本地扩大端口、真实 IP 解析对照、Windows/WSL 双端口探针和三点抓包均已执行。
  最后一轮 1,270 个未到 GCP 的 SYN 身份均有物理 WLAN Tx 的正向匹配。
  **Tx 只证明到达网卡发送观测边界，不证明无线链路或路由器已经收到**；具体中间设备
  仍未定位，不能认定 TUN、Wi-Fi 信号或 GCP 算力就是根因。云控制面防火墙读取也受权限限制。
- 出口设备无法管理；多 Runner 需要身份分片、共同阶段控制和结果汇总，不是现有工具的
  简单配置。本轮不引入分布式验收、额外机器、重试放宽或更高负载。

完整配置、日期、制品/证据指纹、失败轮次与清理范围见不可变的
[收尾报告](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/closeout.md)、
[结构化结果](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/closeout.json)和
[证据索引](https://github.com/quiaingmuarua/xa_mass_platform/blob/69773ada6b5bd6c67d3d5ee2b2adf6c5269c5db2/external-release-load/20261113-v4.2.0-closeout/evidence-index.json)。
旧报告按日期保留；其中早期 TUN 归因、待扩大端口等描述不能覆盖本次结论。
索引区分公开聚合证据与仅保留哈希的本地原始抓包，不把哈希当作公开原始数据。

手动 CI 仅以 1,000 档作为默认基础档，保留发布工具的 `diagnostic-only` 标识；
100 档已移除，不再提供或推荐为新验收入口，其结果只作为历史证据保留。
15k 完整模式仍可显式选择，原断言不变。已通过的是上述本地公网运行，
**不据此声称新的 1,000 档 CI 入口已经在 GitHub 执行通过**。收尾只验证参数接线和文档，
不重新运行远端负载。命令见 [TESTING](../../TESTING.md#external-release-load)。

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
