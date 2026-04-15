# TaskManager銆丏eviceManager 鍜?RuleManager 瀛樺偍灞傞噸鏋勮鏄?

## 閲嶆瀯鑳屾櫙

鍘熷鐨?`TaskManager`銆乣WorkerManager` 鍜?`RuleManager` 鐩存帴浣跨敤 `ConcurrentHashMap` 杩涜鍐呭瓨瀛樺偍锛屽瓨鍦ㄤ互涓嬮棶棰橈細

1. **绱ц€﹀悎**锛歁anager 绫讳笌鍏蜂綋瀛樺偍瀹炵幇绱у瘑鑰﹀悎
2. **鎵╁睍鎬у樊**锛氭棤娉曡交鏉惧垏鎹㈠埌鍏朵粬瀛樺偍鍚庣锛堝 Redis銆佹暟鎹簱锛?
3. **浠ｇ爜閲嶅**锛氬瓨鍌ㄩ€昏緫涓庝笟鍔￠€昏緫娣峰悎鍦ㄤ竴璧?
4. **娴嬭瘯鍥伴毦**锛氶毦浠ヨ繘琛屽崟鍏冩祴璇曞拰闆嗘垚娴嬭瘯

## 閲嶆瀯鏂规

### 1. 鎶借薄瀛樺偍鎺ュ彛

#### TaskStorage 鎺ュ彛

鍒涘缓浜?`TaskStorage` 鎺ュ彛锛屽畾涔変簡浠诲姟鍜屼换鍔℃秷鎭殑瀛樺偍鎶借薄锛?

```java
public interface TaskStorage {
    void saveTask(Task task);

    Optional<Task> getTask(String taskId);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(String status);

    List<Task> getSchedulableTasks();

    void addTaskMessage(String taskId, TaskMsg taskMsg);

    List<TaskMsg> getTaskMessages(String taskId);

    TaskMessageStats getTaskMessageStats(String taskId);
}
```

#### WorkerStorage 鎺ュ彛

鍒涘缓浜?`WorkerStorage` 鎺ュ彛锛屽畾涔変簡璁惧鍜孴oken鐨勫瓨鍌ㄦ娊璞★細

```java
public interface WorkerStorage {
    void addWorker(Worker worker);

    Optional<Worker> getWorker(String workerId);

    boolean updateWorker(Worker worker);

    boolean deleteWorker(String workerId);

List<Worker> getWorkersByGroupId(String workerGroupId);

    List<Worker> getAllWorkers();

    void addWorkerContext(String workerId, WorkerContext workerContext);

    Optional<WorkerContext> getWorkerContext(String workerId);

    boolean updateWorkerContext(String workerId, WorkerContext workerContext);

    boolean deleteWorkerContext(String workerId);

    List<WorkerContext> getAllWorkerContexts();

    boolean tryLockWorker(String workerId);

    void unlockWorker(String workerId);

    boolean isLocked(String workerId);

    List<String> getLockedWorkers();
}
```

#### RuleStorage 鎺ュ彛

鍒涘缓浜?`RuleStorage` 鎺ュ彛锛屽畾涔変簡瑙勫垯瀹氫箟鍜岃鍒欒瘎浼板櫒鐨勫瓨鍌ㄦ娊璞★細

```java
public interface RuleStorage {
    void addRule(RuleDefinition rule);

    Optional<RuleDefinition> getRule(String ruleId);

    boolean updateRule(RuleDefinition rule);

    boolean deleteRule(String ruleId);

    List<RuleDefinition> getAllRules();

    List<RuleDefinition> getRulesByType(RuleType ruleType);

    void addRules(Collection<RuleDefinition> rules);

    void deleteRules(Collection<String> ruleIds);

