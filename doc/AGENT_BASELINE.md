# XA Mass Platform Agent Baseline

本文件只保留后续 agent 最需要的基线事实�?
- 模块真实职责
- 当前主线与历史目录的边界
- 排查时应优先相信什�?- 已知仍未收敛的问�?
不负责记录：

- 启动命令
- 运行验证过程
- 回归测试命令
- 历史排查过程日志

这些内容分别见：

- [../AGENTS.md](../AGENTS.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)

## 1. 事实优先�?
排查时按以下顺序判断真相�?
1. 代码实际调用关系
2. 已验证运行行�?3. `AGENTS.md`
4. 本文�?5. 模块 README
6. `doc/archive/` 下的历史文档

工作规则�?
- 不要默认 README 或历史设计文档描述的是当前实�?- 如果代码、运行表现、文档三者冲突，优先相信代码和已验证运行行为
- 发现事实变化后，先修当前文档，再考虑保留历史说明

## 2. 当前主线判断

- 真实 Spring Boot 入口�?`xa-mass-mock`
- `xa-mass-runtime` 不是当前可直接运行的 Boot 入口
- API-first 任务流是当前主线真相，UI 页面只是辅助观察�?- `engine/v2` 不是当前生产主线
- 事件总线没有完全收敛，`old.eventbus` 仍在运行链中出现

## 3. 模块真相

### `xa-mass-mock`

- 当前真实可运行入�?- 串起 `api + starter + gateway + engine`
- 用于全链路验证和 mock 数据加载

### `xa-mass-runtime`

- 生命周期 / 组装�?- 负责构建和启�?`MassApplication`、`MassEngine`、`MassGateway`
- 不是当前 `spring-boot:run` 入口

### `xa-mass-api`

- REST controller、状态页、模板层
- �?`xa-mass-mock` �?Spring Boot 扫描加载
- 不是独立验证过的应用入口

### `xa-mass-engine`

- 主线业务逻辑所�?- 当前应优先关�?`TaskManager`、`DeviceManager`、`RuleManager`
- `v2` 目录与其测试属于历史资产，不应默认纳入主线判�?
### `xa-mass-core`

- Maven ģ����������Ϊ `xa-mass-core`���� Java ��·���Ա��� `com.xa.mass.base`
- 共享模型、枚举、消息抽象、JSON DSL、事件总线
- 同时存在当前和历史基础设施路径

### `xa-mass-gateway`

- WebSocket 连接、分发、会话上下文
- 已作�?mock 全链路的一部分被验�?- 不应默认视为独立可运行应�?
## 4. 关键收敛结论

### 启动�?
- 入口应从 `xa-mass-mock` 开�?- 不要�?`xa-mass-runtime` 倒推“它就是运行入口�?
### 任务生命周期

- 生命周期的业务真相应�?`TaskManager` + `TaskStatus` 为准
- API 控制器当前已基本对齐 `TaskManager`
- 文档中若出现�?`TaskStatus.canTransitionTo()` 冲突的规则，应视为过�?
### 事件总线

- 新旧事件总线并存
- 不要默认 `StreamEventBusFacade` 已完全替�?Guava 路径
- 讨论事件总线问题时，先看调用点，再看架构文档

### 测试�?
- 主线回归应优先看 engine/api/mock 当前测试
- `xa-mass-engine/src/test/java/com/xa/mass/engine/v2/**` 属于历史测试�?
## 5. 当前已知未收敛问�?
- `SimpleTaskScheduler.scheduleTasks()` 仍是 stub
- 运行中的应用退出仍可能需要两次中�?- EventBus 仍未收敛到单一路径
- Redis / Database storage 仍是 fail-fast 占位实现
- API 端到端覆盖仍偏薄，尤其是失败路径、终止路径、重复回调路�?
## 6. 建议的排查起�?
按问题类型优先打开这些文件�?
### 启动 / 运行

- `xa-mass-mock/src/main/java/com/xa/mass/mock/MockApplicationSpringBootApp.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassApplication.java`
- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`

### 生命周期 / API

- `xa-mass-api/src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
- `xa-mass-core/src/main/java/com/xa/mass/base/enums/task/TaskStatus.java`

### 分配 / 回写

- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskDeviceAssignListener.java`
- `xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskMsgAssignListener.java`
- `xa-mass-gateway` 下的任务发布与结果处理链�?
### 事件总线

- `xa-mass-runtime/src/main/java/com/xa/mass/starter/MassEngine.java`
- `xa-mass-core` �?`old.eventbus` �?`channel.eventbus` 的实际调用点

## 7. 对后�?agent 的要�?
后续 agent 在没有额外验证前，不要默认：

- `starter` 就是唯一入口
- `v2` 就是当前主线
- �?EventBus 已完全替代旧 EventBus
- 历史 API 文档仍与实现一�?- 文档里写到的能力都已经可�?
更好的做法是�?
- 从真实入口和真实调用点开�?- 先补测试再修行为
- 修完行为后同步更新当前文�?- 把历史说明留�?`doc/archive/`，不要再回流到主入口文档



