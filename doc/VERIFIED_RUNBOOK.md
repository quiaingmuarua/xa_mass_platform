# XA Mass Platform Verified Runbook

最后更新：2026-04-13

本文档只记录已经被代码、测试或真实运行验证过的事实。若与历史文档冲突，以代码和运行结果为准。

## 1. 当前结论

- 真实启动入口是 `xa-mass-mock`
- `xa-mass-starter` 不是 Spring Boot 启动入口
- 默认 `dev` 启动现在会自动拉起 mock WebSocket clients
- 当前已验证 API 主链路：
  - `NEW -> READY -> RUNNING -> TERMINAL`
  - `TaskMsg INIT -> SENT -> SUCCESS`
- 暂停恢复链路也已验证：
  - `NEW -> READY -> PAUSED -> READY`

## 2. 推荐启动方式

在仓库根目录执行：

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

Windows 建议：

- 使用短 classpath：各模块 `target/classes` 加 `logs/runtime-libs/*`
- 长展开 `-cp` 在 Windows 上可能被命令行长度截断，表现成假的缺类问题
- 2026-04-13 已确认 `xa-mass-base` 去掉 `javafaker` 后，不再把 `snakeyaml-android` 带进 Spring Boot 运行时

## 3. 启动后检查点

HTTP:

```bash
curl -i http://127.0.0.1:8088/status
curl -i http://127.0.0.1:8088/status/tasks
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
```

WebSocket:

```bash
nc -zv 127.0.0.1 18088
```

默认 `dev` 启动的补充事实：

- `MockApplicationSpringBootApp` 固定激活 `dev`
- `WebSocketClientStarter` 现在由 `ApplicationReadyEvent` 触发启动
- `mock.client.auto-start=true` 时会自动加载 mock 设备并连接 `ws://localhost:18088/ws`

## 4. 当前 API 主链路

### 4.1 创建任务

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","countryCode":"us","textContent":"smoke","userId":"agent","targetList":["smoke-target-001","smoke-target-002"],"batchSize":1}'
```

已确认：

- 初始状态为 `NEW`
- `targetList` 会持久化成真实 `TaskMsg`
- `TaskManager.createTask()` 已处理 `targetList = null`，不再触发 NPE

### 4.2 审核 / 暂停 / 恢复

```bash
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/pause
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/resume
```

已确认：

- `approveTask`: `NEW, BLOCKED -> READY`
- `pauseTask`: `READY, RUNNING -> PAUSED`
- `resumeTask`: `PAUSED -> READY`
- `deleteTask` 只允许 `NEW, TERMINAL`

### 4.3 分配与运行

当前代码路径：

1. 任务进入 `READY`
2. `MassEngine` 无论 `mockMode` 与否都会启动 `TaskAssignWorker`
3. `MassEngine` 会在启动时补提已有 `READY` 任务，并订阅 approve/resume 触发的 READY 事件
4. `TaskDeviceAssignListener` 负责设备匹配
5. 匹配成功后写入 `scheduleDeviceCnt`，并把任务从 `READY` 推到 `RUNNING`
6. `SimpleTaskMsgAssignListener` 复用持久化 `TaskMsg`
7. 每条 `TaskMsg` 会绑定 `deviceId`、`tokenId`、`batchId`，并推进到 `SENT`
8. `GatewayTaskMsgPublisher` 将消息下发为 `TASK/step`

### 4.4 回执与收敛

当前代码路径：

1. mock client 收到 `TASK/step`
2. mock client 回传 `TASK/step` 结果
3. `GatewayTaskResultHandler` 调用 `TaskManager.handleTaskMessageResult(...)`
4. `TaskManager` 根据 `taskId + msgId` 更新持久化 `TaskMsg`
5. `TaskMsg` 进入 `SUCCESS` 或 `FAILED`
6. 当任务处于 `RUNNING` 且全部 `TaskMsg` 都进入终态时，任务自动收敛到 `TERMINAL`

## 5. 2026-04-13 已验证的真实 smoke

### 5.1 Mock 数据前置条件

`MassApplication.loadMockData(...)` 现在会：

- 归一化 mock 设备的 `supportedProjects` 为 `Project` 枚举
- 为缺少 token 的设备自动补一个 `LOGIN_READY` token

这修复了此前“API 审核后任务长期停在 `READY`”的问题，根因不是 worker 没跑，而是 mock 设备不满足默认规则。

### 5.2 默认 dev 启动会自动拉起 mock clients

`WebSocketClientStarter` 现在：

- 不再依赖 `client` profile 才能启动
- 改为在 `ApplicationReadyEvent` 时启动
- 受 `mock.client.auto-start` 控制

对应回归测试：

- `xa-mass-mock/src/test/java/com/xa/mass/mock/starter/WebSocketClientStarterTest.java`

### 5.3 真实 API smoke 结果

已在真实运行中的 `xa-mass-mock` 进程上验证：

1. `POST /status/api/tasks`
2. `POST /status/api/tasks/{taskId}/audit?approved=true`
3. `GET /status/api/tasks/{taskId}`
4. `GET /status/api/tasks/{taskId}/messages`

观察结果：

- 创建后状态为 `NEW`
- 审核后任务进入 `RUNNING`，随后自动收敛到 `TERMINAL`
- `scheduleDeviceCnt = 2`
- `taskExecutedNumber = 2`
- 两条 `TaskMsg` 都变为 `SUCCESS`
- 每条 `TaskMsg` 都写回了 `deviceId`

## 6. 已验证定向回归

本轮已重新验证：

```bash
mvn --% -pl xa-mass-mock -am -Dtest=WebSocketClientStarterTest,MassApplicationLoadMockDataTest,GatewayTaskMsgPublisherTest,GatewayTaskResultHandlerTest,MassEngineStopTest,MassApplicationStopOrderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- `BUILD SUCCESS`

覆盖点：

- `WebSocketClientStarterTest`
- `MassApplicationLoadMockDataTest`
- `GatewayTaskMsgPublisherTest`
- `GatewayTaskResultHandlerTest`
- `MassEngineStopTest`
- `MassApplicationStopOrderTest`

## 7. 仍然存在的缺口

- `SimpleTaskScheduler.scheduleTasks()` 仍是 stub
- 应用退出有时仍需要两次 `Ctrl-C`
- EventBus 仍未完全收敛，运行时还有 `old.eventbus`
- Redis / Database 存储仍是 fail-fast 未实现
- 当前真实主链路已能跑通，但还缺自动化 API 级集成测试
