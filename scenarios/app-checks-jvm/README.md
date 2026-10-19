# 批量检查（Batch Checks / app-checks）

Status: current finite Batch Checks scenario owner.

产品名称为 Batch Checks／批量检查；模块、Project、API 和页面路径等技术标识继续使用 `app-checks`。

`app-checks` 是通用有限任务的受约束业务入口。创建空的 `CLOSE_WHEN_IDLE`
Task、导入种子、核对启动和关闭是独立操作，复用 Server 的 Task 应用能力。
每个 Task 固定检查类型、Group、事件、供给、查询和参数，按保存的 seedsPerItem 将有序种子组装为 Item。
检查配置来自模块内 JSON 配置文件的启动快照；同一 Group 可以提供多种检查能力。
具名契约由模块代码静态注册；可选窗口 Pool 与分配反馈由宿主按 Group 具名绑定。[Boot](../../server_boot_jvm/README.md#platform-and-preview)
提供配置文件路径和种子资源，宿主继续准备对应 Group、Matching 启用关系和 Worker 能力。
国家只校验号码格式和前缀，不筛选 Worker 国家或证明号码归属地。

有效结果由场景契约判定满足或不满足，两者均属于执行成功；执行失败和协议错误归为检查错误。
注册查询与原始运营商识别共享有限任务流程。模拟失败区间仍让真实 Handler 抛异常。Kernel、Pacer、
SDK 和 Worker 协议使用既有机制；Matching 基础规则由宿主启动装配，没有新增业务 Redis key 或导入后台任务。

## API 和 Task 数据

| API | 内容 |
| --- | --- |
| `GET /api/v1/app-checks/catalog` | 当前生效的 Project、checkTypes、含执行绑定的 checks、国家及单次种子导入限制 |
| `GET /api/v1/app-checks/tasks/{taskId}/credits` | 实际种子数量、固定单价、总额、所有者余额及原启动请求的扣分核对；可带 requestId |
| `GET /api/v1/app-checks/configuration` | 已保存与当前进程生效配置及内容指纹、待重启提示及契约说明/示例 |
| `PUT /api/v1/app-checks/configuration` | `{expectedFingerprint, configuration}` 整份校验与原子文件替换；不热更新 |
| `POST /api/v1/app-checks/tasks` | 创建空待审核任务，201 返回 `{"taskId":"..."}` |
| `POST /api/v1/app-checks/tasks/{taskId}/seeds:import` | `text/plain` UTF-8 种子文件；全文件校验后每批最多 100 个 Item |
| `POST /api/v1/app-checks/tasks/{taskId}/results:export?filter=all` | 终态 CSV；filter 为 all、matched 或 unmatched |
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
  "checkId": "app-a-registration",
  "country": "CN"
}
```

号码类型必填 country；text 类型不接受 country。checkType 和 seedType 来自选定检查项，不能由请求覆盖。
Server 通过现有创建关联把 Project、调用方 ID、requestId 关联到首次生成的
Task ID，并在现有不可变 descriptor 保存输入指纹。同身份同配置读取原
Task；不同配置冲突。已有 descriptor 或 Task Score 不完整时返回未确认，不另建或补造资源。
Project 目录由 Server 登记；目录未确认保留已知 Task ID，同请求显式核对可以从原 descriptor 补齐目录。关联在原 Task 资源保留期间跨 Server 重启成立，清理 scope 后不承诺。
没有原先累计 50 请求、50,000 号码的内存账本。自动名称、salt 和日期首次创建后固定。
自动名称使用能力名称并在 128 字符内保留时间；过长的能力名称安全缩短，完整名称仍保存在任务快照中。

任务和配置 HTTP 接口必须声明开发调用方：`X-XA-Dev-User` 为 userId 的 UTF-8
Base64URL 编码，`X-XA-Dev-Role` 为 `admin` 或 `operator`。解码后的 userId 去首尾空白，
必须符合 `^[1-9a-z][0-9a-z]{3,8}$`，不转换大小写或接受前导零，复用 Server 的纯标识校验。缺失、非法编码、非法角色或重复请求头返回 400，
不默认补管理员；操作范围不符返回 403。`GET /catalog` 保留无身份的只读能力探测。

不可变调用方由 Server 统一解析并显式传入场景服务；不由 Scenario Controller 重复实现所有权检查。
admin 可管理全部场景任务及配置；operator 只能读取和管理自己创建的任务，不能访问配置接口。
共同服务先检查 Project 和创建者，再由场景检查业务归属和有限 Task 类型；拒绝发生在导入文件读取、Item 数量
和 Result 读取、写入及导出扫描之前。配置接口在文件访问之前拒绝 operator。
列表直接从 Server 的 Project 单 ZSET 按用户 score 范围取最多 100 条，再读取数量。
operator 默认自己的范围；显式查询其他 userId 返回 403。admin 可用 `?userId=` 查询指定创建者，
省略时读取 Project 批次。truncated 属于实际查询范围，其他用户再多也不会占用个人批次。
批次内按保存的创建时间排序，不承诺最新 100 条，不补扫或分页；已知 Task ID 可独立核对。
缺少创建者不归属于任何 operator。Task 状态、部分失败与 OperationGuard 继续采用原有规则。

创建 JSON 不再接收 `userId` 或 `role`，只从调用方保存 `metadata.userId`，列表/详情返回该保存值。
创建指纹 `app-checks/v5/create` 只覆盖规范化 userId、checkId 和有效种子选项（号码国家，text 为空）；相同 requestId 的不同调用方独立创建。role 不进入指纹或 descriptor。
任务快照解析只接收已保存的 Group 和 metadata，不依赖版本化 HTTP TaskView。
输入版本为 `5`，salt 和号码 Item 身份算法不变。首次创建保存 `checkId`、检查名称、`checkType`、类型名称、`seedType`、有效种子选项及 `contractParameters`、
`eventCode`、`workerSelector`、`seedsPerItem` 以及 salt/date；Group 和 refill 保存在 descriptor 原字段中。
服务端配置不进入客户端重试指纹；相同请求跨配置保存/重启仍复用原 Task，不重新套用当前执行配置。
不回填历史归属或提供旧请求兼容。JSON 文件只保存配置。

这是受信任开发环境的目录核对契约，客户端仍可选择目录中的现有身份；不构成身份认证或生产客户数据隔离。
相同检查覆盖共同 Task API、Messages 和业务结果；全局 Runtime Task 预览及 SMS 操作要求 admin。
业务模块不维护固定账户名单，统一消费 Server 用户目录准入后的调用方；不增加全局 Filter、ThreadLocal、持久用户表或身份注册服务。
Server 在每次请求入口从用户目录解析 userId/role 和 Project 授权；配置操作使用 `app-checks.configuration` 权限。权限变更影响后续请求，已准入操作保留原快照。前端刷新或重新登录应用新目录；自动化证明使用显式用户 seed，重启证明复用该 seed，不依赖临时用户。

导入仅允许 `server.inputOwner=app-checks` 的待审核 Task。原始 Item 入口不能绕过固定业务配置。每个文件最多 10 MiB、100,000 个去重种子。
`phonenumber` 保留 TXT、CSV 列选择和粘贴，校验 CN/US/GB 国家前缀，允许省略 `+`，规范化后去重。
`text` 只接受 TXT 或粘贴，一行一个；移除开头 BOM、每行首尾空格和 Tab，忽略空行，保留大小写、Unicode 及内部内容。
text 不解析 JSON、拆列或做领域规范化。前端统一上传逐行 UTF-8，后端独立全文件校验与去重。非空规范化种子在去重及任何 Item 写入前经过契约的纯 validateSeed 校验；整数末行非法同样拒绝完整文件。
无效文件整批拒绝，返回错误数量和原始首个错误行，
不会写入有效子集。请求局部原始与规范化临时文件在结束或失败后清理，不构成导入历史。

seedsPerItem=1 时，号码的稳定 Item ID 为 `number-` 加长度前缀元组 `["app-checks/v2/number", normalizedNumber]`
的 SHA-256，作用域为 Task。批次读取任务保存的事件、契约、参数、任务盐和 Matching 查询，TTL 留空以采用
Runtime 默认有效期（当前 365 天），不从批准时重新计时。text 使用独立命名空间：`seed-` 加长度前缀 UTF-8 元组 `["app-checks/v1/text", normalizedSeed]` 的 SHA-256，同样在 Task 内去重。
seedsPerItem>1 时，使用 `group-` 加长度前缀 UTF-8 元组
`["app-checks/v1/seed-group", seedType, ...orderedNormalizedSeeds]` 的 SHA-256；尾组也使用组身份。
每次导入按文件内首次出现顺序独立分组，不跨请求拼尾组。完整分组先确定，再核对已有 Item；
相同文件同顺序重传复用原组，重排或重新分组不承诺去重。不同组可含相同种子，结果和 CSV 保留各组答案，
没有 Task 全局种子索引。组装后的 payload 与身份也在首次写入前完整校验并暂存，结束后清理。
已存在且内容一致的 Item 跳过，
保留原时间、有效期、Score 和结果；不同内容拒绝覆盖。显式重传可用已存 Item 原值调用
既有 append 补齐缺失的初始 Score；Score 存在而执行数据缺失等其他不一致按数据异常处理。

Task priority=50、maxRetryTimes=3；Item priority=5。Preview 注册检查供给为 `assignment-window / {} / 100`，
Item 使用 `worker.assignment.available({})`；运营商检查使用 `any / {} / 100` 和 `worker.any({})`。这些数字各自属于原 Owner 的边界。
Preview 使用新 scope 直接切换，不支持历史输入版本、旧 Any 供给兼容、数据迁移或回填，
不删除旧环境数据。Task 的执行配置和输入归属在首次创建后固定。

同一 Server 实例通过既有 OperationGuard 串行化一个 Task 的导入、普通追加、批准和关闭；
一个同步导入的全部批次共享持有期。最多两个文件同时导入，结束释放容量，满时写入前背压。
不承诺跨实例串行。写入中断保留已确认批次，503 附带 taskId、confirmedAddedCount 和
existingCount；当前未确认批次不算拒绝，不自动重传、回滚或启动。用户可以显式重传、
补充其他文件、取消，或核对 Owner 当前实际数量后启动部分输入。审批不要求原文件事务完成。
关闭不会把未完成项计作成功；已有执行在共同收尾窗口内仍可产生迟到结果。场景服务停机停止新准入并最多等待
正在导入的请求 5 秒，每个新批次检查停止信号。

导入成功回执的 inputCount、emptyCount、duplicateCount、uniqueCount 按种子计数；itemCount 是本次组装的执行项数，addedCount、existingCount 以及失败回执 confirmedAddedCount 均按 Item 计数。批准和任务进度也按执行项，不能据此推算全 Task 种子数。前端原始 CSV 的行数及去重数属于客户端校验摘要。
来源和回执仅在控制台会话保存；API 不根据 Task 数量重建来源或操作历史。

Project 绑定不预置长期 RPC Task，App Checks 只创建有限任务。Project 声明独占业务输入归属，通用原始创建和 RPC preparation 均被拒绝；详情和写入验证
Project、场景和有限 Task 类型。总量来自 Item Score 成员数，active 为 tag 1，failed 为
 tag 5，succeeded 为 tags 6..9。Result 预览在 Redis 阶段只读一次 Owner HSCAN，归档后顺序读取文件；逐 Item 校验并展开后最多 100 条业务行；底层截断或展开裁剪均标 truncated，不补扫 Redis，
不保证文件顺序或最新顺序。失败行没有业务判定；内容或 Item/Group 关联异常保留执行状态并
标记 contentError。统计、Task 状态和 Result 不是共同快照。

CSV 复用 Server 共同顺序结果读取，使用每条结果携带的原 Item 做业务关联校验及筛选，包含种子、
判定、检查类型、执行配置，号码类型追加地区，保护文本单元格并正确转义。静态契约提供附加列和值；注册契约保留注册状态，运营商契约追加
原始运营商及识别说明。失败、异常内容和缺少有效判定的记录不导出；不满足属于有效结果。
文件生成完成后才通过 `X-Export-Count` 返回准确行数，不额外扫描以预估数量。通用 JSONL 导出与
CSV 各自的下载临时文件在失败或传输结束时清理；CSV 不再生成中间 JSONL。导出独立于预览筛选；收尾期迟到结果可使下一次导出内容变化。
默认终态 1 小时后由 Server 冻结并归档，释放 Redis 输入和结果；业务预览与 CSV 使用相同的
归档结果和原输入，保留当前协议解析与关联校验。Item Score 及统计保留至终态 7 天后最终清理。
场景不另设归档表、Hash 索引、统计存储或清理定时器。

## 具名执行契约

`string-digest-md5-prefix-v1` 使用 text、空参数和一个种子／Item；构造
`{algorithm:MD5,value:seed}`，解释 Android digest 的 `algorithm/input/digest`。
算法、输入关联及 32 位小写十六进制摘要必须有效，以 `0` 开头时满足，摘要进入
`details.md5` 和 CSV。不要求 Simulator 的 valid/md5 字段，不补造 Worker 身份，
不重新计算摘要。协议异常排除有效 CSV。Preview 默认仍只配置原五项能力。

Boot 通过严格绑定的 `xa.mass.scenarios.app-checks.enabled` 独立装配，缺省关闭，
Preview 显式开启；consumer 可用外部配置开启，无需其他 Scenario。关闭时不打开
配置文件或锁；开启必须明确提供 configuration.path/seed，不回退 Preview 种子。

独立部署使用[三份外部输入](../../distribution/server/README.md#external-consumer-workbench-deployment)：宿主 YAML、Group 注册 JSON 和本模块配置 JSON。
检查配置中的 Group 去重生成本 Project，宿主无需重复填写 supported Groups。
资源启用仍独立由宿主 YAML 决定；同协议新增 Group 不需要修改 profile 或重新打包。
种子只负责首次初始化，新增检查须修改实际保存文档，随后补齐宿主资源并重启。


模块内 `AppCheckContract` 同时声明类型 ID、名称、说明、seedType 和 maxSeedsPerItem；统一用 payload(context, seeds)、inputSeeds(payload)、results(context, payload, content) 承接一个 Item 与多个种子答案。单条契约使用长度为 1 的集合。参数校验和 CSV 附加列仍是纯业务映射。
checkType 直接采用实现 ID，不增加另一套类型到协议的注册体系。
静态 Bean 汇成不可变 ID 映射；同协议复用一个实现，不同协议增加一个实现及注册，
不持有网络客户端、存储、线程或调度状态，不接收脚本、Java 类名或表达式。

`registration-v1` 使用空参数 `{}`，输入只有 `{number}`；成功内容必须包含
`number、registered、workerId、workerGroupId`，不要求模拟字段。
`simulated-registration-v1` 保留下一节的模拟协议。同一契约可以绑定不同事件名称。

归一化结果为 `seed、matched、details`。注册协议把 Worker 的 `registered` 映射为 matched，
同时保留 `details.registered` 业务答案；执行身份和模拟延迟也保存在 details。公开业务响应不保留 number 或顶层 registered/Worker/延迟别名。
预览和 CSV 共用结果解释：公共层核对保存的事件、号码派生身份和输入关联；协议自带的
Worker/Group 字段由相应契约校验。执行失败不生成 matched；异常内容标记 `contentError`，排除成功 CSV，
不改变 Kernel 执行状态、不重试、不当作不满足。Worker 自报身份仍是业务内容，
不是独立真实性证明；模拟正确性由独立 Worker/Python 复算证明。

`original-carrier-v1` 参数为 `{}`，把号码映射为 `{rawNumber}`，复用已有
`extension.worker.phonenumber.original-carrier`。契约核对 input/possible/valid：valid=true 且
originalCarrier 为非空字符串时满足；空字符串表示没有映射；合法 valid=false 与正常号码错误码
表示不满足。缺字段、类型错误、输入不符及调用前置错误不能伪装为负面答案。details 保存
originalCarrier 和 reason（IDENTIFIED、NO_CARRIER_MAPPING 或号码库错误码），不补造 Worker 身份。
号码格式及 CN/US/GB 前缀校验仍在导入前完成，libphonenumber 语义有效性只由 Worker 检查。
该能力识别原始运营商元数据，不证明实时运营商、携号转网归属或号码在用状态。

`ProjectWorkerRequirements` 按 Group 合并、去重所有检查项的事件、Pool 和查询依赖。保存校验内部结构，
启动核对宿主资源；查询参数仍经过现有 Matching 准入，没有新增规则解释器。
宿主资源声明不能证明 Worker 已安装 Handler。

## 分组协议与逐种子结果

`checks.<id>.execution.seedsPerItem` 省略时规范化为 1；单条契约上限 1，批量契约上限 100。
非法大小或契约组合在配置保存和启动时拒绝，配置继续保存后重启生效。
Preview 运营商使用 `original-carrier-batch-v1` / `extension.worker.phonenumber.original-carrier.batch`，
输入 `{rawNumbers:[...]}`；MD5 使用 `md5-prefix-demo-batch-v1` / `extension.worker.string.md5.batch`，
输入 `{values:[...]}`。两者默认 10，均返回 `{results:[原单条结果]}`。大小为 1 时仍使用列表协议。
注册和素数保持单条事件与大小 1。组内最多 100 种子与 Owner 写入每批最多 100 Items 是独立边界。

共用解释先校验保存事件、Item 身份与输入组，再校验全部答案的结构及成员集合。
返回乱序按输入顺序恢复；缺失、重复、额外或损坏答案导致整组 contentError，不导出部分行。
执行失败按原输入展开检查错误，不产生 matched；没有 Runtime Result 不补造业务行。
合法负面答案继续 matched=false。解析不改变 Item 状态或触发重试；平台重试仍以整个 Item 为单位。

CSV 每个种子一行，整组校验成功后才按 all/matched/unmatched 写入，计数是业务行。
Redis 与归档使用同一解释；原始 JSONL 仍每 Item 一条。分配观察不按组内种子数放大，
一次成功 Item 不证明物理 Handler 仅执行一次。

## Simulator 协议与可复算性

模拟契约 `simulated-registration-v1` 的 Preview 事件为 `extension.worker.app.registration.check`。Item 参数只有
`number、salt、ranges、delayMs`，不接受请求指定 Worker 身份。
区间为整数左闭右开，位于 0..1000，完整覆盖且互斥，可为空。`delayMs` 恰好两个整数，
0 ≤ min ≤ max ≤ 30000，相等为固定延迟。规范化参数最多 4096 字符，缺失或未知字段拒绝。
配置契约和 Worker 各自在自己的输入边界校验，不新增共享协议模块。

v2 创建首次受理按 UTC 日期生成 salt：对 UTF-8 元组
`["app-checks/v2/salt", checkId, country, requestId, "YYYY-MM-DD"]` 逐字段加四字节
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

分配窗口反馈由宿主按 WorkerGroup 显式绑定，不再根据检查项使用的查询函数反推事件。
Preview 的 App A/B 绑定 `registration-assigned`，只观察注册查询的 `worker.assigned`；
运营商、MD5、素数及 Messages 事件不自动进入该计数。场景不声明投影 Bean，也不写窗口属性。
完整的事件选择、纯投影算术、批处理、失败与生命周期见
[Server 具名绑定](../../server_jvm/README.md#named-window-pool-and-feedback-bindings)。

一个 Item 即使包含多个种子，也不会按种子数放大分配观察；重试仍可能再次分配。
这是 best-effort 分配观察，不是实际执行次数、成功次数或严格配额。

## 分配窗口筛选

检查项通过保存的 refill 与 workerSelector 引用已启用能力；Preview 注册检查继续使用
`assignment-window` 和 `worker.assignment.available({})`。窗口参数、属性字段及反馈选择归宿主，
不进入本场景配置文件。新增检查项不会启用或修改 Group 资源。

资格仍以补给时的快照为准；消费不读取 Facts，Any 和窗口库存保持独立。
完整规则与失败语义见 [Matching Owner](../../worker_matching_jvm/README.md#fixed-window-pools)。
改变窗口长度须使用新 Redis scope；本场景不迁移属性、不补清零或保留历史策略。

## 模块内配置存储

`AppCheckConfigurationStore` 在模块内拥有一个 JSON 文件及同目录固定 `.lock` 文件的进程独占锁。
第二个 Store 或进程不能同时占用该文件；关闭和启动失败释放锁，锁文件不随配置替换或删除。
同 JVM 先按规范化真实路径占用本地名额，再打开唯一的锁文件 channel；重复持有在打开锁文件前拒绝。
名额在 channel 关闭后释放。这样避免部分平台关闭竞争 channel 时连带释放原持有者的锁，
遵循 [Java FileLock 的平台约束](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)。
没有数据库依赖、公共 Storage 模块或后台同步。Kernel、Pacer、Matching、Server 不读取该文件；
Task、Item、Result、调度和请求幂等仍由 Redis 原 Owner 管理。

严格启动参数为 `xa.mass.scenarios.app-checks.configuration.path`（实际 JSON 文件路径）和
`configuration.seed`（Spring 资源地址）。Preview 由启动器传入独立环境路径，种子为
`classpath:app-checks/preview-configuration.json`，包含五项能力：`app-a-registration`、`app-b-registration`、`original-carrier`、`uint32-prime`、`md5-prefix`。
App A/B 注册使用原 60000 ms/10 次窗口和模拟参数；其余三项绑定 demo-sim，使用 Any，不增加窗口投影、Group 或 Worker 数量。
只有目标文件不存在时才读取种子并完整校验后生成文件。已有文件不读取种子；空文件、
非法 UTF-8、损坏 JSON、非法配置或读取失败阻止场景启动，不回退默认值。
未装配本场景的上下文不创建配置文件或锁文件。旧 `storage.*` 路径不再接受。

文件以 UTF-8、缩进 JSON 保存规范化后的完整配置对象，不增加外层包装。
不保存格式版本、递增修订号、指纹或历史版本；内容指纹从规范化文档计算。文档结构为：

```json
{
  "common": {
    "typeDefaults": {
      "simulated-registration-v1": {
        "ranges": {"registered": [0,500], "unregistered": [500,900], "failed": [900,1000]},
        "delayMs": [2000,5000]
      },
      "registration-v1": {}
    }
  },
  "checks": {"app-a-registration": {
    "label": "App A", "workerGroupId": "app-a-sim",
    "checkType": "simulated-registration-v1",
    "execution": {
      "eventCode": "extension.worker.app.registration.check",
      "refill": [{"poolName": "assignment-window", "target": {}, "count": 100}],
      "workerSelector": {"executorName": "worker.assignment.available", "input": {}}
    }
  }}
}
```

checks 是非空对象，键为稳定 checkId，允许多个检查项绑定同一 Group。
对象键顺序不具有业务含义，Catalog 按 checkId 字典序展示。检查项 `parameters` 存在时整份使用，
否则取 `common.typeDefaults[checkType]`；不递归合并。所选实现规范化并校验参数，seedType 只由实现声明。
允许改名称、执行绑定、参数和新增检查项；不允许删除、停用或改已有 checkId 的类型/Group 绑定。
窗口 Pool 与反馈参数、国家、导入容量和 Task 策略不进入配置文件。不保存历史修订、任务身份、号码或操作记录。

GET 的 `saved` 与 `effective` 各包含 `fingerprint`、`configuration`。指纹是校验并规范化后
完整文档的 SHA-256；递归排序对象键、统一数值表示，保留数组顺序及字符串原值，忽略 JSON 排版。
客户端原样携带读取到的指纹，不自行计算 API 指纹。PUT 在进程独占锁及实例串行准入内重新读取文件、比较
`expectedFingerprint`，再执行既有更新校验；并发编辑返回 409，同内容保存不重写文件。
新文档完整写入同目录临时文件并刷新后原子替换，无法原子替换时不退回直接覆盖。
本次临时文件在完成或失败后清理；崩溃残留临时文件不作为生效或恢复配置。
指纹只核对内容，不记录编辑次数；A→B→A 后指纹仍为 A，没有审计或历史版本语义。
非法配置/字段 400，文件读写故障或保存未确认 503。未知保存不自动重试；GET 读取当前保存文档
供人工核对。运行期间文件丢失或损坏只返回存储错误，不重新播种；替换前失败保留旧文件，
替换后响应丢失可以通过 GET 核对新文档。手工编辑应在 Server 停止后进行，不提供跨编辑器
或多实例协作写入，也不宣称抵御磁盘损坏。
`checkTypes` 给出静态注册类型、种子类型、说明、参数/输入/结果示例。`restartRequired` 比较配置内容。旧 `poolGroups` 字段拒绝，响应不再返回 `newScopeRequired`；窗口变更说明归 Group 生效绑定视图。

模块启动只加载一次生效快照，以它生成 Catalog、ProjectDefinition、ProjectWorkerRequirements 和创建映射。配置保存不会改变这些运行对象。
任务保存的 Group、事件、查询和契约参数也不随配置变化；运行时存储故障仅影响配置读写。

新增检查项先保存配置，再准备宿主 Group/事件/Pool/函数和真实 Worker，最后手动重启。
保存只验证配置本身；完整依赖在启动时校验，失败阻止后续启动，不补注册资源。
修改既有 Group 窗口长度须使用新 Redis scope；系统不自动清理、切换或重启。
配置文件位于环境数据目录，不进入 Git 或 ZIP；锁文件和临时文件同样不打包。
源码/ZIP 验收使用独立 JSON 文件和种子，重启证明复用同一文件。此次存储切换不改变文档结构，
使用新文件和 Redis scope，旧窗口配置字段不自动转换；不读取、迁移或删除旧 H2 数据。没有热更新、后台同步或执行期配置读取。

## 页面与结果核对

启动 [Preview](../../distribution/server/PREVIEW.md#source-launch)，打开 `/app-checks`，
登录开发身份后，先选择种子格式（电话号码/文本），再选择检查能力；Group、事件、查询与内部契约在折叠详情中只读展示。号码类型还选择国家；text 不显示国家或 CSV。前端依次创建空任务、上传种子文件，
用户核对实际数量后启动。operator 只处理当前载入范围内自己创建的任务，admin 可管理全部任务。
列表菜单管理任务，抽屉预览结果；未确认创建或导入保留请求身份及已知 Task ID，不自动重试。

配置页以整份 JSON 编辑 `common` 和 `checks`，不再提供分散表单。
前端只校验 JSON 语法与根对象，后端定位字段错误。刷新/导航保留编辑文本；指纹冲突不合并，
保存未确认保留原文本与基准指纹供读取核对。当前生效文档与契约参考折叠展示。

仅模拟契约的 admin 技术区显示“核对当前预览”，按上面的 SHA-256 协议，用存量 salt、实际 Worker、号码和模拟描述复算；
它只核对已展示内容，不证明执行次数或失败原因，模拟延迟也不是端到端耗时。
[前端 Owner](../../frontend/README.md#app-checks-task-workspace) 维护文件导入、草稿、刷新、
API/Mock 和浏览器核对的可用性及本地状态。

## 装配与证明

`BatchChecksExternalDeploymentIntegrationTest` 使用实际外部 YAML／Group JSON／检查文档，
通过同一生产装配的两次启动和参数化 JavaWorker 验证第二个 Group 接入、窗口查询、属性反馈、
独立固定摘要、逐种子 CSV、真实扣费及原 Task 快照保留。它不宣称 Android Group 身份可动态改写。

配置单测覆盖文件重开、种子不覆盖、文档损坏、键顺序/排版不影响指纹、并发指纹冲突、
同内容保存及内容恢复、提交确认丢失后的读取核对。
Boot 真实 HTTP/Redis/Worker 证明保存 App C 后 Catalog 保持原值，缺失宿主 Group 启动失败，
补齐后重启完成 C 的创建、导入、审核、查询及导出；App C 使用不同事件、Any Pool 和 registration-v1，不生成窗口投影或模拟字段；删除配置文件后这些执行路径仍工作。
Server 测试保留多 Group/多事件投影隔离、重复选择拒绝、既有批读预算和 best-effort 失败语义。场景测试证明同 Group 重复检查绑定只生成一次观察选择。
运营商协议的 Boot 测试使用明确的正面、负面及损坏响应，核对归档前后预览/CSV 一致和清理后不可读取。
源码/新 ZIP runner 则运行真实号码 Handler，对固定运营商/无映射/无效号码向量以及超过 100 条输入，
分别核对完整原始 Result、Item 状态、有限预览及三种 CSV；预期不从场景输出生成。

text 的 `uint32-prime-v1` 和 `md5-prefix-demo-batch-v1` 是 Preview 的明确演示能力（原单条 MD5 契约保留）。前者输入 `{value}`，只接受 1～10 位 ASCII 十进制数字且不超过 4294967295；允许前导零但保持原文本身份。Worker 返回 input/valid/isPrime，Scenario 只核对关联与布尔结构，不重算答案。后者复用 UTF-8 MD5 Handler，核对 input/valid/md5，固定以摘要是否以 0 开头判定，不提供表达式或前缀配置。两个契约参数均为 `{}`，details 分别保留 isPrime/md5，CSV 追加“是否素数”/“MD5 摘要”。
真实 HTTP/Redis/JavaWorker 用例使用独立筛法、边界向量及 Python hashlib，分别检查原始 Result、Item、业务答案、预览截断和完整 CSV。Boot 证明两个 text 契约归档前后答案和 CSV 保持一致。Mock 仅提供固定输入样例，不承担计算正确性证明；未接入城市、DNS 或行情服务。

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
进程 runner 使用宿主启动配置将窗口阈值设为 1000，独立种子文件只覆盖检查参数，
保留并恢复调用环境，在摘要记录覆盖值。它验证原业务闭环，不声称验证默认窗口限额。
Preview 的默认 60 秒／10 次保持。进程 runner 复用现有 Preview 启动/清理，四个 App Worker 加一个执行通用检查的 demo Worker，
先只读确认已知 App Worker 的 Properties 已上报，再在两份独立启动配置中创建八个 Task 共 60 Items，
分别覆盖全注册/全失败与全未注册/混合；参数来自检查配置而非创建请求。每份配置另在 Demo 完成 104 个运营商种子（11 Items）、105 个 MD5 种子（11 Items）及 107 个素数种子（107 Items），独立核对原始 Result、Item 终态、固定答案和预览外 CSV。两份配置分别使用
`--port` 和 `--port + 10` 为基准的独立端口组（各加 3 为 Adapter、加 4 为 Host），避免关闭 JVM
后立即复用尚未释放的 TCP 端口；启动器仍严格拒绝已占用端口。功能验收基准端口范围为 1..65521。
每份配置内每组两个 Task，保留同 Group 与跨 Group 并存覆盖，
最多每 Task 45 秒观察；独立 Python 复算成功结果和延迟。
不要求混合比例、均匀分配或固定执行者；进程 runner 不以终态失败证明执行次数。
CI 使用现有 `product_coexistence` lane 执行源码及新解压 ZIP；原 SMS/Messages
proof 显式 `app_count=0`，负载和断言保持原样。公开产物只包含阶段、数量和校验摘要，
原始日志与业务内容留在 private；每次使用唯一 test scope 并仅 SCAN/UNLINK 该 scope。

Boot 分组证明用同一份 23 个 text 种子验证配置 10/1 分别产生 3/23 Items，
配置保存及重启不改变原 Task 与请求关联；归档证明保持逐种子答案、整组异常排除和 CSV 一致。

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

## 启动预扣积分

每个 check 配置必填的正整数 `unitPrice`，内置检查初值为 1 积分／种子。
单价随首次 Task 创建写入 metadata，配置保存后重启只影响新 Task；原创建请求
仍返回原 Task 和原单价。创建、导入不扣分，不从前端传入价格或任务所有者。

启动前通过共同 Server 服务按页读取实际 Item 输入，检查 Item/Score 完整性，再由
当前 Task 的业务契约解释每组种子。按受理组内数量相加，尾组据实计数；相同 Item
重传不重复收费，不同组中的同一种子按重复检查计数。不用预览、文件摘要或
Item 数量乘分组上限推算费用。查询会保留去重 ID 集合，并非常量内存。

仍调用共同 `/api/v1/tasks/{taskId}/approve`，请求示例：

```json
{"expectedItemCount":3,"credits":{"requestId":"c759709a-fc81-47b2-a24c-4b5ea7799939","expectedSeedCount":23,"expectedAmount":23}}
```

服务端重算金额，并将启动快照关联持久保存后，扣任务所有者的账户；管理员代为
启动只改变记录的操作者。只有确认扣分成功才调用原批准 Owner。一个执行 Task
最多扣一次，调度重试、重复启动都不追加收费。余额不足保留拒绝回执，充值后
须显式重新确认新请求。金额和乘法均受 JavaScript 安全整数上限约束。

`credits` 查询先做相同的目录、Project 和任务所有权检查。待审核且尚未受理时
按需报价；已受理后读取固定快照。`requestStatus` 为 applied/rejected/not_observed，
其中 not_observed 不能当作失败。`status` 的 ready/rejected/debit_unconfirmed/charged/
started/closed 区分输入报价、扣分结论及独立 Task 状态；charged 表示已扣分而尚待批准。
请求返回 503 或丢失响应时，先核对原 requestId 或显式继续原操作；不自动补交。
未确认或已扣分的输入不能继续导入。关闭前先确认扣分结论，关闭后不会重开。

本片不处理终止返还，也不按成功、不匹配或运行错误重新结算。没有冻结余额、
逐条账务、退款接口、计费定时任务或 Kernel 计费机制。启动关联随 Task 最终清理，
账户流水继续保留。源码及 ZIP 证明通过人工调账为具名 fixture 用户准备积分，
产品与 Mock 的普通新账户仍从零余额开始。
