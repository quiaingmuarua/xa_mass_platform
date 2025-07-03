# 结构化JSON日志配置说明

## 概述

本项目已配置结构化JSON日志，使用 `logstash-logback-encoder` 将日志输出为JSON格式，便于后续使用SIGOZ等日志分析工具进行日志分析。

## 配置说明

### 1. 依赖配置

在根 `pom.xml` 中添加了 `logstash-logback-encoder` 依赖：

```xml

<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### 2. 日志配置文件

#### 基础配置 (`logback.xml`)

- 位置：`xa-mass-mock/src/main/resources/logback.xml`
- 功能：提供基本的JSON日志输出
- 输出：控制台 + 文件

#### 完整配置 (`logback-json.xml`)

- 位置：`xa-mass-mock/src/main/resources/logback-json.xml`
- 功能：提供完整的结构化日志配置，包括业务日志和错误日志分离
- 输出：控制台 + 文件 + 错误日志文件 + 业务日志文件

### 3. 日志字段说明

JSON日志包含以下字段：

| 字段名          | 说明    | 示例值                              |
|--------------|-------|----------------------------------|
| `@timestamp` | 时间戳   | `2024-01-01T12:00:00.000Z`       |
| `level`      | 日志级别  | `INFO`, `ERROR`, `WARN`          |
| `thread`     | 线程名   | `main`, `task-executor-1`        |
| `logger`     | 日志器名称 | `com.xa.mass.engine.TaskManager` |
| `message`    | 日志消息  | `任务创建成功`                         |
| `stackTrace` | 异常堆栈  | 异常时的堆栈信息                         |
| `mdc`        | MDC字段 | 结构化字段集合                          |
| `app`        | 应用名称  | `xa-mass-platform`               |
| `version`    | 应用版本  | `0.0.1-SNAPSHOT`                 |

### 4. MDC字段说明

通过 `LogUtils` 工具类可以设置以下MDC字段：

| 字段名         | 说明   | 设置方法                      |
|-------------|------|---------------------------|
| `traceId`   | 跟踪ID | `LogUtils.setTraceId()`   |
| `userId`    | 用户ID | `LogUtils.setUserId()`    |
| `deviceId`  | 设备ID | `LogUtils.setDeviceId()`  |
| `taskId`    | 任务ID | `LogUtils.setTaskId()`    |
| `tokenId`   | 令牌ID | `LogUtils.setTokenId()`   |
| `operation` | 操作类型 | `LogUtils.setOperation()` |
| `module`    | 模块名  | `LogUtils.setModule()`    |
| `result`    | 操作结果 | `LogUtils.setResult()`    |
| `duration`  | 执行时长 | `LogUtils.setDuration()`  |
| `errorCode` | 错误代码 | `LogUtils.setErrorCode()` |

## 使用方法

### 1. 基本日志记录

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger logger = LoggerFactory.getLogger(YourClass.class);

logger.

info("这是一条信息日志");
logger.

warn("这是一条警告日志");
logger.

error("这是一条错误日志");
```

### 2. 结构化日志记录

```java
import com.xa.mass.engine.util.LogUtils;

// 记录操作开始
LogUtils.logOperationStart("CREATE_TASK","TaskManager",
                                   "taskName","测试任务",
                                   "project","demo");

// 记录操作成功
LogUtils.

logOperationSuccess("任务创建成功",150);

// 记录操作失败
LogUtils.

logOperationFailure("TASK_CREATE_ERROR","参数错误",50);

// 记录设备操作
LogUtils.

logDeviceOperation("device-001","LOGIN","SUCCESS");

// 记录任务操作
LogUtils.

logTaskOperation("task-001","ASSIGN","SUCCESS");

// 记录令牌操作
LogUtils.

logTokenOperation("token-001","VALIDATE","SUCCESS");

// 记录规则评估
LogUtils.

logRuleEvaluation("rule-001","device-001","task-001",true);

// 记录任务分配
LogUtils.

logTaskAssignment("task-001","device-001","SUCCESS");
```

### 3. 手动设置MDC字段

```java
// 设置单个字段
LogUtils.setTraceId("trace-12345");
LogUtils.

setUserId("user-001");
LogUtils.

setDeviceId("device-001");

// 记录日志（会自动包含MDC字段）
logger.

info("包含MDC字段的日志");

// 清除MDC字段
LogUtils.

clearMdc();
```

## 日志输出示例

### 控制台输出（开发环境）

```json
{
  "timestamp": "2024-01-01 12:00:00.123",
  "level": "INFO",
  "thread": "main",
  "logger": "com.xa.mass.engine.TaskManager",
  "message": "任务创建成功: 结果=SUCCESS, 耗时=150ms",
  "mdc": {
    "traceId": "trace-12345",
    "taskId": "task-001",
    "operation": "CREATE_TASK",
    "module": "TaskManager",
    "result": "SUCCESS",
    "duration": "150"
  }
}
```

### 文件输出（生产环境）

```json
{
  "@timestamp": "2024-01-01T12:00:00.123Z",
  "level": "INFO",
  "thread": "main",
  "logger": "com.xa.mass.engine.TaskManager",
  "message": "任务创建成功: 结果=SUCCESS, 耗时=150ms",
  "mdc": {
    "traceId": "trace-12345",
    "taskId": "task-001",
    "operation": "CREATE_TASK",
    "module": "TaskManager",
    "result": "SUCCESS",
    "duration": "150"
  },
  "app": "xa-mass-platform",
  "version": "0.0.1-SNAPSHOT"
}
```

## 日志文件说明

### 日志文件位置

- 主日志文件：`logs/xa-mass-platform.log`
- 错误日志文件：`logs/xa-mass-platform-error.log`
- 业务日志文件：`logs/xa-mass-platform-business.log`

### 日志轮转策略

- 按日期轮转：每天生成新文件
- 文件大小限制：100MB
- 保留时间：30天

## SIGOZ集成建议

### 1. 日志收集

- 使用Filebeat收集日志文件
- 配置JSON解析器解析日志内容
- 发送到Elasticsearch或SIGOZ

### 2. 索引配置

建议在SIGOZ中创建以下索引模式：

- `xa-mass-platform-*`：主日志索引
- `xa-mass-platform-error-*`：错误日志索引
- `xa-mass-platform-business-*`：业务日志索引

### 3. 可视化面板

可以创建以下可视化面板：

- 任务创建成功率
- 设备操作统计
- 错误率趋势
- 响应时间分布
- 业务操作统计

## 测试验证

运行日志示例类验证配置：

```bash
cd xa-mass-engine
mvn exec:java -Dexec.mainClass="com.xa.mass.engine.example.LoggingExample"
```

这将输出结构化JSON日志，验证配置是否正确。 