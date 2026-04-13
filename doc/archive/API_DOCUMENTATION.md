> Archived during repository convergence.
> Current high-trust references:
> - [../INTERNAL_API_REFERENCE.md](../INTERNAL_API_REFERENCE.md)
> - [../VERIFIED_RUNBOOK.md](../VERIFIED_RUNBOOK.md)
> - [../../AGENTS.md](../../AGENTS.md)

> **⚠️ 部分过时** �?本文档生成于 Phase 1 之前，不反映以下修复�?> deleteTask 状态约束、WebSocket 错误帧格式、SessionController 真实数据、消息分配管道�?
> **��ǰȨ���ο�**��[`doc/INTERNAL_API_REFERENCE.md`](./doc/INTERNAL_API_REFERENCE.md)����ʵ��״̬��ע���� [`doc/VERIFIED_RUNBOOK.md`](./doc/VERIFIED_RUNBOOK.md)

# XA Mass Platform - Comprehensive API Documentation

## Table of Contents

1. [Platform Overview](#platform-overview)
2. [Module Architecture](#module-architecture)
3. [REST API Documentation](#rest-api-documentation)
4. [Event Bus System](#event-bus-system)
5. [Core Engine APIs](#core-engine-apis)
6. [Gateway & WebSocket APIs](#gateway--websocket-apis)
7. [Configuration & Management](#configuration--management)
8. [Usage Examples](#usage-examples)
9. [Integration Guide](#integration-guide)

## Platform Overview

XA Mass Platform is a multi-module, event-driven message scheduling and distribution platform built with Spring Boot. It
provides:

- **High-performance message scheduling**: Handles concurrent task distribution and device management
- **Event-driven architecture**: Decoupled communication through unified event bus
- **Multi-protocol gateway**: WebSocket connections with middleware chain support
- **Comprehensive testing**: Full-chain mock testing and demonstration capabilities
- **RESTful APIs**: Complete REST API for task management and monitoring

### Key Features

- **Scalable Architecture**: Layered multi-module design for high extensibility
- **Real-time Communication**: WebSocket-based message gateway
- **Rule Engine**: Flexible rule evaluation with QLExpress support
- **Device Management**: Online/offline device tracking and token allocation
- **Mock Testing**: End-to-end integration testing with configurable scenarios

## Module Architecture

### Module Structure

```
xa-mass-platform/
├── xa-mass-api/        # REST API layer
├── xa-mass-core/       # Core infrastructure & event bus
├── xa-mass-engine/     # Business logic & task scheduling
├── xa-mass-gateway/    # WebSocket gateway & message routing
├── xa-mass-runtime/    # Application startup & aggregation
└── xa-mass-mock/       # Testing & mock framework
```

### Dependencies

- **Spring Boot 3.3.0**: Core framework
- **Netty**: High-performance WebSocket server
- **Guava EventBus**: Event-driven communication
- **QLExpress**: Rule engine evaluation
- **Logback**: Structured JSON logging

## REST API Documentation

### Base URL

```
http://localhost:{port}/api
```

### Task Management API

#### 1. Create Task

**Endpoint:** `POST /status/api/tasks`

**Description:** Creates a new task with specified parameters.

**Request Body:**

```json
{
  "taskName": "Sample Task",
  "project": "PROJECT_A",
  "countryCode": "US",
  "userId": "user123",
  "textContent": "Task description",
  "targetList": ["target1", "target2"],
  "targetJsonList": [],
  "batchSize": 10
}
```

**Response:**

```json
{
  "success": true,
  "message": "任务创建成功",
  "taskId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Usage Example:**

```bash
curl -X POST http://localhost:8080/status/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "taskName": "Marketing Campaign",
    "project": "PROJECT_A",
    "countryCode": "US",
    "userId": "marketing_team",
    "textContent": "Send promotional message",
    "targetList": ["user1", "user2", "user3"],
    "batchSize": 5
  }'
```

#### 2. Get Task Details

**Endpoint:** `GET /status/api/tasks/{taskId}`

**Description:** Retrieves detailed information about a specific task.

**Response:**

```json
{
  "success": true,
  "task": {
    "tid": "550e8400-e29b-41d4-a716-446655440000",
    "taskName": "Sample Task",
    "status": "READY",
    "project": "PROJECT_A",
    "taskCountry": "US",
    "textContent": "Task description"
  },
  "targetList": ["target1", "target2"]
}
```

#### 3. Update Task Status

**Endpoint:** `PUT /status/api/tasks/{taskId}/status?status={TaskStatus}`

**Description:** Updates the status of a task.

**Task Status Values:**

- `NEW` - Newly created task
- `READY` - Ready for execution
- `RUNNING` - Currently executing
- `PAUSED` - Temporarily paused
- `TERMINAL` - Completed or cancelled
- `BLOCKED` - Blocked due to audit rejection

**Usage Example:**

```bash
curl -X PUT "http://localhost:8080/status/api/tasks/550e8400-e29b-41d4-a716-446655440000/status?status=PAUSED"
```

#### 4. Task Audit

**Endpoint:** `POST /status/api/tasks/{taskId}/audit`

**Parameters:**

- `approved` (required): "true" or "false"
- `comment` (optional): Audit comment

**Usage Example:**

```bash
curl -X POST "http://localhost:8080/status/api/tasks/550e8400-e29b-41d4-a716-446655440000/audit?approved=true&comment=Approved for execution"
```

#### 5. Task Control Operations

**Pause Task:**

```bash
POST /status/api/tasks/{taskId}/pause
```

**Resume Task:**

```bash
POST /status/api/tasks/{taskId}/resume
```

**Terminate Task:**

```bash
POST /status/api/tasks/{taskId}/terminate
```

**Delete Task:**

```bash
DELETE /status/api/tasks/{taskId}
```

#### 6. Get Task Messages (Paginated)

**Endpoint:** `GET /status/api/tasks/{taskId}/messages?page=1&size=20`

**Description:** Retrieves paginated task messages.

**Response:**

```json
{
  "success": true,
  "total": 100,
  "page": 1,
  "size": 20,
  "messages": [
    {
      "msgId": "msg-001",
      "tid": "task-001",
      "target": "user1",
      "status": "PENDING"
    }
  ]
}
```

### Session Management API

#### 1. List Active Sessions

**Endpoint:** `GET /api/session/list`

**Description:** Retrieves all active WebSocket sessions and connected devices.

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "sessionManager": "ServerSessionManager details"
    }
  ]
}
```

#### 2. Session Statistics

**Endpoint:** `GET /api/session/stats`

**Description:** Provides connection statistics and session metrics.

## Event Bus System

### Core Interfaces

#### EventBusFacade

The main interface for event-driven communication.

```java
public interface EventBusFacade {
    <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler);
    <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler);
    <E extends MassEvent> void post(E event);
    void shutdown();
    void register(Object listener);
    void unregister(Object listener);
}
```

#### Usage Example

```java
// Get event bus instance
EventBusFacade eventBus = EventBusFactory.get("guava");

// Register event handler
eventBus.register(TaskCreatedEvent.class, event -> {
    System.out.println("Task created: " + event.getTaskId());
    // Process task creation
});

// Publish event
Task task = new Task(/* parameters */);
TaskCreatedEvent event = new TaskCreatedEvent(task, "trace123", "req456");
eventBus.post(event);
```

### Event Types

#### Task Events

**TaskCreatedEvent**

```java
// Usage
TaskCreatedEvent event = new TaskCreatedEvent(task, traceId, requestId);
eventBus.post(event);
```

**TaskAuditedEvent**

```java
// Triggered when task is audited
TaskAuditedEvent event = new TaskAuditedEvent(taskId, approved, comment);
eventBus.post(event);
```

**TaskAssignedEvent**

```java
// Triggered when task is assigned to devices
TaskAssignedEvent event = new TaskAssignedEvent(taskId, deviceIds);
eventBus.post(event);
```

#### Device Events

**DeviceOnlineSingleEvent**

```java
// When a single device comes online
DeviceOnlineSingleEvent event = new DeviceOnlineSingleEvent(deviceId, timestamp);
eventBus.post(event);
```

**DeviceOfflineBatchEvent**

```java
// When multiple devices go offline
List<String> deviceIds = Arrays.asList("device1", "device2");
DeviceOfflineBatchEvent event = new DeviceOfflineBatchEvent(deviceIds);
eventBus.post(event);
```

### Custom Event Implementation

```java
public class CustomBusinessEvent extends MassEvent.BaseMassEvent {
    private final String businessData;
    
    public CustomBusinessEvent(String businessData, String traceId, String requestId) {
        super(
            "CUSTOM_BUSINESS_EVENT",
            MassPlatformEventType.BUSINESS,
            "Custom business operation",
            Map.of("data", businessData),
            traceId,
            requestId
        );
        this.businessData = businessData;
    }
    
    public String getBusinessData() {
        return businessData;
    }
}
```

## Core Engine APIs

### TaskManager

Central component for task lifecycle management.

#### Interface

```java
public class TaskManager {
    public Task createTask(TaskCreateRequestDto dto);
    public Task getTask(String taskId);
    public boolean updateTask(Task task);
    public boolean deleteTask(String taskId);
    public List<Task> getAllTasks();
    public List<Task> getTasksByStatus(TaskStatus status);
    public List<Task> getSchedulableTasks();
    
    // Task control operations
    public boolean approveTask(String taskId);
    public boolean rejectTask(String taskId);
    public boolean pauseTask(String taskId);
    public boolean resumeTask(String taskId);
    public boolean cancelTask(String taskId);
    
    // Task message management
    public void addTaskMessage(String taskId, TaskMsg taskMsg);
    public List<TaskMsg> getTaskMessages(String taskId);
    public TaskStorage.TaskMessageStats getTaskMessageStats(String taskId);
}
```

#### Usage Examples

**Creating a Task:**

```java
TaskCreateRequestDto dto = new TaskCreateRequestDto();
dto.setTaskName("Email Campaign");
dto.setProject(Project.PROJECT_A);
dto.setCountryCode("US");
dto.setUserId("marketing");
dto.setTextContent("Welcome email");
dto.setTargetList(Arrays.asList("user1@example.com", "user2@example.com"));
dto.setBatchSize(10);

Task task = taskManager.createTask(dto);
System.out.println("Created task: " + task.getTid());
```

**Task Lifecycle Management:**

```java
// Approve task
boolean approved = taskManager.approveTask(taskId);

// Start task execution
Task task = taskManager.getTask(taskId);
task.setStatus(TaskStatus.RUNNING);
taskManager.updateTask(task);

// Pause if needed
taskManager.pauseTask(taskId);

// Resume
taskManager.resumeTask(taskId);
```

### TaskScheduler Interface

Defines task scheduling strategies.

```java
public interface TaskScheduler {
    SchedulingResult scheduleTask(Task task);
    List<SchedulingResult> scheduleTasks(List<Task> tasks);
    boolean handleTaskMsgCompletion(TaskMsg taskMsg);
    boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage);
    boolean retryTaskMsg(TaskMsg taskMsg);
    boolean cancelTask(String taskId);
    boolean pauseTask(String taskId);
    boolean resumeTask(String taskId);
}
```

#### Custom Scheduler Implementation

```java
public class CustomTaskScheduler implements TaskScheduler {
    
    @Override
    public SchedulingResult scheduleTask(Task task) {
        try {
            // Custom scheduling logic
            List<TaskMsg> messages = createTaskMessages(task);
            
            // Distribute to available devices
            for (TaskMsg msg : messages) {
                Device device = deviceSelector.selectDevice(task);
                if (device != null) {
                    assignTaskToDevice(msg, device);
                }
            }
            
            return SchedulingResult.success(messages);
        } catch (Exception e) {
            return SchedulingResult.failure("Scheduling failed: " + e.getMessage());
        }
    }
    
    // Other methods...
}
```

### DeviceManager

Manages device states and token allocation.

```java
public class DeviceManager {
    public List<Device> getOnlineDevices();
    public List<Device> getDevicesByProject(Project project);
    public void markDeviceOnline(String deviceId);
    public void markDeviceOffline(String deviceId);
    public TokenAllocationResult allocateToken(String deviceId, String taskId);
    public boolean releaseToken(String deviceId, String tokenId);
}
```

### Rule Engine

Flexible rule evaluation system using QLExpress.

#### RuleManager Usage

```java
// Create rule manager
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>();

// Define rule
RuleDefinition rule = new RuleDefinition(
    "device_availability",
    "device.status == 'ONLINE' && device.load < 80",
    "Check if device is available for new tasks"
);

// Add rule
ruleManager.addRule(rule);

// Evaluate
Map<String, Object> context = Map.of(
    "device", Map.of("status", "ONLINE", "load", 60)
);

boolean result = ruleManager.evaluate("device_availability", context);
```

## Gateway & WebSocket APIs

### WebSocket Server

#### MassWebSocketServer Interface

```java
public interface MassWebSocketServer {
    void start(int port);
    void stop();
    boolean isRunning();
    Channel getClientChannel(String clientId);
}
```

#### Configuration and Usage

```java
WebSocketServerImpl server = new WebSocketServerImpl();
server.setPort(8080);
server.setWebsocketPath("/ws");
server.setSessionManager(sessionManager);
server.setDispatcherContext(dispatcherContext);

// Start server
server.start(8080);

// Get client channel
Channel clientChannel = server.getClientChannel("device123");
if (clientChannel != null && clientChannel.isActive()) {
    // Send message to client
    clientChannel.writeAndFlush(new TextWebSocketFrame("Hello Device"));
}
```

### Message Transport System

#### MessageTransporter Interface

```java
public interface MessageTransporter {
    void sendInput(Envelope envelope);
    Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException;
    void sendOutput(Envelope envelope);
    Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException;
    int inputQueueSize();
    int outputQueueSize();
}
```

#### Usage Example

```java
MessageTransporter transporter = MessageTransporterFactory.create("memory");

// Send input message
Envelope inputEnvelope = new Envelope("device123", "Hello", "TEXT");
transporter.sendInput(inputEnvelope);

// Receive and process
Envelope received = transporter.receiveInput(5, TimeUnit.SECONDS);
if (received != null) {
    processMessage(received);
}
```

### Message Handling

#### Custom Message Handler

```java
public class CustomMessageHandler implements MassMessageHandler {
    
    @Override
    public ResolutionResult handle(MassMessage message, DispatchRuntimeContext context) {
        try {
            // Process message based on type
            switch (message.getType()) {
                case "TASK_REQUEST":
                    return handleTaskRequest(message, context);
                case "STATUS_UPDATE":
                    return handleStatusUpdate(message, context);
                default:
                    return ResolutionResult.unresolved("Unknown message type");
            }
        } catch (Exception e) {
            return ResolutionResult.error("Processing failed: " + e.getMessage());
        }
    }
    
    private ResolutionResult handleTaskRequest(MassMessage message, DispatchRuntimeContext context) {
        // Handle task request logic
        return ResolutionResult.resolved("Task request processed");
    }
}
```

## Configuration & Management

### Application Configuration

#### MassApplicationBuilder

```java
public class MassApplicationBuilder {
    
    public static MassApplication build() {
        return new MassApplicationBuilder()
            .withEngine(engineBuilder -> engineBuilder
                .withTaskScheduler(new SimpleTaskScheduler())
                .withDeviceSelector(new DefaultDeviceSelector())
                .withTokenAllocator(customTokenAllocator)
            )
            .withGateway(gatewayBuilder -> gatewayBuilder
                .withPort(8080)
                .withWebSocketPath("/ws")
                .withMessageTransporter("multi-level")
            )
            .build();
    }
}
```

#### Configuration Properties

```properties
# Server Configuration
server.port=8080
mass.websocket.port=8081
# WebSocket path is currently fixed in code as /ws

# Event Bus Configuration
xa.mass.eventbus.type=guava
xa.mass.eventbus.async=true

# Task Configuration
xa.mass.task.batch-size=50
xa.mass.task.max-retries=3
xa.mass.task.timeout=300000

# Device Configuration
xa.mass.device.heartbeat-interval=30000
xa.mass.device.offline-threshold=90000

# Logging Configuration
logging.level.com.xa.mass=DEBUG
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
```

### Mock Configuration

The mock system supports comprehensive testing scenarios through `mock_config.json`:

```json
{
  "mockDevices": [
    {
      "deviceId": "mock_device_001",
      "token": "token_123",
      "project": "PROJECT_A",
      "capabilities": ["SMS", "EMAIL"],
      "maxConcurrentTasks": 5
    }
  ],
  "mockTasks": [
    {
      "taskName": "Test Campaign",
      "project": "PROJECT_A",
      "targetCount": 1000,
      "batchSize": 50,
      "messageTemplate": "Hello {{name}}, welcome!"
    }
  ],
  "scenarios": {
    "load_test": {
      "devices": 10,
      "tasks": 5,
      "duration": "5m"
    }
  }
}
```

## Usage Examples

### Complete Integration Example

```java
@Service
public class MassIntegrationService {
    
    @Autowired
    private TaskManager taskManager;
    
    @Autowired
    private EventBusFacade eventBus;
    
    @PostConstruct
    public void initialize() {
        // Register event handlers
        eventBus.register(TaskCreatedEvent.class, this::handleTaskCreated);
        eventBus.register(DeviceOnlineEvent.class, this::handleDeviceOnline);
    }
    
    public String createAndScheduleTask(String campaignName, List<String> targets) {
        // 1. Create task
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(campaignName);
        dto.setProject(Project.PROJECT_A);
        dto.setTargetList(targets);
        dto.setBatchSize(20);
        
        Task task = taskManager.createTask(dto);
        
        // 2. Auto-approve for immediate processing
        taskManager.approveTask(task.getTid());
        
        // 3. Start monitoring
        monitorTaskProgress(task.getTid());
        
        return task.getTid();
    }
    
    private void handleTaskCreated(TaskCreatedEvent event) {
        log.info("Task created: {}", event.getTaskId());
        // Trigger additional processing
    }
    
    private void handleDeviceOnline(DeviceOnlineEvent event) {
        log.info("Device online: {}", event.getDeviceId());
        // Check for pending tasks
        redistributePendingTasks();
    }
    
    private void monitorTaskProgress(String taskId) {
        // Schedule periodic progress checks
        scheduler.scheduleAtFixedRate(() -> {
            TaskStorage.TaskMessageStats stats = taskManager.getTaskMessageStats(taskId);
            log.info("Task {} progress: {}/{} completed", 
                taskId, stats.getSuccess(), stats.getTotal());
        }, 0, 30, TimeUnit.SECONDS);
    }
}
```

### WebSocket Client Integration

```java
@Component
public class MassWebSocketClient extends WebSocketClient {
    
    public MassWebSocketClient(URI serverUri) {
        super(serverUri);
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("Connected to Mass Platform");
        
        // Send device registration
        JsonObject registration = new JsonObject();
        registration.addProperty("type", "DEVICE_REGISTER");
        registration.addProperty("deviceId", deviceId);
        registration.addProperty("token", authToken);
        
        send(registration.toString());
    }
    
    @Override
    public void onMessage(String message) {
        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String type = msg.get("type").getAsString();
            
            switch (type) {
                case "TASK_ASSIGNMENT":
                    handleTaskAssignment(msg);
                    break;
                case "TASK_CANCELLATION":
                    handleTaskCancellation(msg);
                    break;
                default:
                    log.warn("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to process message: {}", message, e);
        }
    }
    
    private void handleTaskAssignment(JsonObject message) {
        String taskId = message.get("taskId").getAsString();
        String target = message.get("target").getAsString();
        
        // Process the task
        boolean success = processTask(taskId, target);
        
        // Send result back
        JsonObject result = new JsonObject();
        result.addProperty("type", "TASK_RESULT");
        result.addProperty("taskId", taskId);
        result.addProperty("success", success);
        result.addProperty("timestamp", System.currentTimeMillis());
        
        send(result.toString());
    }
}
```

### Custom Rule Implementation

```java
@Component
public class BusinessRuleService {
    
    private final RuleManager<DeviceMatchContext> deviceRuleManager;
    private final RuleManager<Map<String, Object>> taskRuleManager;
    
    public BusinessRuleService() {
        this.deviceRuleManager = new RuleManager<>();
        this.taskRuleManager = new RuleManager<>();
        
        initializeRules();
    }
    
    private void initializeRules() {
        // Device selection rules
        deviceRuleManager.addRule(new RuleDefinition(
            "high_priority_device",
            "device.load < 50 && device.priority == 'HIGH'",
            "Select high priority devices with low load"
        ));
        
        deviceRuleManager.addRule(new RuleDefinition(
            "project_matching",
            "device.project == task.project",
            "Device must match task project"
        ));
        
        // Task validation rules
        taskRuleManager.addRule(new RuleDefinition(
            "batch_size_limit",
            "task.batchSize <= 100",
            "Batch size must not exceed 100"
        ));
    }
    
    public List<Device> selectDevicesForTask(Task task, List<Device> availableDevices) {
        return availableDevices.stream()
            .filter(device -> {
                DeviceMatchContext context = new DeviceMatchContext(device, task);
                return deviceRuleManager.evaluateAll(context);
            })
            .collect(Collectors.toList());
    }
    
    public boolean validateTask(Task task) {
        Map<String, Object> context = Map.of("task", task);
        return taskRuleManager.evaluateAll(context);
    }
}
```

## Integration Guide

### Spring Boot Integration

1. **Add Dependencies:**

```xml
<dependency>
    <groupId>com.xa.mass</groupId>
    <artifactId>xa-mass-runtime</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

2. **Configuration Class:**

```java
@Configuration
@EnableMassPlatform
public class MassConfiguration {
    
    @Bean
    public TaskScheduler customTaskScheduler() {
        return new CustomTaskScheduler();
    }
    
    @Bean
    public DeviceSelector customDeviceSelector() {
        return new BusinessRuleDeviceSelector();
    }
}
```

3. **Application Startup:**

```java
@SpringBootApplication
public class Application {
    
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Bean
    @Primary
    public MassApplication massApplication() {
        return MassApplicationBuilder.build();
    }
}
```

### Testing Integration

```java
@SpringBootTest
@TestPropertySource(properties = {
    "xa.mass.mock.enabled=true",
    "xa.mass.mock.devices=5",
    "xa.mass.mock.auto-connect=true"
})
class MassPlatformIntegrationTest {
    
    @Autowired
    private TaskManager taskManager;
    
    @Autowired
    private EventBusFacade eventBus;
    
    @Test
    void testFullWorkflow() {
        // Create task
        TaskCreateRequestDto dto = createTestTask();
        Task task = taskManager.createTask(dto);
        
        // Approve and monitor
        taskManager.approveTask(task.getTid());
        
        // Wait for completion
        await().atMost(30, SECONDS)
            .until(() -> {
                Task updated = taskManager.getTask(task.getTid());
                return updated.getStatus() == TaskStatus.TERMINAL;
            });
        
        // Verify results
        TaskStorage.TaskMessageStats stats = taskManager.getTaskMessageStats(task.getTid());
        assertThat(stats.getSuccess()).isGreaterThan(0);
    }
}
```

### Monitoring and Observability

The platform provides comprehensive logging and monitoring capabilities:

```java
@Component
public class MassMonitoringService {
    
    @EventListener
    public void handleTaskEvent(TaskCreatedEvent event) {
        // Log task creation metrics
        Metrics.counter("tasks.created", "project", event.getProject()).increment();
    }
    
    @Scheduled(fixedRate = 30000)
    public void reportSystemHealth() {
        Map<String, Object> health = Map.of(
            "activeTasks", taskManager.getTasksByStatus(TaskStatus.RUNNING).size(),
            "onlineDevices", deviceManager.getOnlineDevices().size(),
            "queueSize", messageTransporter.inputQueueSize()
        );
        
        log.info("System health: {}", health);
    }
}
```

This documentation provides a comprehensive guide to all public APIs, functions, and components in the XA Mass Platform.
Each section includes detailed examples and usage instructions to help developers integrate and extend the platform
effectively.


