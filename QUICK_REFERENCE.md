# XA Mass Platform - Quick Reference Guide

> This file contains historical quick notes.
> For commands and behavior that were actually verified on 2026-04-12, use [`doc/VERIFIED_RUNBOOK.md`](./doc/VERIFIED_RUNBOOK.md) first.

## 🚀 Quick Start

### Start the Platform

```bash
./mvnw -DskipTests compile
./mvnw -pl xa-mass-mock -am dependency:build-classpath \
  -Dmdep.outputFile=/tmp/xa-mass-mock.cp \
  -DincludeScope=runtime
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:$(cat /tmp/xa-mass-mock.cp)" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

### Access Points

- **Status Page**: `http://localhost:8088/status`
- **Task Page**: `http://localhost:8088/status/tasks`
- **API Doc**: `http://localhost:8088/doc.html`
- **WebSocket**: `ws://localhost:18088`

## 📋 Common API Endpoints

### Task Management

```bash
# Create Task
POST /status/api/tasks
Content-Type: application/json
{
  "taskName": "My Task",
  "project": "PROJECT_A",
  "countryCode": "US",
  "userId": "user123",
  "targetList": ["target1", "target2"],
  "batchSize": 10
}

# Get Task
GET /status/api/tasks/{taskId}

# Update Status
PUT /status/api/tasks/{taskId}/status?status=PAUSED

# Control Task
POST /status/api/tasks/{taskId}/pause
POST /status/api/tasks/{taskId}/resume
POST /status/api/tasks/{taskId}/terminate
```

### Session Management

```bash
# List Sessions
GET /api/session/list

# Session Stats
GET /api/session/stats
```

## 🎯 Core Components Quick Access

### EventBus

```java
// Get instance
EventBusFacade eventBus = EventBusFactory.get("guava");

// Register handler
eventBus.

register(TaskCreatedEvent .class, event ->{
        // Handle event
        });

// Post event
        eventBus.

post(new TaskCreatedEvent(task, traceId, requestId));
```

Note:

- This still reflects the current runtime path more closely than the newer EventBus docs.
- Do not assume the newer generic EventBus has fully replaced the old path.

### TaskManager

```java

@Autowired
private TaskManager taskManager;

// Create task
Task task = taskManager.createTask(dto);

// Get task
Task task = taskManager.getTask(taskId);

// Control task
taskManager.

approveTask(taskId);
taskManager.

pauseTask(taskId);
taskManager.

resumeTask(taskId);
```

### WebSocket Server

```java
WebSocketServerImpl server = new WebSocketServerImpl();
server.

setPort(8080);
server.

setWebsocketPath("/ws");
server.

start(8080);

// Get client channel
Channel channel = server.getClientChannel("deviceId");
```

## 🔧 Configuration Properties

```properties
# Server
server.port=8088
xa.mass.websocket.port=18088
xa.mass.websocket.path=/ws
# Event Bus
xa.mass.eventbus.type=guava
xa.mass.eventbus.async=true
# Task Settings
xa.mass.task.batch-size=50
xa.mass.task.max-retries=3
xa.mass.task.timeout=300000
# Device Settings
xa.mass.device.heartbeat-interval=30000
xa.mass.device.offline-threshold=90000
```

## 📊 Task Status Flow

```
NEW → READY → RUNNING → TERMINAL
 ↓      ↓       ↓
BLOCKED PAUSED  ↓
        ↓       ↓
       READY → TERMINAL
```

Verified minimum path:

- `NEW -> READY -> PAUSED -> READY`

**Status Descriptions:**

- `NEW`: Just created, awaiting audit
- `READY`: Approved and ready for execution
- `RUNNING`: Currently executing
- `PAUSED`: Temporarily suspended
- `TERMINAL`: Completed or cancelled
- `BLOCKED`: Rejected during audit

## 🎪 Event Types Quick Reference

### Task Events

- `TaskCreatedEvent`: Task is created
- `TaskAuditedEvent`: Task is audited (approved/rejected)
- `TaskAssignedEvent`: Task assigned to devices
- `TaskCompletedEvent`: Task execution completed

