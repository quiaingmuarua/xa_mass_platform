# XA Mass Platform Agent Baseline

本文件只记录已经核实过的事实，给后续 agent 当“真实起点”用。

## 1. 当前仓库状态

- 项目类型：Maven 多模块 Java 项目
- 根模块：`xa_mass_platform`
- 子模块：
  - `xa-mass-base`
  - `xa-mass-engine`
  - `xa-mass-gateway`
  - `xa-mass-api`
  - `xa-mass-starter`
  - `xa-mass-mock`
- 编译验证：
  - 2026-04-12 在仓库根目录执行 `./mvnw -q -DskipTests compile`
  - 结果：通过

结论：项目不是“已经完全坏掉”，至少当前代码在编译层面仍然连得上。

## 2. 推荐的事实优先级

后续排查时，建议按下面顺序判断真相：

1. 代码实际引用关系
2. 能否编译/启动/访问接口
3. 模块级 README
4. 顶层 README / API 文档 / QUICK_REFERENCE
5. `old` / `v2` / `refactor` / `todo` / `daily` 类文档

原因：仓库内存在大量历史文档，且明显出现“描述超前于实现”的情况。

## 3. 真实启动链

### 已核实入口

- 真正的 Spring Boot 入口在：
  - `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- 这个类：
  - 带 `@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.api"})`
  - 启动 Spring Boot Web 服务
  - 在 `CommandLineRunner` 里通过 `MassApplicationBuilder` 启动 Gateway + Engine

### 非真实入口

- `xa-mass-starter/src/main/java/com/xa/mass/starter/MassApplication.java`
  - 这是生命周期/组装类，不是 Spring Boot 入口
- `xa-mass-starter` 模块没有发现 `@SpringBootApplication`

### 文档偏差

- 顶层 `README.md` 说“进入 `xa-mass-starter` 运行 `MassApplication.java`（Spring Boot 入口）”
- 这与代码不符

## 4. 模块实际职责

### `xa-mass-mock`

当前最像“集成运行壳”的模块。

- 提供 Spring Boot 入口
- 提供 Manager Bean 装配
- 负责把 `api + starter + gateway + engine` 串起来
- 也是现在最可能成功跑起来的模块

### `xa-mass-starter`

当前更像“手工组装器”。

- `MassApplicationBuilder`：聚合配置，构建 `MassApplication`
- `MassApplication`：启动 Gateway / Engine / WebSocket Server
- `MassEngine`：包装引擎相关资源启动逻辑

注意：

- `xa-mass-starter/pom.xml` 只直接依赖 `engine` 和 `gateway`
- 但 README 把自己描述成“启动和聚合 gateway、engine、api 等”
- 从代码看，API 不是由 starter 自己直接启动，而是由 `xa-mass-mock` 的 Spring Boot 扫描拉起来

### `xa-mass-api`

- 有 Controller、模板页面、Knife4j 依赖
- 但不是独立启动应用
- 当前依赖于上层 Spring Boot 入口扫描加载

### `xa-mass-engine`

- 仍然承载核心任务/设备/规则逻辑
- 同时存在：
  - 主线类：`TaskManager` / `DeviceManager` / `RuleManager`
  - `v2` 目录
  - 多份 refactor / optimization / todo 文档

结论：这是“主线实现 + 半完成重构并存”的状态，不宜默认 `v2` 已落地。

### `xa-mass-base`

- 同时承载基础模型、消息通道、JSON DSL、事件总线
- 也同时存在：
  - `channel/eventbus`
  - `old/eventbus`

这说明事件系统并未真正完全收敛到一套实现。

## 5. 已确认的文档/实现不一致

### 5.1 EventBus 叙事不一致

顶层 README 和部分文档在强调：

- 新版泛型 EventBus
- `StreamEventBusFacade`
- Guava 旧版已废弃

但实际运行链中仍直接引用旧实现：

- `xa-mass-starter/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/service/AuditService.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/service/AssignmentService.java`

这些代码仍在使用：

- `com.xa.mass.base.old.eventbus.core.EventBusFactory`
- `EventBusFactory.get("guava")`