    void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator);

    Optional<RuleEvaluator> getEvaluator(RuleType ruleType);

    List<RuleType> getRegisteredEvaluatorTypes();

    boolean removeEvaluator(RuleType ruleType);

    void clear();
}
```

### 2. 瀛樺偍瀹炵幇

#### 鍐呭瓨瀛樺偍瀹炵幇

- **InMemoryTaskStorage**: 灏嗗師鏉ョ殑Map閫昏緫灏佽鍒板疄鐜扮被涓?
- **InMemoryWorkerStorage**: 灏嗗師鏉ョ殑Map閫昏緫灏佽鍒板疄鐜扮被涓?
- **InMemoryRuleStorage**: 灏嗗師鏉ョ殑Map閫昏緫灏佽鍒板疄鐜扮被涓?
- 淇濇寔绾跨▼瀹夊叏锛堜娇鐢?ConcurrentHashMap 鍜?Collections.synchronizedSet锛?
- 浣滀负榛樿瀛樺偍瀹炵幇

#### Redis 瀛樺偍瀹炵幇

- **RedisTaskStorage**: 鎻愪緵 Redis 瀛樺偍鐨勭ず渚嬪疄鐜?
- **RedisWorkerStorage**: 鎻愪緵 Redis 瀛樺偍鐨勭ず渚嬪疄鐜?
- **RedisRuleStorage**: 鎻愪緵 Redis 瀛樺偍鐨勭ず渚嬪疄鐜?
- 浣跨敤 JSON 搴忓垪鍖栧瓨鍌ㄦ暟鎹?
- 鏀寔鎸夌姸鎬佺储寮曟煡璇?

### 3. 瀛樺偍宸ュ巶 (`TaskStorageFactory`)

鎻愪緵缁熶竴鐨勫瓨鍌ㄥ垱寤哄叆鍙ｏ紝鏀寔浠诲姟瀛樺偍銆佽澶囧瓨鍌ㄥ拰瑙勫垯瀛樺偍锛?

```java
// 鍒涘缓浠诲姟瀛樺偍
TaskStorage taskStorage = TaskStorageFactory.createDefaultTaskStorage();
TaskStorage redisTaskStorage = TaskStorageFactory.createTaskStorage(StorageType.REDIS);

// 鍒涘缓璁惧瀛樺偍
WorkerStorage workerStorage = TaskStorageFactory.createDefaultWorkerStorage();
WorkerStorage redisWorkerStorage = TaskStorageFactory.createWorkerStorage(StorageType.REDIS);

// 鍒涘缓瑙勫垯瀛樺偍
RuleStorage ruleStorage = TaskStorageFactory.createDefaultRuleStorage();
RuleStorage redisRuleStorage = TaskStorageFactory.createRuleStorage(StorageType.REDIS);

// 閫氳繃閰嶇疆瀛楃涓插垱寤?
TaskStorage configTaskStorage = TaskStorageFactory.createTaskStorage("memory");
WorkerStorage configWorkerStorage = TaskStorageFactory.createWorkerStorage("memory");
RuleStorage configRuleStorage = TaskStorageFactory.createRuleStorage("memory");
```

### 4. Manager 绫婚噸鏋?

#### TaskManager 閲嶆瀯

閲嶆瀯鍚庣殑 `TaskManager`锛?

```java
public class TaskManager {
    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;

    // 浣跨敤榛樿瀛樺偍
    public TaskManager(TaskScheduler taskScheduler) {
        this(taskScheduler, TaskStorageFactory.createDefaultTaskStorage());
    }

    // 浣跨敤鑷畾涔夊瓨鍌?
    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage) {
        this.taskScheduler = taskScheduler;
        this.taskStorage = taskStorage;
    }

    // 鎵€鏈夊瓨鍌ㄦ搷浣滈兘濮旀墭缁?taskStorage
    public Task getTask(String taskId) {
        return taskStorage.getTask(taskId).orElse(null);
    }

    public boolean updateTask(Task task) {
        return taskStorage.updateTask(task);
    }
    // ... 鍏朵粬鏂规硶
}
```

#### WorkerManager 閲嶆瀯

閲嶆瀯鍚庣殑 `WorkerManager`锛?

```java
public class WorkerManager {
    private final WorkerStorage workerStorage;

    // 浣跨敤榛樿瀛樺偍
    public WorkerManager() {
        this(TaskStorageFactory.createDefaultWorkerStorage());
    }

    // 浣跨敤鑷畾涔夊瓨鍌?
    public WorkerManager(WorkerStorage workerStorage) {
        this.workerStorage = workerStorage;
    }

    // 鎵€鏈夊瓨鍌ㄦ搷浣滈兘濮旀墭缁?workerStorage
    public void addWorker(Worker worker) {
        workerStorage.addWorker(device);
    }

    public Worker getWorker(String workerId) {
        return workerStorage.getWorker(workerId).orElse(null);
    }

public List<Worker> getWorkersByGroupId(String workerGroupId) {
    return workerStorage.getWorkersByGroupId(workerGroupId);
}
    // ... 鍏朵粬鏂规硶
}
```

#### RuleManager 閲嶆瀯

閲嶆瀯鍚庣殑 `RuleManager`锛?

```java
public class RuleManager<T> {
    private final RuleStorage ruleStorage;

    // 浣跨敤榛樿瀛樺偍
    public RuleManager() {
        this(TaskStorageFactory.createDefaultRuleStorage());
    }

    // 浣跨敤鑷畾涔夊瓨鍌?
    public RuleManager(RuleStorage ruleStorage) {
        this.ruleStorage = ruleStorage;
    }

    // 鎵€鏈夊瓨鍌ㄦ搷浣滈兘濮旀墭缁?ruleStorage
    public void addDefaultRule(RuleDefinition rule) {
        ruleStorage.addRule(rule);
    }

