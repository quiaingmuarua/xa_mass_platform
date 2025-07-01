# xa-mass-eventbus

本模块为事件总线基础设施，负责：
- 事件定义与发布（基于 MassEvent 体系）
- 事件监听与处理（通过 EventBusFacade 注册 Consumer）
- 任务、设备、Token 等全局事件流

## 事件驱动架构

- 事件总线核心接口为 `EventBusFacade`，通过 `EventBusFactory.get("guava")` 获取实例。
- 所有事件均实现 `MassEvent` 接口，常见事件如：
  - 任务相关：`TaskCreatedEvent`、`TaskAuditedEvent`、`TaskAssignedEvent`（`eventbus.task` 包）
  - 设备相关：`DeviceOnlineBatchEvent`、`DeviceOfflineSingleEvent`（`eventbus.device` 包）
- 事件注册与发布示例：

```java
EventBusFacade eventBus = EventBusFactory.get("guava");
eventBus.register(TaskCreatedEvent.class, event -> {
    // 处理任务创建
});
eventBus.post(new TaskCreatedEvent(task, traceId, requestId));
```

- 事件驱动带来高内聚、低耦合、易扩展、易插拔混沌注入/监控等优势。

详细设计见 `doc/规划.md`。

## 任务状态流转

### TaskStatus 枚举

任务状态流转逻辑如下：

```
NEW -> BLOCKED -> READY -> RUNNING -> TERMINAL
  |        |        |         |         |
  |        |        |         |         |
  +--------+--------+---------+---------+
  |        |        |         |         |
  v        v        v         v         v
TERMINAL  READY   BLOCKED   PAUSED   (终态)
```

#### 状态说明

1. **NEW（新建）**
   - 任务刚创建，未审核
   - 可转换为：BLOCKED, TERMINAL

2. **BLOCKED（已阻塞）**
   - 审核通过但被阻塞，暂不可调度
   - 可转换为：READY, TERMINAL

3. **READY（待分配）**
   - 审核通过，待分配设备
   - 可转换为：RUNNING, BLOCKED, TERMINAL
   - 只有此状态的任务可以被调度

4. **RUNNING（运行中）**
   - 已调度，设备已匹配
   - 可转换为：BLOCKED, PAUSED, TERMINAL

5. **PAUSED（已暂停）**
   - 被暂停，暂不可调度
   - 可转换为：READY, TERMINAL

6. **TERMINAL（已终止）**
   - 终止，结束/异常/人工关闭
   - 终态，不可再转换

#### 状态检查方法

- `isFinal()`: 检查是否为最终状态
- `isSchedulable()`: 检查是否可以调度
- `isRunning()`: 检查是否正在运行
- `isBlocked()`: 检查是否被阻塞
- `isPaused()`: 检查是否被暂停
- `canTransitionTo(TaskStatus)`: 检查是否可以转换为目标状态

### 使用示例

```java
Task task = new Task();
task.setStatus(TaskStatus.NEW);

// 审核通过但阻塞
if (task.getStatus().canTransitionTo(TaskStatus.BLOCKED)) {
    task.setStatus(TaskStatus.BLOCKED);
}

// 解除阻塞，准备调度
if (task.getStatus().canTransitionTo(TaskStatus.READY)) {
    task.setStatus(TaskStatus.READY);
}

// 开始运行
if (task.getStatus().canTransitionTo(TaskStatus.RUNNING)) {
    task.setStatus(TaskStatus.RUNNING);
}

// 暂停任务
if (task.getStatus().canTransitionTo(TaskStatus.PAUSED)) {
    task.setStatus(TaskStatus.PAUSED);
}

// 恢复任务
if (task.getStatus().canTransitionTo(TaskStatus.READY)) {
    task.setStatus(TaskStatus.READY);
}

// 终止任务
if (task.getStatus().canTransitionTo(TaskStatus.TERMINAL)) {
    task.setStatus(TaskStatus.TERMINAL);
}
```

## 其他状态枚举

### TokenStatus
Token 状态管理，包括登录、绑定、发送等状态。

### DeviceStatus  
设备状态管理，包括在线、离线、忙碌等状态。

### TaskMsgStatus
任务消息状态管理，包括待发送、发送中、发送成功、发送失败等状态。 