# test 目录

## 📖 说明

本目录包含 channel 各子模块的单元测试用例，覆盖注册、分发、批量、异常、桥接等主流程，保障通信中台的稳定性和可回归性。

---

## 🚀 测试覆盖

- 消息流/队列/Map/Set的功能测试
- 事件总线的注册、分发、注销、异常隔离
- 传输器/桥接器的集成测试

---

## 🏃 如何运行

- 使用 `mvn test` 或在 IDE 中运行对应测试类
- 推荐持续集成环境自动执行

---

## 📚 推荐阅读

- [messaging/README.md](../messaging/README.md)
- [eventbus/README.md](../eventbus/README.md)
- [tranporter/README.md](../tranporter/README.md) 