# SMS Reception 0.1.0-preview

Status: current business workload and simulator owner document.

这是 XA Mass 的首个具有真实业务形态的系统验证载体，以接码预览版提供页面和 API。
现阶段 `Product` 表示用来检验平台的业务场景，尚不代表独立的商业产品单元。
页面和产品 API 创建监听订单，
Java Worker 模拟自有设备的 SIM，通过真实 Prepare、WebSocket、ON_DEMAND
Task 和后续 Outcome 观察完成接码。只使用模拟短信，不连接真实短信供应商。

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

当前业务模块、同进程 profile 和独立前端足以支持这些实验。调用面通过实际使用逐步稳定，
无需先抽取通用 SDK、独立后端或产品基础设施。独立发布、运维隔离、鉴权或持久化等
成为具体需求时，再评估相应组织与部署边界；`products/` 的名称本身不触发拆分。

## 启动与交付

构建需要仓库现有 Java 21、Node 22.19+（低于 25）、Corepack 和 Python 3.11+。
运行需要 Java 21、Python 3.11+ 和可连接的 Redis 7。Redis 默认为
`redis://127.0.0.1:6379/15`，可用 `XA_MASS_REDIS_URL` 覆盖。不会启动、升级或停止已有 Redis。

在仓库根目录运行：

```powershell
python -m pip install -r products/sms-reception/requirements.txt
python products/sms-reception/run_preview.py --build
```

打开 `http://127.0.0.1:18390/sms`，模拟器页面为 `http://127.0.0.1:18394/`。默认每国家 20 个模拟号码；首次体验可以使用
`--counts 1,1,1`，方便观察多个订单共享同号。`Ctrl+C` 结束本轮场景。
`--port 18410` 同时将 Server、Adapter 和 Host 切换到
18410、18413、18414。端口被占用时明确失败，不停止占用服务。

构建预览 ZIP：

```powershell
.\gradlew.bat :products:sms-reception:previewZip
```

输出 `build/distributions/sms-reception-0.1.0-preview.zip`（相对此产品目录）。
解压后在包目录运行以下命令，无需 Node 或 Gradle：

```powershell
python -m pip install -r requirements.txt
python run_preview.py
```

ZIP 包含发行模块生成的单一 Server JAR、模拟 Host 和 SDK、平台前端、产品 profile 配置及启动脚本。
SMS 前端单独构建并嵌入该 Server JAR，在 `/sms/` 下提供资源。
产品版本不改变平台版本。`preview-manifest.json` 记录同次构建的版本、HEAD 和 Server、Host 两个 JVM 的产物指纹。
已有本次验收报告时，ZIP 同时携带有限汇总；此前结果保留为历史，不充当本版验收。
启动顺序是 Server 健康且 SMS 注册完成、Host 身份建立、实际 WebSocket 路由验证。
默认端口为 Server 18390、Adapter 18393、Host 18394。
Server 显式启用 `sms-reception` profile，读取 `config/application-sms-reception.yaml`。
该配置集中定义本轮 Server、Redis、Adapter 和 Endpoint；平台默认配置保持独立。
根页面 `/` 仍是平台入口，产品仅提供 `/sms` 和 `/api/v1/sms/*`。
不启用该 profile 时，不注册 SMS API、页面、静态资源、国家 Group 或后台任务。
任一进程退出即结束场景，不自动恢复旧状态。

每次启动使用新的 `test_sms_<UUID>` Redis scope。所有自有进程停止后，退出仅以 `SCAN` + `UNLINK`
清理该精确前缀，保留其他 scope。硬杀启动器可能留下进程和数据；正常退出是清理前提。
日志及本轮 PID/scope 元数据保存在 `build/runs/`，不记录短信正文或 Result 内容。

## 模块和执行路径

| 模块 | 职责 |
| --- | --- |
| `backend` | 普通 Java 业务模块：产品 API、有限监听状态、应用内幂等、有界 Task 提交和统一 Result 观察 |
| `frontend` | 独立 Vue 工程：工作台、分页记录、业务指标；只调用同源 SMS API |
| `worker-simulator` | 独立 Java Host：SIM 库、模板匹配、全进程去重、Reporter 生命周期及单 HTML 控制台 |

[发行入口](../../distribution/server/README.md) 显式导入
[Server 配置](../../server_jvm/README.md) 和 SMS 产品配置，创建一个 Spring 上下文。
`backend -> server_jvm` 为单向依赖，Server 不依赖产品；产品只允许引用批准的服务和 DTO。
SMS 不创建 Redis 客户端、Owner、Pacer、Matching consumer 或 Adapter，复用宿主实例。
平台继续提供完整 Prepare、Worker poll/results、Adapter consume/append 和 Direct Call。
Worker 与 Adapter 均指向 Server 18390。产品服务调用不经过平台业务 HTTP。
每个 scope 仅允许一个启用 Pacer 的 Server；本版不支持多实例订单幂等或重启恢复。

产品生命周期在平台装配之后注册 `sms-cn`、`sms-us`、`sms-gb` 并取得托管 Task ID。
构造器没有注册或线程启动副作用；注册失败使启动失败，不补注册。关闭时先拒绝产品新命令，
停止提交与观察任务，再由宿主关闭平台资源；产品线程共用最多 5 秒的关闭预算，不刷新或重放队列。

产品直接调用 `WorkerGroupRegistrationService`、`TaskCallSubmissionService` 和
`TaskDataService`。提交入口校验完整批次，包括会被同 ID 覆盖的输入；Result 读取也保留数量
和字段约束。复用现有 Task 请求与 Result 类型，不调用 Controller，不读取 Redis 或调度实现。
平台 HTTP Call 复用同一提交服务，独立保留原有即时查询和异步等待。