    public Optional<RuleDefinition> getRule(String ruleId) {
        return ruleStorage.getRule(ruleId);
    }

    public List<RuleDefinition> getDefaultRules() {
        return ruleStorage.getAllRules();
    }

    public boolean evaluate(RuleDefinition rule, T context) throws Exception {
        Optional<RuleEvaluator> evaluatorOpt = ruleStorage.getEvaluator(rule.getType());
        if (evaluatorOpt.isEmpty()) {
            throw new IllegalArgumentException("涓嶆敮鎸佺殑瑙勫垯绫诲瀷:" + rule.getType());
        }
        return evaluatorOpt.get().evaluate(rule, context);
    }
    // ... 鍏朵粬鏂规硶
}
```

## 浣跨敤鏂瑰紡

### 1. 浣跨敤榛樿鍐呭瓨瀛樺偍

```java
// TaskManager
TaskScheduler scheduler = new SimpleTaskScheduler();
TaskManager taskManager = new TaskManager(scheduler); // 鑷姩浣跨敤鍐呭瓨瀛樺偍

// WorkerManager
WorkerManager workerManager = new WorkerManager(); // 鑷姩浣跨敤鍐呭瓨瀛樺偍

// RuleManager
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(); // 鑷姩浣跨敤鍐呭瓨瀛樺偍
```

### 2. 浣跨敤鑷畾涔夊瓨鍌?

```java
// TaskManager
TaskScheduler scheduler = new SimpleTaskScheduler();
TaskStorage redisTaskStorage = TaskStorageFactory.createTaskStorage(StorageType.REDIS);
TaskManager taskManager = new TaskManager(scheduler, redisTaskStorage);

// WorkerManager
WorkerStorage redisWorkerStorage = TaskStorageFactory.createWorkerStorage(StorageType.REDIS);
WorkerManager workerManager = new WorkerManager(redisWorkerStorage);

// RuleManager
RuleStorage redisRuleStorage = TaskStorageFactory.createRuleStorage(StorageType.REDIS);
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(redisRuleStorage);
```

### 3. 閫氳繃閰嶇疆鍒囨崲瀛樺偍

```java
// 浠庨厤缃枃浠惰鍙栧瓨鍌ㄧ被鍨?
String taskStorageType = config.getProperty("task.storage.type", "memory");
String workerStorageType = config.getProperty("worker.storage.type", "memory");
String ruleStorageType = config.getProperty("rule.storage.type", "memory");

TaskStorage taskStorage = TaskStorageFactory.createTaskStorage(taskStorageType);
WorkerStorage workerStorage = TaskStorageFactory.createWorkerStorage(workerStorageType);
RuleStorage ruleStorage = TaskStorageFactory.createRuleStorage(ruleStorageType);

TaskManager taskManager = new TaskManager(scheduler, taskStorage);
WorkerManager workerManager = new WorkerManager(workerStorage);
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(ruleStorage);
```

## 鎵╁睍鏂扮殑瀛樺偍瀹炵幇

瑕佹坊鍔犳柊鐨勫瓨鍌ㄥ疄鐜帮紝鍙渶锛?

1. 瀹炵幇瀵瑰簲鐨勫瓨鍌ㄦ帴鍙ｏ紙`TaskStorage`銆乣WorkerStorage` 鎴?`RuleStorage`锛?
2. 鍦?`TaskStorageFactory` 涓坊鍔犳柊鐨勫瓨鍌ㄧ被鍨?
3. 鍦ㄥ伐鍘傛柟娉曚腑鍒涘缓瀵瑰簲鐨勫疄渚?

渚嬪锛屾坊鍔犳暟鎹簱瀛樺偍锛?

```java
public class DatabaseTaskStorage implements TaskStorage {
    // 瀹炵幇鎵€鏈夋帴鍙ｆ柟娉?
}

public class DatabaseWorkerStorage implements WorkerStorage {
    // 瀹炵幇鎵€鏈夋帴鍙ｆ柟娉?
}

public class DatabaseRuleStorage implements RuleStorage {
    // 瀹炵幇鎵€鏈夋帴鍙ｆ柟娉?
}

// 鍦?TaskStorageFactory 涓?
public static TaskStorage createTaskStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryTaskStorage();
        case REDIS:
            return new RedisTaskStorage();
        case DATABASE:
            return new DatabaseTaskStorage(); // 鏂板
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}

public static WorkerStorage createWorkerStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryWorkerStorage();
        case REDIS:
            return new RedisWorkerStorage();
        case DATABASE:
            return new DatabaseWorkerStorage(); // 鏂板
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}

public static RuleStorage createRuleStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryRuleStorage();
        case REDIS:
            return new RedisRuleStorage();
        case DATABASE:
            return new DatabaseRuleStorage(); // 鏂板
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}
```

