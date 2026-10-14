# 应用注册查询（app-checks）

Status: current one-shot application check scenario owner.

`app-checks` 是 Preview 的 one-shot 查询场景。创建空的 `CLOSE_WHEN_IDLE`
Task、导入号码、核对启动和关闭是独立操作，复用 Server 的 Task 应用能力。
App/Group、执行事件、供给、查询和契约参数来自模块内 H2 的启动快照。
具名契约由模块代码静态注册；可选窗口 Pool 的名称与属性字段由场景固定声明。[Boot](../../server_boot_jvm/README.md#platform-and-preview)
提供数据库路径和种子资源，宿主继续准备对应 Group、Matching 启用关系和 Worker 能力。
国家只校验号码格式和前缀，不筛选 Worker 国家或证明号码归属地。

已注册和未注册均属于执行成功；失败区间让真实 Handler 抛异常。Kernel、Pacer、
SDK 和 Worker 协议使用既有机制；Matching 基础规则由场景声明装配，没有新增业务 Redis key 或导入后台任务。

## API 和 Task 数据

| API | 内容 |
| --- | --- |
| `GET /api/v1/app-checks/catalog` | 当前生效的 Project、App/名称/Group、国家、单次导入限制及契约 ID |
| `GET /api/v1/app-checks/configuration` | 已保存与当前进程生效配置及内容指纹、待重启/新 scope 提示及契约说明/示例 |
| `PUT /api/v1/app-checks/configuration` | `{expectedFingerprint, configuration}` 整份校验与事务保存；不热更新 |
| `POST /api/v1/app-checks/tasks` | 创建空待审核任务，201 返回 `{"taskId":"..."}` |
| `POST /api/v1/app-checks/tasks/{taskId}/numbers:import` | `text/plain` UTF-8 号码文件；全文件校验后每批最多 100 个 Item |
| `POST /api/v1/app-checks/tasks/{taskId}/results:export?filter=all` | 终态 CSV；filter 为 all、registered 或 unregistered |
| `GET /api/v1/app-checks/tasks/{taskId}/results` | `{taskId, results, truncated}`，最多 100 条业务结果 |

任务列表、基础详情、统计、批准、关闭、目录确认及原始结果/导出使用
[共同 Server Task API](../../server_jvm/README.md#shared-development-caller)。批准请求统一为
`{expectedItemCount}`；待审核核对正数，已批准重复请求 unchanged，终态不重开。
前端组合共同详情、共同统计和业务结果，注册答案仍是内容解释，不从预览推算全量统计。

例如，以开发调用方 `1001` 创建任务时显式携带以下请求头；`MTAwMQ` 是 `1001`
的 UTF-8 Base64URL 编码。自动化或手动调用均须自行声明角色，不由 HTTP 客户端补入。

```http
X-XA-Dev-User: MTAwMQ
X-XA-Dev-Role: operator
Content-Type: application/json
```

创建 JSON 只包含以下配置，不接受 simulation、号码数组、自定义名称、userId 或 role：

```json
{
  "requestId": "check-cn-app-a-001",
  "appId": "app-a",
  "country": "CN"
}
```

Server 以版本化命名空间、Project、调用方 ID、requestId 的长度前缀 UTF-8 SHA-256 生成稳定
Task ID，并在现有不可变 descriptor 保存请求身份与输入指纹。同身份同配置读取原
Task；不同配置冲突。已有 descriptor 或 Task Score 不完整时返回未确认，不另建或补造资源。
Project 目录由 Server 登记；目录未确认保留已知 Task ID，同请求显式核对可以从原 descriptor 补齐目录。关联在原 Task 资源保留期间跨 Server 重启成立，清理 scope 后不承诺。
没有原先累计 50 请求、50,000 号码的内存账本。自动名称、salt 和日期首次创建后固定。

任务和配置 HTTP 接口必须声明开发调用方：`X-XA-Dev-User` 为 userId 的 UTF-8
Base64URL 编码，`X-XA-Dev-Role` 为 `admin` 或 `operator`。解码后的 userId 去首尾空白，
必须符合 `^[1-9a-z][0-9a-z]{3,8}$`，不转换大小写或接受前导零，复用 Server 的纯标识校验。缺失、非法编码、非法角色或重复请求头返回 400，
不默认补管理员；操作范围不符返回 403。`GET /catalog` 保留无身份的只读能力探测。

不可变调用方由 Server 统一解析并显式传入场景服务；不由 Scenario Controller 重复实现所有权检查。
admin 可管理全部场景任务及配置；operator 只能读取和管理自己创建的任务，不能访问配置接口。
共同服务先检查 Project 和创建者，再由场景检查业务归属和有限 Task 类型；拒绝发生在导入文件读取、Item 数量
和 Result 读取、写入及导出扫描之前。配置接口在 H2 访问之前拒绝 operator。
列表直接从 Server 的 Project 单 ZSET 按用户 score 范围取最多 100 条，再读取数量。
operator 默认自己的范围；显式查询其他 userId 返回 403。admin 可用 `?userId=` 查询指定创建者，
省略时读取 Project 批次。truncated 属于实际查询范围，其他用户再多也不会占用个人批次。
批次内按保存的创建时间排序，不承诺最新 100 条，不补扫或分页；已知 Task ID 可独立核对。
缺少创建者不归属于任何 operator。Task 状态、部分失败与 OperationGuard 继续采用原有规则。

创建 JSON 不再接收 `userId` 或 `role`，只从调用方保存 `metadata.userId`，列表/详情返回该保存值。
创建指纹 `app-checks/v4/create` 只覆盖规范化 userId、App 和国家；相同 requestId 的不同调用方独立创建。role 不进入指纹或 descriptor。
任务快照解析只接收已保存的 Group 和 metadata，不依赖版本化 HTTP TaskView。
输入版本为 `3`，salt 和号码 Item 身份算法不变。首次创建保存 `contractId`、`contractParameters`、
`eventCode`、`workerSelector` 以及 salt/date；Group 和 refill 保存在 descriptor 原字段中。
服务端配置不进入客户端重试指纹；相同请求跨配置保存/重启仍复用原 Task，不重新套用当前执行配置。
不回填历史归属或提供旧请求兼容。H2 仍只保存配置。

这是受信任开发环境的目录核对契约，客户端仍可选择目录中的现有身份；不构成身份认证或生产客户数据隔离。
相同检查覆盖共同 Task API、Messages 和业务结果；全局 Runtime Task 预览及 SMS 操作要求 admin。
业务模块不维护固定账户名单，统一消费 Server 用户目录准入后的调用方；不增加全局 Filter、ThreadLocal、持久用户表或身份注册服务。
Server 在每次请求入口从用户目录解析 userId/role 和 Project 授权；配置操作使用 `app-checks.configuration` 权限。权限变更影响后续请求，已准入操作保留原快照。前端刷新或重新登录应用新目录；自动化证明使用显式用户 seed，重启证明复用该 seed，不依赖临时用户。

导入仅允许 `server.inputOwner=app-checks` 的待审核 Task。原始 Item 入口不能绕过固定业务配置。每个文件最多 10 MiB、100,000 个去重号码，
前端将 TXT、CSV 或粘贴统一转换为逐行 UTF-8 文件；后端独立校验 UTF-8、号码和国家前缀，
允许省略 `+`，去首尾空白、忽略空行并去重。无效文件整批拒绝，返回错误数量和首个错误行，
不会写入有效子集。请求局部原始与规范化临时文件在结束或失败后清理，不构成导入历史。

号码的稳定 Item ID 为 `number-` 加长度前缀元组 `["app-checks/v2/number", normalizedNumber]`
的 SHA-256，作用域为 Task。批次读取任务保存的事件、契约、参数、任务盐和 Matching 查询，TTL 留空以采用
Runtime 默认有效期（当前 365 天），不从批准时重新计时。已存在且内容一致的 Item 跳过，
保留原时间、有效期、Score 和结果；不同内容拒绝覆盖。显式重传可用已存 Item 原值调用
既有 append 补齐缺失的初始 Score；Score 存在而执行数据缺失等其他不一致按数据异常处理。

Task priority=50、maxRetryTimes=3；Item priority=5。Preview 种子的供给为 `assignment-window / {} / 100`，
Item 使用 `worker.assignment.available({})`；其他应用可配置已有 Pool/查询组合。这些数字各自属于原 Owner 的边界。
Preview 使用新 scope 直接切换，不支持历史输入版本、旧 Any 供给兼容、数据迁移或回填，
不删除旧环境数据。Task 的执行配置和输入归属在首次创建后固定。

同一 Server 实例通过既有 OperationGuard 串行化一个 Task 的导入、普通追加、批准和关闭；
一个同步导入的全部批次共享持有期。最多两个文件同时导入，结束释放容量，满时写入前背压。
不承诺跨实例串行。写入中断保留已确认批次，503 附带 taskId、confirmedAddedCount 和
existingCount；当前未确认批次不算拒绝，不自动重传、回滚或启动。用户可以显式重传、
补充其他文件、取消，或核对 Owner 当前实际数量后启动部分输入。审批不要求原文件事务完成。
关闭不会把未完成项计作成功，也不会阻止已有执行产生迟到结果。场景服务停机停止新准入并最多等待
正在导入的请求 5 秒，每个新批次检查停止信号。

导入成功回执给出 inputCount、emptyCount、duplicateCount、uniqueCount、addedCount 和
existingCount，均针对收到的号码文件。前端原始 CSV 的行数及去重数属于客户端校验摘要。
来源和回执仅在控制台会话保存；API 不根据 Task 数量重建来源或操作历史。

Project 绑定不预置长期 RPC Task，App Checks 只创建有限任务。列表仍保留目录中由其他显式调用产生的 RPC/无业务 metadata 行，由前端过滤；详情和写入验证
Project、场景和有限 Task 类型。总量来自 Item Score 成员数，active 为 tag 1，failed 为
 tag 5，succeeded 为 tags 6..9。Result 预览只读一次 Owner HSCAN，最多 100 条，不补扫，
不保证文件顺序或最新顺序。失败行没有注册答案；内容或 Item/Group 关联异常保留执行状态并
标记 contentError。统计、Task 状态和 Result 不是共同快照。

CSV 复用通用终态成功结果导出，再按每批最多 100 个 Item 做业务关联校验及筛选，包含号码、
注册状态、应用、地区，保护文本单元格并正确转义。失败、异常内容和缺少注册答案的记录不导出。
文件生成完成后才通过 `X-Export-Count` 返回准确行数，不额外扫描以预估数量。原始 JSONL 与
CSV 临时文件都在失败或传输结束时清理。导出独立于预览筛选；迟到结果可使下一次导出内容变化。

## 具名执行契约

模块内 `AppCheckContract` 只负责参数校验、号码到 Item payload 的转换和成功内容解释。
静态 Bean 汇成不可变 ID 映射；同协议复用一个实现，不同协议增加一个实现及注册，
不持有网络客户端、存储、线程或调度状态，不接收脚本、Java 类名或表达式。

`registration-v1` 使用空参数 `{}`，输入只有 `{number}`；成功内容必须包含
`number、registered、workerId、workerGroupId`，不要求模拟字段。
`simulated-registration-v1` 保留下一节的模拟协议。同一契约可以绑定不同事件名称。

预览和 CSV 共用结果解释：核对保存的事件、号码派生身份、Item 号码、结果号码和 Group，
再要求布尔注册答案及有效执行身份。异常内容标记 `contentError`，排除成功 CSV，
不改变 Kernel 执行状态、不重试、不当作未注册。Worker 自报身份仍是业务内容，
不是独立真实性证明；模拟正确性由独立 Worker/Python 复算证明。

`ProjectWorkerRequirements` 按 Group 声明各自的事件、Pool 和查询依赖。保存校验内部结构，
启动核对宿主资源；查询参数仍经过现有 Matching 准入，没有新增规则解释器。
宿主资源声明不能证明 Worker 已安装 Handler。

## Simulator 协议与可复算性

模拟契约 `simulated-registration-v1` 的 Preview 事件为 `extension.worker.app.registration.check`。Item 参数只有
`number、salt、ranges、delayMs`，不接受请求指定 Worker 身份。
区间为整数左闭右开，位于 0..1000，完整覆盖且互斥，可为空。`delayMs` 恰好两个整数，
0 ≤ min ≤ max ≤ 30000，相等为固定延迟。规范化参数最多 4096 字符，缺失或未知字段拒绝。
配置契约和 Worker 各自在自己的输入边界校验，不新增共享协议模块。

v2 创建首次受理按 UTC 日期生成 salt：对 UTF-8 元组
`["app-checks/v2/salt", appId, country, requestId, "YYYY-MM-DD"]` 逐字段加四字节
大端长度，计算 SHA-256 小写十六进制。号码数量不再参与。salt/date 在 descriptor 固定，
重复创建返回原任务，不用请求重试时的新日期覆盖。Worker 的
outcome/delay 算法和固定向量不变。

Handler 进入时捕获实际 workerId，Group 来自构造绑定。结果及延迟分别计算：

```text
tuple = ["app-checks/v1/" + domain, workerId, salt, number]
encoding = 对每字段追加四字节大端 UTF-8 字节长度 + UTF-8 字节
value = SHA-256(encoding) 的前八字节，按大端无符号整数解释
outcome bucket = value(domain="outcome") mod 1000
delay = min + value(domain="delay") mod (max - min + 1)
```

固定向量：worker-a / fixed-salt / +8613800000001 → bucket 25；延迟范围
[2000,5000] → 4067ms。worker-b 同输入 → bucket 917、4911ms。

先等待所算延迟，再返回成功或抛 `WorkerException(EVENT_EXECUTION_FAILED)`。
成功内容为 `number、registered、workerId、workerGroupId、simulatedDelayMillis`。
中断恢复线程标记并走既有失败路径。没有业务线程、状态缓存或 Reporter。

相同实际 Worker 和输入可以复算；重试换 Worker 后结果可以变化。当前执行 claim
为 5 秒，较长延迟可能重试并产生迟到结果。场景不续租，也不承诺只执行一次。
最终 FAILED 本身不能证明 Handler 执行过，真实失败证明另有测试装配内的调用见证。

## 分配窗口投影

模块仅为 `poolGroups` 中配置的 Group 声明纯属性投影，共同观察 `worker.assigned`，
每组分别选择该 App 的 `execution.eventCode`。不把同组其他业务事件计入窗口。
Pacer 在执行租约和 Item claim 均成功后、Command 编码和发布前发出通知。
同组其他事件、选择候选但分配失败、仅取得租约都不计入。

投影只写两个 Platform Properties：`lastAssignedAt` 和 `windowAssignmentCount`。
前者是最近观察到的分配时间（毫秒），后者是该时间所在固定窗口中的观察数量。
窗口长度读取启动快照的 `poolGroups.<group>.windowMillis`，与 Pool 定义使用同一份不可变
配置。一个 `WorkerPropertyProjection` 声明包含 Group 到消息事件选择/纯投影函数的映射，Server 在启动时展开；
投影不读取数据库。窗口编号为 `floor(observedAtMillis / windowMillis)`。
同窗口累加、时间取最大值；新窗口重新计数；
更旧窗口不回退。两个字段均不存在时初始化；字段不完整、非整数、负数或溢出时
整个 Worker 投影跳过并由 Server 计入处理失败诊断，不静默修复。其他属性不修改。

这里统计的是 best-effort **分配观察**，不是 Handler 实际执行次数或成功次数。
后续发送、执行失败不会扣减；重试再次分配会再次通知。Server 队列丢弃、进程退出、
Facts 缺失或属性写入失败均可能造成缺口，没有补发、最终一致性或精确额度保证。
不定时清零，空闲时保留最近窗口；重启读取已写入值，不补齐未处理通知。
窗口 Pool 的补给策略读取这两个字段，投影只提供选择和纯计算，由 Server 的同一个
Properties Handler 合并读取与写入；场景不依赖 Pacer 或通用 FunctionHandler。
固定路由、异步交接及既有 patch/候选失效的完整边界见
[Server](../../server_jvm/README.md#worker-allocation-observations)。

## 分配窗口筛选

选择窗口 Pool 的 App 使用 `worker.assignment.available({})`；窗口和阈值由下述模块配置的启动快照决定。
场景以 `FixedWindowPoolDefinition` 注册独立 `assignment-window` Pool，关联
`worker.assignment.available`、两个 Platform 属性键以及各 Group 的窗口参数。
只有该纯数据契约及 `WindowLimit` 是场景允许引用的 Matching 类型；场景不持有库存、
存储或补给策略实现。Server 收集定义，Matching 复用参数化基础规则在补给时资格判断，
查询函数只取库存。完整失败与预算见
[Matching Owner](../../worker_matching_jvm/README.md#fixed-window-pools)。

跨窗口意味着重新符合条件，不承诺立即执行。观察异步、可丢失，不能把配置阈值当成严格
执行上限；失败和迟到结果不会扣减统计。范围是 Group 内的单 Worker，没有跨设备
IP／账号配额。其他显式函数不受这一策略拦截。窗口长度改变使用新 scope，不迁移旧值。

资格以补给时的快照为准，入池之后消费不读取 Facts 或再次计算窗口；候选库存由该 Pool
独立持有，`worker.any` 使用另一份 Any 库存。属性变化、失效提示、TTL 与 Kernel 精确
执行准入仍各自独立，不能据此承诺取用时业务条件最新。

配置归属为固定 Pool／Group，`poolGroups` 是应用 Group 的可选子集。
使用窗口供给或窗口查询的 Group 必须有参数；Any 组合不自动附加窗口资格。
宿主 Group 的 `pools` 和 `functions` 只表达启用关系。没有独立窗口 YAML 读取路径；
窗口 Pool/函数身份及 salt 算法不变；执行绑定不再由全局固定事件决定。

## 模块内配置存储

`AppCheckConfigurationStore` 独占 H2 JDBC 连接、单表建表和关闭，H2 版本随 Boot BOM。
没有公共 DataSource、ORM、数据库进程或公共 Storage 模块。Kernel、Pacer、Matching、Server
不查询 H2；Task、Item、Result、调度和请求幂等仍由 Redis 原 Owner 管理。

严格启动参数为 `xa.mass.scenarios.app-checks.storage.path`（嵌入式数据库文件前缀）和
`storage.seed`（Spring 资源地址）。Preview 由启动器传入独立环境路径，种子为
`classpath:app-checks/preview-configuration.json`，包含 App A/B、60000 ms/10 次窗口及原模拟默认值。
数据库表没有配置行时才读取种子；存在记录时种子内容不会覆盖数据库。非法文档
或读取失败阻止场景启动，不回退默认值。未装配本场景的上下文不开连接、不建文件。

唯一表 `app_check_configuration` 只保存一行：`id=1` 和完整 `document`。不保存格式版本、
递增修订号或历史版本；内容指纹从规范化文档计算，不单独落库。文档结构为：

```json
{
  "common": {
    "contractDefaults": {
      "simulated-registration-v1": {
        "ranges": {"registered": [0,500], "unregistered": [500,900], "failed": [900,1000]},
        "delayMs": [2000,5000]
      },
      "registration-v1": {}
    }
  },
  "applications": {"app-a": {
    "label": "App A", "workerGroupId": "app-a-sim",
    "contractId": "simulated-registration-v1",
    "execution": {
      "eventCode": "extension.worker.app.registration.check",
      "refill": [{"poolName": "assignment-window", "target": {}, "count": 100}],
      "workerSelector": {"executorName": "worker.assignment.available", "input": {}}
    }
  }},
  "poolGroups": {"app-a-sim": {"windowMillis": 60000, "maxCount": 10}}
}
```

applications 是非空对象，键为 App ID，值中不再包含 appId；Group 绑定保持唯一。
对象键顺序不具有业务含义，Catalog 按 App ID 字典序展示。App `parameters` 存在时整份使用，
否则取 `common.contractDefaults[contractId]`；不递归合并。所选契约规范化并校验参数。
窗口/阈值为正整数。允许改名称、执行绑定、契约/参数、窗口参数和新增 App；不允许删除、停用或改已有身份/Group 绑定。
国家、导入容量和 Task 策略不进入配置表。不保存历史修订、任务身份、号码或操作记录。

GET 的 `saved` 与 `effective` 各包含 `fingerprint`、`configuration`。指纹是校验并规范化后
完整文档的 SHA-256；递归排序对象键、统一数值表示，保留数组顺序及字符串原值，忽略 JSON 排版。
客户端原样携带读取到的指纹，不自行计算 API 指纹。PUT 在事务内锁定唯一配置行，再比较
`expectedFingerprint` 并整份替换；并发编辑返回 409，同内容保存不写入且指纹不变。
指纹只核对内容，不记录编辑次数；A→B→A 后指纹仍为 A，没有审计或历史版本语义。
非法配置/字段 400，数据库故障或提交未确认 503。未知保存不自动重试；GET 读取当前保存文档
供人工核对。
`contracts` 给出静态注册的说明、参数/输入/结果示例。`restartRequired` 比较配置内容，`newScopeRequired` 标识既有 Group 窗口长度变化。

模块启动只加载一次生效快照，以它生成 Catalog、ProjectDefinition、ProjectWorkerRequirements、
FixedWindowPoolDefinition、各 Group 属性投影和创建映射。配置保存不会改变这些运行对象。
任务保存的 Group、事件、查询和契约参数也不随配置变化；运行时存储故障仅影响配置读写。

新增 App 先保存配置，再准备宿主 Group/事件/Pool/函数和真实 Worker，最后手动重启。
保存只验证配置本身；完整依赖在启动时校验，失败阻止后续启动，不补注册资源。
修改既有 Group 窗口长度须使用新 Redis scope；系统不自动清理、切换或重启。
数据库文件位于环境数据目录，不进入 Git 或 ZIP。源码/ZIP 验收使用独立数据库和种子，
重启证明复用同一文件。本次表结构和 JSON 形状切换使用新 H2 文件与 Redis scope，不做旧数据迁移。
没有热更新、后台同步或数据库执行路径。

## 页面与结果核对

启动 [Preview](../../distribution/server/PREVIEW.md#source-launch)，打开 `/app-checks`，
通过顶栏选择开发身份，再选择 App 和号码国家。前端依次创建空任务、上传号码文件，
用户核对实际数量后启动。operator 只处理当前载入范围内自己创建的任务，admin 可管理全部任务。
列表菜单管理任务，抽屉预览结果；未确认创建或导入保留请求身份及已知 Task ID，不自动重试。

配置页以整份 JSON 编辑 `common`、`applications` 和 `poolGroups`，不再提供分散表单。
前端只校验 JSON 语法与根对象，后端定位字段错误。刷新/导航保留编辑文本；指纹冲突不合并，
保存未确认保留原文本与基准指纹供读取核对。当前生效文档与契约参考折叠展示。

仅模拟契约的 admin 技术区显示“核对当前预览”，按上面的 SHA-256 协议，用存量 salt、实际 Worker、号码和模拟描述复算；
它只核对已展示内容，不证明执行次数或失败原因，模拟延迟也不是端到端耗时。
[前端 Owner](../../frontend/README.md#app-checks-task-workspace) 维护文件导入、草稿、刷新、
API/Mock 和浏览器核对的可用性及本地状态。

## 装配与证明

配置单测覆盖文件重开、种子不覆盖、文档损坏、键顺序/排版不影响指纹、并发指纹冲突、
同内容保存及内容恢复、提交确认丢失后的读取核对。
Boot 真实 HTTP/Redis/Worker 证明保存 App C 后 Catalog 保持原值，缺失宿主 Group 启动失败，
补齐后重启完成 C 的创建、导入、审核、查询及导出；App C 使用不同事件、Any Pool 和 registration-v1，不生成窗口投影或模拟字段；关闭 H2 后这些执行路径仍工作。
Server 测试保留多 Group 投影隔离、既有批读预算和 best-effort 失败语义。

生产装配见 [Boot](../../server_boot_jvm/README.md#platform-and-preview)；
Host 数量参数和库存复用见 [Preview](../../distribution/server/PREVIEW.md#inventory-and-process-lifecycle)。
以下 `--build` 验证源码 staging，`--root` 验证新解压 ZIP；后者不回退源码启动器或在包内构建。

```powershell
.\gradlew.bat :scenarios:app-checks-jvm:test :worker_simulator_jvm:test :server_boot_jvm:test
.\gradlew.bat :server_boot_jvm:scenarioCompositionIntegrationTest
python -m unittest discover -s scenarios/app-checks-jvm -p 'test_*.py'
python scenarios/app-checks-jvm/run_acceptance.py --build --port 18620
python scenarios/app-checks-jvm/run_acceptance.py --root <freshly-extracted-preview> --port 18640
```

单元测试证明轻量创建、稳定身份、十万号码有界导入、全文件校验、部分失败、互斥及重复输入处理。
调用方单测覆盖请求头校验、拒绝发生在任务内容读取/导入/导出及配置存储之前，以及按用户目录范围读取。Boot 真实 HTTP/Redis 证明 Base36 调用方、缺失或非法调用方
返回 400、跨用户及配置访问返回 403、admin 管理和重启后创建者保留。源码/ZIP runner
显式携带验证调用方并核对创建者；这些证明不代表身份认证或客户数据隔离。
真实 Redis/HTTP 另外验证十万号码写入、跨文件去重、原时间保持、默认有效期、重启幂等、审核数量检查和关闭。Boot 的
真实 Redis/HTTP/Worker 测试用两个 Group 各两个 Worker，证明 101 条预览有界、真实
异常调用、未注册成功、Group 隔离、6 秒迟到成功及 Server 重启读取。调用见证仅在测试
装配中，溢出失败，不增加生产尝试日志或场景队列。

额外的分配观察证明保持实际 Redis、Server、Adapter 和 Java Worker：在测试装配中
阻塞 Handler 返回 Report，先确认 Platform 窗口已推进，再放行成功、异常及 6 秒
迟到结果，并验证重启保留属性。测试有界记录真正的 Pacer 通知，用其源时间和
Worker 关联复算窗口，不用 Item 数量或 Result 数量推算。该见证不进入生产代码。

窗口专用 Boot 用例使用一个实际 Worker、60 秒窗口、阈值 1：真实分配通知推进属性后，
通过实际 Facts 快照读取和零准入的 refill 结果见证筛选拒绝，并断言 take 的 Facts 读取为零；跨窗后通过普通候选回收（30 秒老化阈值）、
补货与执行恢复，不手动清零、恢复库存或改 Score，也不承诺跨窗立即执行。恢复观察最多 180 秒，字段边界另由
受控时钟单元测试证明。投影延迟允许继续选择，测试不把阈值当成严格配额。

原 101 Item 等非窗口 Boot 回归显式把阈值设为 1000，仍走新函数。
进程 runner 使用独立种子文件覆盖两个 Group 的阈值为 1000，
保留并恢复调用环境，在摘要记录覆盖值。它验证原业务闭环，不声称验证默认窗口限额。
Preview 的默认 60 秒／10 次保持。进程 runner 复用现有 Preview 启动/清理，四个 App Worker 加一个闲置 demo Worker，
先只读确认已知 App Worker 的 Properties 已上报，再在两份独立启动配置中创建八个 Task 共 60 Items，
分别覆盖全注册/全失败与全未注册/混合；参数来自 App 配置而非创建请求。两份配置分别使用
`--port` 和 `--port + 10` 为基准的独立端口组（各加 3 为 Adapter、加 4 为 Host），避免关闭 JVM
后立即复用尚未释放的 TCP 端口；启动器仍严格拒绝已占用端口。功能验收基准端口范围为 1..65521。
每份配置内每组两个 Task，保留同 Group 与跨 Group 并存覆盖，
最多每 Task 45 秒观察；独立 Python 复算成功结果和延迟。
不要求混合比例、均匀分配或固定执行者；进程 runner 不以终态失败证明执行次数。
CI 使用现有 `product_coexistence` lane 执行源码及新解压 ZIP；原 SMS/Messages
proof 显式 `app_count=0`，负载和断言保持原样。公开产物只包含阶段、数量和校验摘要，
原始日志与业务内容留在 private；每次使用唯一 test scope 并仅 SCAN/UNLINK 该 scope。

## 可选 Pool 基准

现有 runner 的 `--benchmark` 模式连续运行三次独立 scope：20 个 App A Worker、
1,360 个号码、全注册成功、零模拟延迟、60 秒窗口／阈值 1,000。计时从批准开始到观察到
Task 终态，保持相同轮询方式；导入、启动和编译不计入完成时间。不新增 CI lane。

```text
python scenarios/app-checks-jvm/run_acceptance.py --benchmark --baseline --root <217393902-preview> --output <baseline-output> --port 18720
python scenarios/app-checks-jvm/run_acceptance.py --benchmark --root <current-preview> --output <current-output> --port 18720
```

以上旧产物对比属于版本范围内的历史命令，须使用该版本的运行器。当前运行器只调用本轮共同 Task API，
不提供旧任务 API 兼容；不能以当前运行器执行上述旧二进制作为本轮验证。`--baseline` 的配置选择不进入生产配置解析。
两个版本按相同启动方式展开 Boot JAR，并用相同的 benchmark-only Java 观察器启动原
Boot composition。观察器只记录 App A 的 refill/take 调用耗时、失败数和固定 Facts
快照命令次数，不保留 Command 参数或业务内容；其计量入口只存在于该独立 loopback
测试进程，不进入生产 JAR、部署或场景 API。没有额外观察线程或生产计数状态。

summary 保存 Server/Host/Frontend 和观察器指纹、Java/系统信息以及三次阶段计量。
旧版本应只在 take 阶段读取 Facts，新版本应只在 refill 阶段读取；无法归属的读取或
阶段失败使基准失败。阶段耗时包含观察器开销，不等同于整个 Dispatch 或无观测器的延迟。
低阈值的窗口恢复由 Boot 专用用例单独证明；不以这个基准声称严格配额或生产吞吐改善。