### Device Events

- `DeviceOnlineEvent`: Device comes online
- `DeviceOfflineEvent`: Device goes offline
- `DeviceOnlineBatchEvent`: Multiple devices online
- `DeviceOfflineBatchEvent`: Multiple devices offline

## 🧪 Testing & Mock

### Mock Configuration (`mock_config.json`)

```json
{
  "mockDevices": [
    {
      "deviceId": "mock_device_001",
      "token": "token_123",
      "project": "PROJECT_A"
    }
  ],
  "mockTasks": [
    {
      "taskName": "Test Task",
      "targetCount": 100,
      "batchSize": 10
    }
  ]
}
```

### Test Properties

```properties
xa.mass.mock.enabled=true
xa.mass.mock.devices=5
xa.mass.mock.auto-connect=true
```

## 🔍 Monitoring & Health Check

### Health Endpoints

```bash
# System health
GET /actuator/health

# Session statistics
GET /api/session/stats

# Queue status
GET /api/queue/status
```

### Log Levels

```properties
logging.level.com.xa.mass=DEBUG
logging.level.com.xa.mass.engine=INFO
logging.level.com.xa.mass.gateway=WARN
```

## 🛠️ Common Integration Patterns

### Spring Bean Configuration

```java

@Configuration
public class MassConfig {

    @Bean
    public MassApplication massApplication() {
        return MassApplicationBuilder.build();
    }

    @Bean
    public TaskScheduler customScheduler() {
        return new SimpleTaskScheduler();
    }
}
```

### Event Handler Registration

```java

@PostConstruct
public void init() {
    EventBusFacade eventBus = EventBusFactory.get("guava");
    eventBus.register(TaskCreatedEvent.class, this::handleTaskCreated);
}

private void handleTaskCreated(TaskCreatedEvent event) {
    log.info("Task created: {}", event.getTaskId());
}
```

### WebSocket Client Connection

```java
URI serverUri = URI.create("ws://localhost:8081/ws");
MassWebSocketClient client = new MassWebSocketClient(serverUri);
client.

connect();
```

## 🚨 Error Handling

### Common HTTP Status Codes

- `200`: Success
- `400`: Bad Request (invalid parameters)
- `404`: Not Found (task/resource doesn't exist)
- `500`: Internal Server Error

### Exception Types

- `TaskNotFoundException`: Task with given ID not found
- `InvalidTaskStateException`: Operation not allowed in current state
- `DeviceNotAvailableException`: No suitable device available
- `RuleEvaluationException`: Rule evaluation failed

## 💡 Best Practices

### Task Creation

1. Always set appropriate `batchSize` (recommended: 10-50)
2. Use meaningful `taskName` for tracking
3. Validate `targetList` before submission
4. Handle approval workflow properly

### Event Handling

1. Register handlers in `@PostConstruct`
2. Keep handlers lightweight and fast
3. Use async processing for heavy operations
4. Always include error handling

### WebSocket Usage

1. Implement proper reconnection logic
2. Handle connection lifecycle events
3. Use heartbeat mechanism for health checks
4. Validate incoming messages

### Performance Tips

1. Use appropriate queue implementations
2. Monitor queue sizes regularly
3. Implement circuit breakers for external calls
4. Use connection pooling for databases

## 📞 Support & Troubleshooting

### Common Issues

**Issue**: WebSocket connection fails
**Solution**: Check port availability and firewall settings

**Issue**: Task stuck in RUNNING state
**Solution**: Check device connectivity and message processing

**Issue**: High memory usage
**Solution**: Monitor queue sizes and implement backpressure

**Issue**: Events not being processed
**Solution**: Verify event bus registration and handler methods

### Debug Commands

```bash
# Check active connections
curl http://localhost:8080/api/session/stats

# Monitor task progress
curl http://localhost:8080/status/api/tasks/{taskId}

# Check queue status
curl http://localhost:8080/api/queue/status
```

---

For detailed documentation, see [API_DOCUMENTATION.md](./API_DOCUMENTATION.md)