Host 每个号码对应一个 Worker，每个国家 Group 使用一个 JavaWorkerManager。
SDK 以 CLIENT_KEY 准备身份；Properties 经真实 Adapter 观察进入 Matching，Prepare 不保存完整属性。
Worker SDK 和 Adapter 的原有 HTTP/WebSocket 边界继续保留。

```text
应用申请 -> 产品按国家选托管 Task -> Server 有限提交服务 (ANY)
  -> Kernel 调度 -> Adapter -> 号码 Worker 建立监听
  -> 初始完整快照返回，命令完成并释放执行 lease
Host 页面／验收脚本输入短信 -> Host 选择唯一获胜监听 -> 原 run 的 Reporter (tag 9)
  -> Adapter -> 平台 Result -> 产品批量读取应用服务 -> 接码页面
取消意图 -> 观察实际 workerId -> 同一 Task 的定向取消命令 -> Host 裁决
```

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

旧产品路径不保留别名。平台静态和实时 OpenAPI 保持平台契约；SMS 自身的实时 OpenAPI 只包含产品路由。
产品异常处理局限于自己的 Controller。本预览尚无登录体系，同源部署本身不是订单授权或租户隔离。

模拟控制仅由 Host 的独立端口提供；产品 Server 不代理这些请求：

| Host 接口 | 行为 |
| --- | --- |
| `GET /` | 单 HTML 模拟控制台 |
| `GET /inventory?offset=0&limit=100` | 号码、国家、Worker ID、本地运行状态和活跃监听数；limit 为 1–1,000 |
| `GET /metrics` | Host 匹配、去重、容量和有限流统计 |
| `GET /records?offset=0&limit=100` | 仅供验收的只读分页记录，不暴露 Reporter，不作为产品结果来源 |
| `POST /sms` | `{phone, smsId, text}`，原始短信输入，正文最多 1,024 字符 |
| `POST /traffic/start` | `{ratePerSecond, durationSeconds}`，启动有限自动流 |
| `POST /traffic/stop` | 停止自动流 |

Host 页面对当前号码页轮询，支持短信注入及自动流启停，不提供 Worker 启停或 Properties 编辑。
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
.\gradlew.bat :products:sms-reception:worker-simulator:test :products:sms-reception:backend:test
corepack pnpm@11.9.0 --dir products/sms-reception/frontend lint
corepack pnpm@11.9.0 --dir products/sms-reception/frontend typecheck
corepack pnpm@11.9.0 --dir products/sms-reception/frontend test
.\gradlew.bat :distribution:server:test :distribution:server:smsCompositionIntegrationTest
python -m unittest discover -s products/sms-reception -p 'test_*.py'
python products/sms-reception/run_acceptance.py --build --scenario functional
python products/sms-reception/run_acceptance.py --scenario concurrency
python .github/scripts/check_docs.py
python .github/scripts/check_proof_selection.py
git diff --check
```

发行模块的组合证明创建真实 Server 上下文，主动阻断平台 Task 提交／Result 查询 HTTP 路由，
SMS 仍通过宿主应用服务和真实 Java Worker 完成注册、执行及后续观察。该证明检查共享资源仅一份，
三个页面可直接访问，实时 OpenAPI 同时包含平台与产品路由；关闭 profile 时，产品 API、页面和国家 Group 均不存在。
单元测试另外覆盖构造无副作用、失败启动清理、产品先于平台停止、退出拒绝和依赖边界。

功能 world 为每国家 1 个真实连接的 Java Worker。依次验证无监听、A/B/C 共号、优先级、
相同优先级顺序、幂等冲突、短信去重、普通 Task 重复执行、无匹配、取消、窗口外和有限自动流。
额外的 JVM 用例证明两个副本竞争同监听、匹配与取消/到期竞争、上报失败不转发、清理 Reporter、
有界容量和晚到快照。Host 只读分页证据 `/records` 提供监听状态、短信 ID 和本地发送接受位，
不暴露 Reporter、不作为 Backend 的结果来源。

固定并发 world 为 1,000 个 Java Worker（CN/US/GB = 700/200/100）。完成身份和真实路由
验证后，以 200 次监听申请/秒持续 60 秒，叠加最多 135 秒、300 条短信/秒的有限随机流。
每次申请监听 60 秒；有限业务结束后比较 Host 和产品观察的状态及获胜短信关联。
验收要求 12,000 次申请均被接纳并建立，无虚假成功、无遗漏的 Host 已命中短信、无状态
不一致和未确认结果；报告 HTTP 错误、发生器限制、实际速率、建立吞吐、短信观察率、
P95/P99、活跃监听峰值及 Server 和 Host 两个 JVM 的 RSS/线程峰值。到期不算接码成功。

本轮迁移验收证据写入 `build/server-consolidation/`；后续默认验收写入 `build/acceptance/`，仅 `summary.json`、`summary.md` 用于汇总；
private 日志不进入 CI 工件。`--root <解压后的产品目录>` 使用 ZIP 内真实产物执行相同功能验收。
ZIP 也携带验收脚本，可直接在解压目录运行 `python run_acceptance.py --scenario functional`。
`.github/workflows/sms-reception-preview.yml` 运行产品单元测试、独立前端检查、同进程组合边界和小规模真实链路；
保留现有 [Proof Selection](../../TESTING.md) 的平台规则。

本场景证明有限产品闭环，不声明平台容量上限，不证明真实设备收信、属性索引、国家 Properties
过滤、黑名单、可靠投递、租户隔离或重启恢复，也不扩大既有平台容量场景。
