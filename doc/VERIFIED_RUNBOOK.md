# XA Mass Platform Verified Runbook

本文件只记录 2026-04-12 已实测通过的运行方式。

## 1. 目标

给后续 agent 或开发者一个可直接复用的启动/验证入口，避免继续使用仓库里已经失真的启动说明。

## 2. 已验证前提

- JDK 17+ 可用
- 在仓库根目录执行
- 不依赖 Redis 即可启动主链
- 当前主链入口是 `xa-mass-mock`

## 3. 不要直接照做的命令

以下命令在当前仓库状态下不可靠：

### 3.1 顶层 README 的 starter 入口

- 不要把 `xa-mass-starter/MassApplication.java` 当成 Spring Boot 入口

### 3.2 `xa-mass-mock/README.md` 里的单模块运行

以下命令当前会误导：

```bash
cd xa-mass-mock
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

原因：

- `xa-mass-mock` 不是一个可独立解析同仓库模块依赖的自洽启动单元
- 单模块跑时会出现“找不到 engine/base/gateway 类”的编译错误

## 4. 当前可行的启动方式

### 4.1 编译整个 reactor

在仓库根目录执行：

```bash
./mvnw -DskipTests compile
```

### 4.2 生成运行时 classpath

```bash
./mvnw -pl xa-mass-mock -am dependency:build-classpath \
  -Dmdep.outputFile=/tmp/xa-mass-mock.cp \
  -DincludeScope=runtime
```

### 4.3 直接启动真实入口

```bash
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:$(cat /tmp/xa-mass-mock.cp)" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

## 5. 启动成功后的验证点

### 5.1 HTTP

```bash
curl -i http://127.0.0.1:8088/status
curl -i http://127.0.0.1:8088/status/tasks
curl -i http://127.0.0.1:8088/doc.html
curl -i http://127.0.0.1:8088/actuator/health
curl -i http://127.0.0.1:8088/api/session/list
curl -i http://127.0.0.1:8088/api/session/stats
curl -i http://127.0.0.1:8088/api/queue/status
```

已实测结果：

- `/status` 返回 `200`
- `/status/tasks` 返回 `200`
- `/doc.html` 返回 `200`
- `/actuator/health` 返回 `{"status":"UP"}`
- `/api/session/*` 返回 `200`
- `/api/queue/status` 返回 `200`

### 5.2 WebSocket 端口

```bash
nc -zv 127.0.0.1 18088
```

已实测结果：

- 连接成功

## 6. 已验证的运行时现状

启动后页面展示：

- 任务数：5
- 设备数：150
- 规则数：5
- Token 数：0

说明：

- 这套 mock 主链当前使用的是内存队列
- 没有 Redis 也能把 Web + Gateway + Engine + WebSocket 跑起来

## 7. 已验证的任务接口

示例任务 ID 可从 `/status/tasks` 页面取得，也可以直接创建一条临时 smoke 任务。

### 7.0 创建 smoke 任务

```bash
curl -s -X POST http://127.0.0.1:8088/status/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"smoke-lifecycle","project":"demoApp","countryCode":"us","textContent":"smoke","userId":"agent","targetList":["smoke-target-001","smoke-target-002"],"batchSize":1}'
```

已实测：

- 返回 `success = true`
- 返回新的 `taskId`
- 新建任务初始状态为 `NEW`

### 7.1 读取任务详情

```bash
curl -i http://127.0.0.1:8088/status/api/tasks/{taskId}
```

### 7.2 审核任务

```bash
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=true&comment=smoke"
```

已实测：

- 返回 `newStatus = READY`

### 7.3 暂停任务

```bash
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/pause
```

已实测：

- 返回成功
- 再读取详情时状态确实变为 `PAUSED`

### 7.4 恢复任务

```bash
curl -i -X POST http://127.0.0.1:8088/status/api/tasks/{taskId}/resume
```

已实测：

- 返回成功
- 再读取详情时状态确实回到 `READY`

### 7.5 非法动作边界

例如任务已经是 `READY` 时，再执行拒绝审核：

```bash
curl -i -X POST "http://127.0.0.1:8088/status/api/tasks/{taskId}/audit?approved=false&comment=invalid-after-ready"
```

已实测：

- 返回 `success = false`
- 返回 `当前任务状态不允许审核`

## 8. 当前已知问题

### 8.1 文档命令过时

- README 中的启动命令不能直接代表真实启动方式

### 8.2 任务生命周期现在已基本收敛

已确认：

- `TaskApiController` 不再直接改 `Task.status`
- 生命周期接口已走 `TaskManager`
- `READY -> PAUSED -> READY` 已经实测通过

之前存在的 `READY` 无法暂停问题，根因是 `TaskStatus.canTransitionTo()` 漏掉了 `READY -> PAUSED`，现已修复。

### 8.3 任务目标数据问题已修复

`GET /status/api/tasks/{id}` 现在会返回真实 `targetList`，不再是一串空字符串。

例如可看到类似：

- `target-us-000`
- `target-us-001`
- `smoke-target-001`
- `smoke-target-002`

### 8.4 停机链路不够稳

实测停止时：

- 第一次中断进入关闭流程
- 第二次中断才完全退出

## 9. 推荐的后续排查顺序

1. 补一组任务生命周期测试，固化 `NEW/READY/PAUSED/TERMINAL` 边界
2. 清理失效启动文档
3. 收敛旧/新 EventBus 的主线标准
4. 补强 shutdown 行为
5. 继续核实 `session/queue` 观测接口到底哪些是真功能、哪些是占位

## 10. 已验证的测试命令

### 10.1 engine 主线回归

```bash
./mvnw -pl xa-mass-engine -am clean test
```

2026-04-12 已实测：

- `BUILD SUCCESS`
- `xa-mass-engine` 当前主线进入回归的测试只有 2 组：
  - `com.xa.mass.engine.listener.TaskDeviceAssignListenerTest`
  - `com.xa.mass.engine.TaskManagerLifecycleTest`
- 共 `7` 个测试，`0` failures，`0` errors

### 10.2 生命周期单测

```bash
./mvnw -pl xa-mass-engine -am clean \
  -Dtest=TaskManagerLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

用途：

- 只验证任务生命周期主链
- 避免 reactor 上游模块因为“不含该测试类”而直接失败

### 10.3 当前测试面说明

`xa-mass-engine/src/test/java/com/xa/mass/engine/v2/**` 下的历史测试/示例已从主线测试流程排除，因为它们依赖已经不存在的 `com.xa.mass.base.channel.messaging.*` 包树。

### 10.4 API 控制器测试

```bash
./mvnw -pl xa-mass-api -am clean \
  -Dtest=TaskApiControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

2026-04-12 已实测：

- `com.xa.mass.api.internal.TaskApiControllerTest`
- 共 `14` 个测试，`0` failures，`0` errors
- 已覆盖：
  - `createTask`
  - `getTask`
  - `getTask` not-found 分支
  - `DELETE /{taskId}`
  - `PUT /{taskId}`
  - `PUT /{taskId}` not-found 分支
  - `GET /{taskId}/messages`
  - `audit`
  - `pause`
  - `resume`
  - `terminate`
  - `PUT /status` 对 `approve/resume` 分支选择