结论：文档中的“旧版已废弃”目前不能当真，真实情况更接近“新旧两套并存，运行链仍依赖旧版”。

### 5.2 启动说明不一致

文档说法：

- 从 `xa-mass-starter` 启动整个平台

代码事实：

- 真正可启动的 Spring Boot 入口在 `xa-mass-mock`

### 5.3 Task API 行为与文档抽象不一致

文档大量以 `TaskManager.approveTask/pauseTask/resumeTask` 的能力描述任务流转。

这个问题在本轮检查开始时确实存在，但 2026-04-12 已修正：

- `TaskApiController` 的审核 / 暂停 / 恢复 / 中止接口
- `PUT /status/api/tasks/{taskId}/status`

现在都改为优先走 `TaskManager.approveTask()` / `pauseTask()` / `resumeTask()` / `cancelTask()` / `rejectTask()`

当前结论：

- 文档与实现曾经不一致
- 但这部分现在已经基本收敛到统一生命周期入口
- 后续再看任务状态问题时，应优先检查 `TaskStatus.canTransitionTo()` 与 `TaskManager` 是否一致

### 5.4 文档覆盖范围明显夸大

例如 `DOCUMENTATION_INDEX.md` 中有类似：

- “All public APIs, functions, and components documented”
- “Living Documentation”

结合仓库现状，这类表述不应继续视为可靠输入，而应视作历史宣传文本。

## 6. 当前更可信的运行方式

如果后续要做真实验证，优先从 `xa-mass-mock` 路线开始：

1. 启动 `MockApplicationSpringBootApp`
2. 看 `http://localhost:8088/status`
3. 看 `http://localhost:8088/doc.html`
4. 看 WebSocket 是否监听 `ws://localhost:18088/ws`

相关配置位于：

- `xa-mass-mock/src/main/resources/application.yml`

## 7. 建议的下一阶段检查顺序

建议不要先改架构，先做“收敛现实”。

### 阶段 A：运行时真相盘点

- 实测 mock 启动是否成功
- 记录 REST 页面、WebSocket、Redis 依赖是否都可用
- 确认真正需要的外部依赖

### 阶段 B：接口真相盘点

- 以 Controller 为起点，反查到 Manager/Service
- 输出“接口 -> 真实执行路径 -> 是否绕过业务入口”的映射

### 阶段 C：事件系统收敛

- 列出哪些模块在用 `old.eventbus`
- 列出哪些模块只在文档里提到 `channel.eventbus`
- 判断是迁移到新 EventBus，还是明确回退到旧 EventBus 作为当前标准

### 阶段 D：文档分级

- `Verified`: 已由代码/运行验证
- `Historical`: 历史设计稿，可参考不可直接信
- `Stale`: 明显失效

## 8. 后续 agent 工作约束

后续 agent 在没有额外验证前，不要默认以下命题成立：

- “starter 就是唯一入口”
- “新 EventBus 已完全替代旧版”
- “v2 是当前主线”
- “API 文档与实现一致”
- “README 里的能力都已可用”

后续 agent 可以先把下面几类问题当成高优先级：

- 启动链和运行路径不一致
- API 接口绕过核心业务入口
- 新旧架构并存但没有主从标记
- 文档没有可靠分层

## 9. 本轮检查产出

本轮只做了基线核实，尚未做：

- 实际启动应用
- 调接口验功能
- 跑测试集
- 清理失效文档
- 改代码修行为

这意味着当前结论适合当“下一轮排查地图”，还不是最终诊断报告。

## 10. 第二轮运行验证（2026-04-12）

这一节记录实际启动与接口验证结果。

### 10.1 文档命令 vs 真实运行命令

#### 文档命令不可直接用

`xa-mass-mock/README.md` 中推荐的：

- `mvn spring-boot:run -Dspring-boot.run.profiles=dev`

在模块目录直接执行时失败。

失败原因不是业务代码本身，而是：

- `xa-mass-mock` 不是可以独立解析同仓库依赖的自洽启动模块
- 直接单模块跑时，`xa-mass-engine`、`xa-mass-base` 等依赖类无法从本地 Maven 仓库解析到