## 浼樺娍

1. **瑙ｈ€?*锛歁anager 绫讳笉鍐嶄緷璧栧叿浣撳瓨鍌ㄥ疄鐜?
2. **鍙墿灞?*锛氳交鏉炬坊鍔犳柊鐨勫瓨鍌ㄥ悗绔?
3. **鍙祴璇?*锛氬彲浠ヨ交鏉捐繘琛屽崟鍏冩祴璇曞拰闆嗘垚娴嬭瘯
4. **閰嶇疆鍖?*锛氬彲浠ラ€氳繃閰嶇疆鍒囨崲瀛樺偍瀹炵幇
5. **鍚戝悗鍏煎**锛氫繚鎸佸師鏈?API 涓嶅彉
6. **缁熶竴鏋舵瀯**锛歍askManager銆丏eviceManager 鍜?RuleManager 浣跨敤鐩稿悓鐨勫瓨鍌ㄦ娊璞℃ā寮?
7. **绫诲瀷瀹夊叏**锛氫娇鐢ㄦ硾鍨嬩繚璇佺被鍨嬪畨鍏?

## 娉ㄦ剰浜嬮」

1. **绾跨▼瀹夊叏**锛氭墍鏈夊瓨鍌ㄥ疄鐜伴兘搴旇淇濊瘉绾跨▼瀹夊叏
2. **鎬ц兘鑰冭檻**锛氫笉鍚屽瓨鍌ㄥ疄鐜扮殑鎬ц兘鐗瑰緛涓嶅悓锛岄渶瑕佹牴鎹疄闄呴渶姹傞€夋嫨
3. **鏁版嵁涓€鑷存€?*锛氬湪鍒嗗竷寮忕幆澧冧笅闇€瑕佺壒鍒敞鎰忔暟鎹竴鑷存€ч棶棰?
4. **閿欒澶勭悊**锛氬瓨鍌ㄦ搷浣滃彲鑳藉け璐ワ紝闇€瑕侀€傚綋鐨勯敊璇鐞嗘満鍒?
5. **鍚戝悗鍏煎**锛氫繚鐣欎簡鍘熸湁鐨勫伐鍘傛柟娉曪紝浣嗘爣璁颁负 @Deprecated
6. **璇勪及鍣ㄥ簭鍒楀寲**锛歊edis瀛樺偍涓殑璇勪及鍣ㄥ簭鍒楀寲闇€瑕佺壒鍒敞鎰忥紝鍙兘闇€瑕佷娇鐢ㄥ伐鍘傛ā寮?

## 鏂板鏂囦欢鍒楄〃

### 浠诲姟瀛樺偍鐩稿叧

- `TaskStorage.java` - 浠诲姟瀛樺偍鎺ュ彛
- `InMemoryTaskStorage.java` - 鍐呭瓨浠诲姟瀛樺偍瀹炵幇
- `RedisTaskStorage.java` - Redis浠诲姟瀛樺偍绀轰緥瀹炵幇

### 璁惧瀛樺偍鐩稿叧

- `WorkerStorage.java` - 璁惧瀛樺偍鎺ュ彛
- `InMemoryWorkerStorage.java` - 鍐呭瓨璁惧瀛樺偍瀹炵幇
- `RedisWorkerStorage.java` - Redis璁惧瀛樺偍绀轰緥瀹炵幇

### 瑙勫垯瀛樺偍鐩稿叧

- `RuleStorage.java` - 瑙勫垯瀛樺偍鎺ュ彛
- `InMemoryRuleStorage.java` - 鍐呭瓨瑙勫垯瀛樺偍瀹炵幇
- `RedisRuleStorage.java` - Redis瑙勫垯瀛樺偍绀轰緥瀹炵幇

### 宸ュ巶鍜岀ず渚?

- `TaskStorageFactory.java` - 瀛樺偍宸ュ巶锛堟敮鎸佷换鍔°€佽澶囧拰瑙勫垯瀛樺偍锛?
- `StorageExample.java` - 浠诲姟瀛樺偍浣跨敤绀轰緥
- `WorkerStorageExample.java` - 璁惧瀛樺偍浣跨敤绀轰緥
- `RuleStorageExample.java` - 瑙勫垯瀛樺偍浣跨敤绀轰緥

### 閲嶆瀯鐨勭被

- `TaskManager.java` - 閲嶆瀯鍚庝娇鐢═askStorage鎺ュ彛
- `WorkerManager.java` - 閲嶆瀯鍚庝娇鐢―eviceStorage鎺ュ彛
- `RuleManager.java` - 閲嶆瀯鍚庝娇鐢≧uleStorage鎺ュ彛 
