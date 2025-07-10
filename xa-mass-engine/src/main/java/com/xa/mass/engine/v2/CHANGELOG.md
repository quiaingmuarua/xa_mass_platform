# 变更日志（CHANGELOG）

本文件用于记录 `com.xa.mass.engine.v2` 包下主要类的变更历史。

---

## 2024-06-25

### DeviceRepositoryManager
- 字段 `deviceEntityMap` 和 `tokenEntityMap` 类型由 `ConcurrentMap` 改为业务抽象 `MessageMap`，并推荐使用 `InMemoryMessageMap` 实现。
- 保持 `projectTokenEntityMap` 为 `ConcurrentMap`，保证多线程安全。
- 构造函数参数类型与字段类型保持一致。
- main 方法示例初始化方式同步调整。

### TaskRepositoryManager
- 字段 `taskSeedsMap` 和 `taskMsgMap` 类型由 `Map` 改为 `ConcurrentMap`，并用 `ConcurrentHashMap` 初始化，提升线程安全。
- 业务方法增加参数校验和异常处理。
- 完善了单元测试，覆盖正常流程、异常情况和并发场景。

---

如有更多变更，请在此处补充。 