从根目录执行：

- `./mvnw -pl xa-mass-mock spring-boot:run`

也失败，因为 Spring Boot Maven 插件先落在聚合根 `xa_mass_platform` 上执行，而根模块没有 main class。

#### 这次验证使用的可行方式

1. 在仓库根目录执行：
   - `./mvnw -DskipTests compile`
2. 生成运行时 classpath
3. 用 `java -cp ... com.xa.mass.mock.MockApplicationSpringBootApp` 直接启动

结论：项目“可以启动”，但不是按 README 写的方式启动。

### 10.2 实际启动结果

实测成功启动：

- Spring Boot Web
- Tomcat `8088`
- Gateway
- Engine
- WebSocket Server `18088`
- Mock 数据加载

日志中已确认：

- Web 服务启动成功
- WebSocket 服务监听成功
- Mock 数据装载成功
- 初始任务事件发布成功

### 10.3 已验证可用的页面与接口

以下接口实测返回 `200`：

- `GET /status`
- `GET /status/tasks`
- `GET /doc.html`
- `GET /actuator/health`
- `GET /api/session/list`
- `GET /api/session/stats`
- `GET /api/queue/status`
- `GET /status/api/tasks/{taskId}`
- `POST /status/api/tasks/{taskId}/audit`
- `POST /status/api/tasks/{taskId}/pause`

### 10.4 运行时观察到的真实数据

`/status` 页面显示：

- 任务数：5
- 设备数：150
- 规则数：5
- Token 数：0

`/actuator/health` 返回：

- `{"status":"UP"}`

`nc -zv 127.0.0.1 18088` 返回成功，说明 WebSocket 端口确实在监听。

### 10.5 运行时发现的能力边界

#### `session` 和 `queue` 接口是“能用但很薄”

`/api/session/list` 与 `/api/session/stats` 当前只返回：

- `sessionManager.toString()`

没有真正的在线会话明细和统计数字。

`/api/queue/status` 当前返回：

- `inputQueue: 0`
- `outputQueue: 0`

这说明接口能通，但观测能力比较浅。

#### 任务流转接口确实生效

实测：

- `POST /status/api/tasks/{id}/audit?approved=true...`
  - 返回 `newStatus = READY`
- 顺序执行 `POST /status/api/tasks/{id}/pause`
  - 再 `GET /status/api/tasks/{id}`
  - 实际状态变为 `PAUSED`

结论：这些 UI 按钮背后的接口不是纯 mock 文案，确实会改动 `Task` 状态。

#### 任务详情暴露出一处数据质量问题

`GET /status/api/tasks/{id}` 返回的：

- `targetList` 是一串空字符串

但任务本身又有：

- `taskInitNumber = 10`
- `taskValidNumber = 10`

这说明 mock 任务消息虽然创建了，但 `target` 字段内容可能没有正确灌入，或者展示逻辑读取了空值。

这属于后续应优先排查的“功能不是完全假的，但数据语义不对”类型问题。

进一步排查后，已确认这个问题至少由两个因素叠加造成：

1. `mock/mock_tasks.json` 中的 `targetList` DSL 定义为：
   - `MODEL = java.lang.String`
   - `FIELDS = {}`
   这会生成空字符串列表，而不是手机号/账号等真实 target
2. `TaskManager.createTask()` 中创建 `TaskMsg` 的代码是：
   - `new TaskMsg(tid, msgId, target)`
   但 `TaskMsg` 构造函数签名实际是：
   - `TaskMsg(String msgId, String taskId, String target)`

这说明：

- `msgId` 和 `taskId` 被传反了
- 当前 API 返回的 `targetList` 虽然能取到 `TaskMsg.target`
- 但 mock 数据本身就是空字符串

结论：`targetList` 为空不是单点 bug，而是“mock 数据定义不完整 + 任务消息构造参数顺序错误”两层问题叠加。

## 11. 第三轮行为收敛（2026-04-12）

这一轮处理的是“接口已经改走 TaskManager，但状态机规则还没有完全对齐”的问题。

### 11.1 已修正的问题

