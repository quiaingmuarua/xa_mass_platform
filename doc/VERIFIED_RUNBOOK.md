# XA Mass Platform Verified Runbook

最后更新：2026-04-13

本文档只记录当前已经由代码和测试确认的主线事实，优先级低于源码，高于仓库里的历史说明。

## 1. 目标

- 给后续 agent 和开发者一个可复用的启动、验证、排障入口
- 先服务于 API-first 的任务主流程收敛
- 页面能力可以作为验证面，但当前不作为事实来源

## 2. 当前主线结论

- 真实启动入口是 `xa-mass-mock`
- `xa-mass-starter` 不是 Spring Boot 入口
- 任务生命周期主线目前可按以下路径验证
  - `NEW -> READY -> PAUSED -> READY`
  - `READY -> RUNNING -> TERMINAL`
- 当前已收敛的执行闭环是
  - 创建任务
  - 审核任务
  - 设备匹配与分配
  - WebSocket 下发 `TASK/step`
  - 客户端回执 `TASK/step`
  - 回写 `TaskMsg` / 汇总任务进度

## 3. 启动方式

在仓库根目录执行：

```bash
./mvnw -DskipTests compile
./mvnw -pl xa-mass-mock -am dependency:build-classpath \
  -Dmdep.outputFile=/tmp/xa-mass-mock.cp \
  -DincludeScope=runtime
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:$(cat /tmp/xa-mass-mock.cp)" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

不要把下面这条当成当前可验证入口：

```bash
cd xa-mass-mock
mvn spring-boot:run
```

## 4. 启动后的验证点

HTTP：

```bash
curl -i http://127.0.0.1:8088/status
curl -i http://127.0.0.1:8088/status/tasks
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
curl -i http://127.0.0.1:8088/api/session/list
curl -i http://127.0.0.1:8088/api/session/stats
curl -i http://127.0.0.1:8088/api/queue/status
```

WebSocket：

```bash
nc -zv 127.0.0.1 18088
```

## 5. 当前任务主流程

### 5.1 创建任务

通过：

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","countryCode":"us","textContent":"smoke","userId":"agent","targetList":["smoke-target-001","smoke-target-002"],"batchSize":1}'
```

当前已确认：

- 初始状态为 `NEW`
- `targetList` 会落成真实的 `TaskMsg`
- `targetList = null` 不再触发 NPE

### 5.2 审核 / 暂停 / 恢复

```bash
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/pause
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/resume
```

当前已确认：

- `approveTask`: `NEW, BLOCKED -> READY`
- `pauseTask`: `READY, RUNNING -> PAUSED`
- `resumeTask`: `PAUSED -> READY`
- `deleteTask` 只允许 `NEW, TERMINAL`

### 5.3 分配与运行

当前代码路径：

1. 任务进入 `READY`
2. `MassEngine` 无论 `mockMode` 与否都会启动 `TaskAssignWorker`
3. `MassEngine` 会：
   - 启动时提交已有 `READY` 任务
   - 对 `approveTask()` / `resumeTask()` 触发的 READY 事件订阅提交
4. `TaskDeviceAssignListener` 负责设备匹配
5. 匹配成功后：
   - 设置 `scheduleDeviceCnt`
   - 将任务从 `READY` 转成 `RUNNING`
6. `SimpleTaskMsgAssignListener` 不再新建一批临时消息，而是复用已持久化的 `TaskMsg`
7. 每条 `TaskMsg` 会绑定：
   - `deviceId`
   - `tokenId`
   - `batchId`
   - 状态推进到 `SENT`
8. `GatewayTaskMsgPublisher` 将这些消息转成 `TASK/step` 下发到 WebSocket 输出链路

### 5.4 回执与完成

当前代码路径：

1. 客户端回传 `TASK/step`
2. `MassApplication` 在 gateway registry 中注册了 `GatewayTaskResultHandler`
3. handler 调用 `TaskManager.handleTaskMessageResult(taskId, msgId, success, detail)`
4. `TaskManager` 会：
   - 按 `taskId + msgId` 找到持久化 `TaskMsg`
   - 更新 `TaskMsg` 为 `SUCCESS` 或 `FAILED`
   - 回算 `taskExecutedNumber`
   - 当 RUNNING 任务的全部消息进入终态时，将任务收敛为 `TERMINAL`

当前 mock 客户端也已经补齐：

- 回执 `project`
- `response=true`
- `TASK/step` 回执 payload 中的 `status`

## 6. 当前仍然存在的问题

- `SimpleTaskScheduler.scheduleTasks()` 仍是 stub
  - 但主线 `READY -> RUNNING` 已不再依赖这条调度 API
- 应用退出有时仍需两次 Ctrl-C
- EventBus 仍未完成收敛，运行时还有 `old.eventbus` 路径
- Redis / Database 存储仍是 fail-fast 未实现状态
- 最高价值缺口已经从引擎单测转移到 API 级集成验证

## 7. 当前推荐验证顺序

1. 先用 API 验证 `create -> approve -> assign -> run`
2. 再用 mock WebSocket 客户端验证 `TASK/step` 下发与回执
3. 最后再用页面验证同一条链路，不要反过来

## 8. 最新已验证测试

本次修改后，已验证的定向回归命令：

```bash
mvn --% -pl xa-mass-engine,xa-mass-starter,xa-mass-gateway,xa-mass-mock -am -Dtest=TaskManagerLifecycleTest,SimpleTaskMsgAssignListenerTest,TaskDeviceAssignListenerTest,GatewayTaskMsgPublisherTest,GatewayTaskResultHandlerTest,MassEngineStopTest,ProcessEnvelopeMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- `BUILD SUCCESS`

本次定向回归覆盖了：

- `TaskManagerLifecycleTest`：11
- `SimpleTaskMsgAssignListenerTest`：4
- `TaskDeviceAssignListenerTest`：5
- `GatewayTaskMsgPublisherTest`：1
- `GatewayTaskResultHandlerTest`：2
- `MassEngineStopTest`：5
- `ProcessEnvelopeMiddlewareTest`：4

## 9. 仍推荐的完整回归

完整回归命令仍然是：

```bash
./mvnw clean test
```

但本轮文档只声明上面“第 8 节”的定向命令为当前已重新验证结果。
