# 变更日志（CHANGELOG�?

本文件用于记�?`com.xa.mass.engine.v2` 包下主要类的变更历史�?

---

## 2024-07-12

### MessageStreamProviderRegistry (xa-mass-core)
- **增强类型转换逻辑**：添加对字符串类型队列类型的支持，自动转换为QueueProviderType枚举
- **新增测试清理方法**：添加`clearCache()`方法用于测试时清理缓存，避免测试间冲�?
- **提升健壮�?*：改进错误处理，确保传入的类型总是被正确处�?

### EngineRegistry
- **新增测试清理方法**：添加`clearDefaultServices()`和`clearAllServices()`方法
- **支持测试隔离**：允许测试运行时清理全局服务状态，避免测试间干�?

### TaskRepositoryManager
- **修复测试冲突**：解�?Unsupported queue type: memory"错误
- **增强测试稳定�?*：在测试类中添加缓存和服务状态清�?
- **完善测试覆盖**：确保所有测试用例能够独立运行且互不干扰

### 测试改进
- **TaskRepositoryManagerTest**：添加`@BeforeEach`清理逻辑，确保测试隔�?
- **DataFlowIntegrationTest**：添加全局状态清理，避免与其他测试冲�?
- **测试稳定性提�?*：所�?9个测试用例现在可以稳定通过

---

## 2024-06-25

### DeviceRepositoryManager
- 字段 `deviceEntityMap` �?`tokenEntityMap` 类型�?`ConcurrentMap` 改为业务抽象 `MessageMap`，并推荐使用 `InMemoryMessageMap` 实现�?
- 保持 `projectTokenEntityMap` �?`ConcurrentMap`，保证多线程安全�?
- 构造函数参数类型与字段类型保持一致�?
- main 方法示例初始化方式同步调整�?

### TaskRepositoryManager
- 字段 `taskSeedsMap` �?`taskMsgMap` 类型�?`Map` 改为 `ConcurrentMap`，并�?`ConcurrentHashMap` 初始化，提升线程安全�?
- 业务方法增加参数校验和异常处理�?
- 完善了单元测试，覆盖正常流程、异常情况和并发场景�?

---

如有更多变更，请在此处补充�?