- `TaskApiController` 已改走 `TaskManager` 生命周期入口
- `TaskStatus.canTransitionTo()` 之前遗漏了 `READY -> PAUSED`
- 这会导致：
  - API/页面判断认为任务可以暂停
  - `TaskManager.pauseTask()` 也允许 `READY` 进入暂停
  - 但底层 `task.transitionTo(PAUSED)` 实际失败

已修复为：

- `READY` 允许转换到 `PAUSED`
- 状态注释同步补成 `NEW -> READY/BLOCKED -> RUNNING/PAUSED/BLOCKED -> TERMINAL`

### 11.2 已完成的 smoke 验证

使用临时任务 `451cdd89-0f55-428f-9837-0c5dd3413096` 实测：

1. 创建后初始状态为 `NEW`
2. `POST /status/api/tasks/{id}/audit?approved=true`
   - 返回 `newStatus = READY`
3. `POST /status/api/tasks/{id}/pause`
   - 返回成功
4. 再读取详情
   - 状态为 `PAUSED`
5. `POST /status/api/tasks/{id}/resume`
   - 返回成功
6. 再读取详情
   - 状态回到 `READY`
7. 在 `READY` 状态下执行拒绝审核
   - 返回 `当前任务状态不允许审核`

当前结论：

- 任务生命周期接口现在已经能稳定走通 `NEW -> READY -> PAUSED -> READY`
- 非法状态动作会被拒绝
- 任务状态展示、控制器入口、`TaskManager`、`TaskStatus` 四层已基本一致

### 11.3 当前剩余高优先级问题

- 停机链路仍不稳定，经常需要第二次中断才完全退出
- EventBus 仍是新旧并存，主线标准未收敛
- 页面与接口虽然能用了，但 `session/queue` 一类观测接口仍偏占位

## 12. 第四轮测试收敛（2026-04-12）

这一轮处理的是“把已经验证过的生命周期行为固化成可回归测试”。

### 12.1 已新增/修正的测试

- `xa-mass-base/src/test/java/com/xa/mass/base/enums/task/TaskStatusTest.java`
  - 修正为与当前真实状态机一致
  - 已覆盖：
    - `NEW -> READY`
    - `READY -> PAUSED`
- `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java`
  - 新增最小生命周期测试
  - 已覆盖：
    - 创建任务后消息 target / taskId / msgId 语义正确
    - `NEW -> READY -> PAUSED -> READY`
    - 非法动作会被拒绝

### 12.2 已确认的旧测试债

`xa-mass-engine/src/test/java/com/xa/mass/engine/v2/**` 下存在一批历史测试/示例：

- 依赖已经不存在的 `com.xa.mass.base.channel.messaging.*`
- 本质上属于“旧 v2 试验代码”，不是当前主线
- 会把 `mvn test` 的 `testCompile` / `surefire` 一起拖挂

当前处理方式：

- 在 `xa-mass-engine/pom.xml` 中显式排除了这些失效的 v2 测试/示例
- 让当前主线测试面恢复可运行

当前结论：

- `TaskManagerLifecycleTest` 在 `-pl xa-mass-engine -am clean test` 场景下已跑通
- `xa-mass-engine` 的主线测试现在可以围绕真实实现继续补
- `v2` 目录目前应视为历史资产，不应再默认纳入主线回归

### 10.6 第二轮后的结论更新

当前更准确的描述应当是：

- 项目主链可启动
- 运行入口在 `xa-mass-mock`
- 启动文档大部分不可靠
- 页面和核心接口基本可访问
- 核心任务状态流转至少部分真实可用
- 观测接口存在“占位实现”
- mock 数据质量和文档一致性仍有明显问题

### 10.7 关闭行为补充

实测停止时：

- 第一次中断会进入关闭日志
- 但进程没有立刻完全退出
- 需要再次中断才真正结束

这与当前代码现状一致：

- `MassEngine.stop()` 只有非常轻量的停止逻辑
- `MassApplication.stop()` 里也有 `TODO`

结论：运行链可启动，但停止链路仍不够扎实，后续可能需要补线程池/dispatcher/websocket 关闭收口。
