# SMS Reception 0.1.0-preview

Status: current business workload and simulator owner document.

这是 XA Mass 的首个具有真实业务形态的系统验证载体，以接码预览版提供页面和 API。
本模块是业务 Scenario，以普通 Spring Configuration 接入宿主；独立产品部署由后续实际需求决定。
页面和产品 API 创建监听订单，
Java Worker 模拟自有设备的 SIM，通过真实 Prepare、WebSocket、Country Pool 查询、
Task 执行和后续 Outcome 观察完成接码。只使用模拟短信，不连接真实短信供应商。

## 验证目的与演进

让业务尽可能便宜地接入 XA Mass，用真实的订单关联、监听窗口、共享号码、
模板竞争、取消和到期去施加负载，暴露孤立的合成用例不易触发的问题。
模拟的是设备和短信输入；调度、Adapter 传输和 Result 观察必须经过真实平台路径。
评价重点是暴露并解释问题的能力，以及接入和重复实验的成本。

```text
把业务场景跑真 -> 拉起规模 -> 观察故障与性能
  -> 修正并稳定机制 -> 稳定实际调用面
  -> 判断是否形成独立产品 -> 有需要时毕业、剥离
```

这是反复验证的顺序，不是一次通过的阶段门槛。新发现的故障回到对应 Owner 分析和修复，
提炼为可重复的回归证明，再回到业务场景验证。已有验收阈值和失败语义仍按下文执行，
不能用业务侧补偿隐藏平台问题，也不能把未确认结果当成成功。

当前业务模块、同进程 profile 和统一控制台足以支持这些实验。调用面通过实际使用逐步稳定，
无需先抽取通用 SDK、独立后端或产品基础设施。独立发布、运维隔离、鉴权或持久化等
成为具体需求时，再评估相应组织与部署边界；Scenario 模块不承诺独立产品的部署与运营能力。

## 启动与交付

准备 [Preview 运行依赖](../../distribution/server/PREVIEW.md#source-launch)，在仓库根目录运行：

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

打开 `http://127.0.0.1:18500/sms` 申请监听，再到 `http://127.0.0.1:18504/lab`
向所选号码输入短信，回到业务页观察结果。申请、取消和未确认结果的含义见下文；
[前端 Owner](../../frontend/README.md#sms-business-pages) 维护页面刷新、catalog 和本地状态。

[Preview](../../distribution/server/PREVIEW.md) 独占库存参数、端口覆盖、进程退出与精确 scope 清理，
以及 [ZIP 构建和解压运行](../../distribution/server/PREVIEW.md#archive-delivery)。
[Boot](../../server_boot_jvm/README.md#platform-and-preview) 独占 profile、共享 Group/Project 和配置。
SMS 的监听、匹配、取消和结果语义独立；只施加 SMS workload 时，Messages 不产生业务记录。

## 模块和执行路径

| 模块 | 职责 |
| --- | --- |
| `scenarios/sms-reception-jvm` | Spring 场景模块：业务 API、有限监听状态、应用内幂等、有界 Task 提交和统一 Result 观察 |
| [统一前端](../../frontend/README.md#sms-business-pages) | `src/sms/`：工作台、分页记录、业务指标；共享布局与主题，只调用同源 SMS API |
| [Worker Simulator](../../worker_simulator_jvm/README.md#sms-scenario) | 共用 Java Host 的 SMS 场景：SIM 库、模板匹配、全进程去重、Reporter 生命周期及单 HTML 控制台 |

场景通过 [Server 允许的应用服务](../../server_jvm/README.md#worker-and-scenario-assembly)
消费已准备的 Project 目录，不经过平台业务 HTTP。本版不支持多实例订单幂等或重启恢复；
每个 scope 只运行一个启用 Pacer 的 Server。构造器没有资源准备或线程启动副作用。
SMS 关闭先拒绝新命令、停止提交与观察，线程共用最多 5 秒预算，不刷新或重放队列；
平台资源随后关闭。

产品直接调用 `ProjectDirectory`、`TaskCallSubmissionService` 和
`TaskDataService`。提交入口校验完整批次，包括会被同 ID 覆盖的输入；Result 读取也保留数量
和字段约束。复用现有 Task 请求与 Result 类型，不调用 Controller，不读取 Redis 或调度实现。
平台 HTTP Call 复用同一提交服务，独立保留原有即时查询和异步等待。

Host 每个号码对应一个 Worker；身份、库存和 Properties 发布遵循
[Simulator 生命周期](../../worker_simulator_jvm/README.md#runtime-lifecycle)。

```text
应用申请 -> 同一个托管 Task -> Server 有限提交服务
  -> WorkerQuery("worker.country", ["CN"])
  -> Kernel 调度 -> Adapter -> 号码 Worker 建立监听
  -> 初始完整快照返回，命令完成并释放执行 lease
Host 页面／验收脚本输入短信 -> Host 选择唯一获胜监听 -> 原 run 的 Reporter (tag 9)
  -> Adapter -> 平台 Result -> 产品批量读取应用服务 -> 接码页面
取消意图 -> 观察实际 workerId -> 同一 Task 的定向取消命令 -> Host 裁决
```

每个监听 Item 使用 `worker.country` 并独立携带所请求国家，
取消 Item 使用 `WorkerQuery("workerId", 实际Worker身份)`。托管 Task 的 Country 供给由
[Boot](../../server_boot_jvm/README.md#platform-and-preview) 声明，不由 Item 查询推导。输入构造见
[ListenerService](src/main/java/com/xa/mass/scenario/sms/ListenerService.java)，
通用探针消费 Country Pool，定向探针使用独立身份查询。

监听只是 Host 业务状态，不持有 Kernel lease。初始执行成功只表明监听已建立。
Backend 从 `succeeded` Result 的完整业务内容读取接码状态，不从 TaskItem terminal tag
推导接码成功。首次快照使用普通执行结果，最终快照使用后续观察，均包含订单、号码和有效窗口；
后者可以先被读取，迟到的初始快照不能抹去已观察结果。

平台权威契约见 [Server API](../../server_jvm/README.md)、
[Worker 后续观察](../../transport/worker-core/README.md#later-task-outcome-observations)
和 [Result 存储](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)。
本产品没有新增平台 API、Delivery DTO、SDK 公共接口或 Kernel 机制。

## 监听和分发契约

监听默认为 60 秒，允许 1–300 秒，从 Host 成功建立开始。
建立期限为产品受理后的 30 秒。尚未观察到号码时显示“正在取号”，不会捏造已分配号码。

内置模板的权威配置在 Backend `ListenerService.TEMPLATES`，通过 catalog 暴露，
每次建立监听携带配置快照；Host 只解释这份有限模板格式。

| 应用 | 模板 | 优先级 |
| --- | --- | --- |
| A | `[A] ` 后跟恰好六位数字 | 200 |
| B | `[B] ` 后跟恰好六位数字 | 100 |
| C | 非空短信全匹配 | 0 |

同一号码的所有有效监听参加一次裁决：模板优先级降序、建立顺序升序、监听 ID 升序。
只有获胜监听收到短信，首条命中即终止，其他监听继续。没有有效监听、没有匹配或到期后到达
的短信不会上报接码结果。相同短信 ID 在整个模拟进程只处理一次，重复输入不能转交其他订单；
同 ID 的不同号码或正文明确冲突。匹配、取消、到期共享号码状态锁，发送与回调在锁外。

创建接口以 `applicationId + requestId` 幂等；相同内容返回原订单，不同国家或时长复用
同一 ID 返回 409。Host 以监听 ID 全进程去重；重复 Task 即使落到另一副本，也返回原监听
当前完整快照，不创建第二个监听、不换号码、不把原 Reporter 转给新 run。

取消先表示意图。建立期间取消，Backend 等待实际 Worker 归属后发送定向 Task；
取消使用 `workerId` 查询函数，不等待目标重新进入 Pool；监听创建仍使用原 Pool 查询。
本地队列尚未接纳取消意图时，后续观察轮次可以继续尝试本地准入。已实际提交的命令不由
Backend 自动重发。Host 确认前保持“取消中”；短信已获胜时不能改判取消。
已过有效窗口的取消按明确到期报告。

Reporter 发送失败仍结束获胜监听，不降级转发、不重放短信。Host 终止监听时清理 Reporter。
进程退出丢弃活跃监听，不进行恢复。后续观察是 best-effort；本地发送接受不是平台 ACK。
Backend 只重读 Result，不修补平台事实，不保证最终到达。

观察截止为：产品受理时间 + 30 秒建立期限 + 请求监听时长 + 15 秒宽限。
截止前没有明确业务终态，显示“结果未确认”；不会改成到期或接码成功，停止观察该订单。
明确终态有 `RECEIVED`、`CANCELLED`、`EXPIRED`、`REJECTED` 和 Host 终止状态。
提交或读取失败分别计数，不隐去不确定性。

## API 和有限资源

所有端口只绑定本机。此预览无应用登录、计费、模板编辑或第三方供应商协议。

| 接口 | 请求与行为 |
| --- | --- |
| `GET /api/v1/sms/catalog` | 应用、模板、国家池、Task ID、本轮 ID 和限制 |
| `POST /api/v1/sms/listeners` | `{requestId, applicationId, country, listenSeconds?}`；受理后返回业务记录 |
| `GET /api/v1/sms/listeners?offset=0&limit=30` | 创建顺序分页，limit 为 1–1,000，返回 total 和 items |
| `GET /api/v1/sms/listeners/{id}` | 号码、窗口、状态和已观察短信 |
| `POST /api/v1/sms/listeners/{id}/cancel` | 记录取消意图并返回当前状态 |
| `GET /api/v1/sms/metrics` | 申请、各状态、错误、队列、建立和短信观察延迟 P95/P99 |

旧产品路径不保留别名。静态与实时 OpenAPI 的范围见
[Boot 页面契约](../../server_boot_jvm/README.md#pages-and-configuration)。
产品异常处理局限于自己的 Controller。本预览尚无登录体系，同源部署本身不是订单授权或租户隔离。

模拟控制仅由统一 Worker Simulator 的独立端口提供；产品 Server 不代理这些请求。
[Host Owner](../../worker_simulator_jvm/README.md#sms-scenario) 维护 `/lab` 页面、
`/lab/v1/sms/*` 查询／流量接口、统一设备输入和号码启停规则。Host 直接使用 Worker SDK，
与默认 Lab 共用主类、Manager 集合及 HTTP 控制服务，无独立产品模拟器模块。

Host 页面始终开放通用库存、设备输入和启停控制，额外展示短信记录和自动流。
单条短信通过稳定文件坐标的 `:inputs`、`sms.receive` 输入到指定 Sim；可选 phone
由 SMS Owner 在准入栅栏内核对，换号后的旧地址不在 Harness 中预先过滤。
phone/country 热修改先持久化再更新本地快照，最后通过 SDK 上报；换号本地结束旧监听，不合成远端结束报告。
功能验收独立读取 Adapter、Matching，验证新国家桶的实际执行者和 Host 重启后恢复。
停止号码关闭本地监听准入并清理 Reporter，不发送产品取消命令或合成结束 Report。
重启保持身份和本轮去重记录，不恢复旧监听；原订单缺少结束证据时仍显示“结果未确认”。
本地运行状态、实际 Adapter 网络证据和 Kernel 可调度性分别归各自 Owner；页面不能混同它们。
Host 与产品各自使用同源 API，启动器提供两个页面地址，不需要浏览器跨源控制代理。

短信模拟接口只向 Host 输入原始刺激，不伪造 Report、不写 Result。
自动流使用固定随机种子 20260909，均匀选择本轮号码，独立产生 A/B/无关短信，正文不含目标订单 ID。
速率 1–1,000/秒、时长 1–300 秒、单次最多 100,000 条；不承诺定时器在过载时维持目标速率。

每号码最多 64 个活跃监听；Backend 和 Host 各最多保留 50,000 条监听记录；Host 全进程
最多保留 100,000 条短信去重指纹。达到限制明确拒绝新输入，已有监听继续，不驱逐去重记录。
产品按国家聚合提交，每批最多 100 条，8 个命令批次并发、每国家 256 个等待槽。
共享应用服务只提交，不占用 HTTP 等待注册；单条命令保留独立身份和 Worker selector。
每 100ms 一个统一观察轮次，按国家轮转，每次最多 500 个订单及其取消 ID；
每次 Result 读取不超过 1,000 ID，没有每订单轮询线程。
Host 使用一个过期/自动流时钟和一个有界 HTTP 执行器。

产品读指标是各自进程的采样，不是跨进程原子快照。建立延迟统计从 API 受理到首次观察到
号码，短信观察延迟从 Host 接收时间到 Backend 观察终态；只统计可观察样本。
Backend 活跃数量只包含已观察到建立且尚无终态的监听；建立阶段的取消意图不计入活跃。

## 检查与验收

在仓库根目录运行：

```powershell
.\gradlew.bat :worker_simulator_jvm:test :scenarios:sms-reception-jvm:test
.\gradlew.bat :server_boot_jvm:test :server_boot_jvm:smsCompositionIntegrationTest
python -m unittest discover -s scenarios/sms-reception-jvm -p 'test_*.py'
python scenarios/sms-reception-jvm/run_acceptance.py --build --scenario functional
python scenarios/sms-reception-jvm/run_acceptance.py --scenario lifecycle
python scenarios/sms-reception-jvm/run_acceptance.py --scenario concurrency
```

[Boot 组合证明](../../server_boot_jvm/README.md#build-and-verification) 阻断平台 Task 提交／Result
查询 HTTP，验证 SMS 经应用服务和真实 Worker 完成执行与后续观察；单元测试覆盖构造无副作用、
失败启动清理、产品先于平台停止、退出拒绝和依赖边界。
[前端检查](../../frontend/README.md#verification) 和
[发行检查](../../distribution/server/PREVIEW.md#verification) 各归其 Owner。

Runner 使用共享 proof inventory 工具预先物化 CN/US/GB = 1/1/1 或 700/200/100 的精确
SMS-only 库存，保留号码、国家顺序及文件坐标，显式 `app_count=0`。不挑选 seed 或修补配额；
Host 重启复用相同库存和 scope。Server 仍使用 Boot 的完整 preview 装配。

功能 world 为每国家 1 个真实连接的 Java Worker。依次验证无监听、A/B/C 共号、优先级、
相同优先级顺序、幂等冲突、短信去重、普通 Task 重复执行、无匹配、取消、窗口外和有限自动流。
额外的 JVM 用例证明两个副本竞争同监听、匹配与取消/到期竞争、上报失败不转发、清理 Reporter、
有界容量和晚到快照。Host 只读分页证据 `/lab/v1/sms/records` 提供监听状态、短信 ID 和本地发送接受位，
不暴露 Reporter、不作为 Backend 的结果来源。

功能场景还从实际 Worker 读取事件快照，再在 CN 同一号码保持监听期间，通过同 Group 的
有限 Task 分别使用 Country CN Pool、`workerId` 和 `worker.phone` 定向查询执行字符串事件，
并以独立的 Country ANY 查询验证通用字符串调用，随后验证短信结果。号码查询通过 Preview 实际启用的独立索引，SMS-only 库存没有 Messaging
启用属性。该场景不阻断补货；无 Pool／无预先租约的证明归 Runtime Boundary。这只证明同一 Worker 承接两类
Task，不证明公平性或容量。独立 `lifecycle` 场景验证停止、身份稳定的重启、去重保留以及
新监听成功；旧监听的本地 INTERRUPTED 与产品 UNCONFIRMED 分别记录，不计入正常流的状态不一致。

功能场景随后热修改同一 Worker 的号码和国家，独立读取 Adapter 缓存、Matching 事实，并用
后续实际接码 Worker 证明号码索引与国家桶归属改变。Server 私有访问日志仅记录方法、路径和状态码，
校验初始文件批量 Prepare 以及热修改期间 Prepare 调用增量为零。重新启动 Host 时复用同一
库存与 scope，验证文件编辑、Worker 身份和新号码继续有效；不恢复旧监听或 Reporter。
重启时显式组合 execution-witness 能力，验证同一个产品 Worker 仍能处理 SMS，并执行
所选验证 Handler、产生独立 Host 执行证据；不额外启动一批 Lab Worker。

固定并发 world 为 1,000 个 Java Worker（CN/US/GB = 700/200/100）。完成身份和真实路由
验证后，以 200 次监听申请/秒持续 60 秒，叠加最多 135 秒、300 条短信/秒的有限随机流。
每次申请监听 60 秒；有限业务结束后比较 Host 和产品观察的状态及获胜短信关联。
验收要求 12,000 次申请均被接纳并建立，无虚假成功、无遗漏的 Host 已命中短信、无状态
不一致和未确认结果；报告 HTTP 错误、发生器限制、实际速率、建立吞吐、短信观察率、
P95/P99、活跃监听峰值及 Server 和 Host 两个 JVM 的 RSS/线程峰值。到期不算接码成功。

当前验收写入产品目录的 `build/acceptance/`，仅 `summary.json`、`summary.md` 和安全归档检查用于 CI 汇总；
private 日志不进入 CI 工件。源码验收使用 `--build`；`--root <解压后的统一 Preview 目录>`
按 [打包验收契约](../../distribution/server/PREVIEW.md#verification) 加载 ZIP 产物，
不回退源码或在 ZIP 内构建。摘要记录实际加载的启动脚本指纹。
`.github/workflows/sms-reception-preview.yml` 运行产品单元测试、统一前端检查、同进程组合边界、小规模真实链路以及解压 ZIP 后的真实功能链路；
functional、lifecycle 和 ZIP functional 分别使用 18400、18420、18440 作为 Server 基址，
Adapter 为基址 +3，Host 为基址 +4，避免连续阶段复用同一组端口影响启动准入。
保留现有 [Proof Selection](../../TESTING.md) 的平台规则。

本场景证明有限业务、Country Pool 与 Phone Index 闭环，不声明平台容量上限；不证明真实设备收信、
app 索引、黑名单、可靠投递、租户隔离或业务订阅的重启恢复，也不扩大既有平台容量场景。

先按 [Preview 交付说明](../../distribution/server/PREVIEW.md#archive-delivery) 构建、检查并解压新 ZIP，
再从仓库运行相同业务验收：

```powershell
python scenarios/sms-reception-jvm/run_acceptance.py --scenario functional --root <解压后的统一Preview目录>
```

真实浏览器验收使用同一 ZIP 页面完成申请、Host 原始短信输入及结果观察，
并检查 Runtime 切换、页内标签、深浅主题和窄屏布局。

## Project ownership

Listeners use the `sms` Project/Group managed Task prepared by
[Boot composition](../../server_boot_jvm/README.md#platform-and-preview).
The scenario only reads that directory; it never registers Groups or Projects.
