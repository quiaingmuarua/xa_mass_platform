> Archived during repository convergence.
> Start from:
> - [../../AGENTS.md](../../AGENTS.md)
> - [../AGENT_BASELINE.md](../AGENT_BASELINE.md)
> - [../VERIFIED_RUNBOOK.md](../VERIFIED_RUNBOOK.md)

# XA Mass Platform - Documentation Index

> **⚠️ 部分过时** �?以下文档已删除（daily/、doc/规划.md、v2 草稿），索引内容不再完整�?
> Agent 入口请直接看 [`AGENTS.md`](./AGENTS.md)�?
> 当前权威参考：
> - [`doc/AGENT_BASELINE.md`](./doc/AGENT_BASELINE.md)
> - [`doc/VERIFIED_RUNBOOK.md`](./doc/VERIFIED_RUNBOOK.md)

## 📚 Documentation Overview

This repository contains both current and historical documentation. Some files describe intended architecture rather than verified runtime behavior.

## 📖 Available Documentation

### 1. [API_DOCUMENTATION.md](./API_DOCUMENTATION.md) 📋

**Comprehensive API Documentation**

- Complete REST API reference with examples
- Event Bus System documentation
- Core Engine APIs (TaskManager, DeviceManager, etc.)
- Gateway & WebSocket APIs
- Configuration & Management guides
- Integration examples and patterns

**Best for:** Developers who need complete API reference and detailed integration guidance.

### 2. [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) �?

**Quick Reference Guide**

- Common API endpoints and usage
- Configuration properties
- Task status flows
- Event types reference
- Testing & mock setup
- Troubleshooting guide

**Best for:** Quick lookup after you have checked the verified runbook.

### 3. [README.md](./README.md) 🏠

**Project Overview**

- Project introduction and architecture
- Module structure explanation
- Getting started guide
- Development setup instructions

**Best for:** First-time users and project overview.

## 🏗�?Platform Architecture Summary

```
xa-mass-platform/
├── xa-mass-api/        # REST API layer with controllers and DTOs
├── xa-mass-core/       # Core infrastructure & event bus system
├── xa-mass-engine/     # Business logic, task scheduling & device management
├── xa-mass-gateway/    # WebSocket gateway & message routing
├── xa-mass-runtime/    # Application startup & module aggregation
└── xa-mass-mock/       # Comprehensive testing & mock framework
```

## 🎯 Key Features Documented

### �?REST APIs

- **Task Management**: Create, update, control, and monitor tasks
- **Session Management**: WebSocket session tracking and statistics
- **System Health**: Monitoring and observability endpoints

### �?Event-Driven Architecture

- **EventBus System**: 高性能泛型事件总线，支持任意POJO和结构化事件
- **Multi-Implementation**: 内存(InMemoryMessageStream) / Redis(LettuceRedisStream)分布式支�?
- **Event Types**: 灵活的事件类型设计，支持trace追踪和元数据
- **Custom Events**: 既支持轻量级POJO，也支持完整结构化MassEvent
- **Performance**: 20K+ events/sec吞吐量，精确匹配分发算法

### �?Core Components

- **TaskManager**: Complete task lifecycle management
- **DeviceManager**: Device state and token allocation
- **TaskScheduler**: Flexible task scheduling strategies
- **Rule Engine**: QLExpress-based rule evaluation system

### �?WebSocket Gateway

- **Real-time Communication**: Netty-based WebSocket server
- **Message Transport**: Multi-level message queue system
- **Protocol Adaptation**: Flexible message handling and routing

### �?Testing & Mock Framework

- **End-to-end Testing**: Complete mock device and task simulation
- **Configuration-driven**: JSON-based mock scenario configuration
- **Integration Testing**: Spring Boot test integration patterns

## 🚀 Getting Started Quickly

1. **Reality Check First**: Start with [doc/AGENT_BASELINE.md](./doc/AGENT_BASELINE.md)
2. **Verified Commands**: Use [doc/VERIFIED_RUNBOOK.md](./doc/VERIFIED_RUNBOOK.md)
3. **Project Overview**: Then read [README.md](./README.md)
4. **Quick Operations**: Use [QUICK_REFERENCE.md](./QUICK_REFERENCE.md)

## 📋 Common Use Cases

### 🔨 For Developers

- **Building integrations**: Use REST API documentation
- **Event handling**: Implement event listeners with EventBus guide
- **Custom components**: Extend TaskScheduler or DeviceSelector interfaces
- **Testing**: Set up mock scenarios for integration testing

### 🎮 For System Operators

- **Monitoring**: Use health check endpoints and logging configuration
- **Troubleshooting**: Follow diagnostic procedures in quick reference
- **Performance tuning**: Apply configuration best practices
- **Deployment**: Use Spring Boot integration patterns

### 🧪 For QA/Testing

- **Mock testing**: Configure comprehensive test scenarios
- **API testing**: Use provided curl examples and Postman collections
- **Integration testing**: Set up Spring Boot test environments
- **Load testing**: Use mock device scaling capabilities

## 🔗 External Dependencies

The platform integrates with several key technologies:

- **Spring Boot 3.3.0**: Core framework and dependency injection
- **Netty**: High-performance WebSocket server implementation
- **Guava EventBus / old.eventbus**: still part of the current runtime path
- **QLExpress**: Rule engine for flexible business logic evaluation
- **Logback**: Structured JSON logging with observability features

## 📊 API Coverage Summary

### REST Endpoints

- �?**10+ Task Management APIs**: Complete CRUD and control operations
- �?**Session Management APIs**: Connection tracking and statistics
- �?**Health/Monitoring APIs**: System observability endpoints

### Java APIs

- �?**30+ Public Classes/Interfaces**: Comprehensive business logic APIs
- �?**Event System**: 15+ event types with extensible framework
- �?**Configuration APIs**: Flexible system configuration options

### WebSocket Protocol

- �?**Message Types**: Device registration, task assignment, status updates
- �?**Connection Management**: Session tracking and heartbeat mechanisms
- �?**Protocol Extensions**: Middleware chain for custom message handling

## 🎨 Code Examples Included

Each documentation file includes practical examples:

- **REST API calls** with curl commands
- **Java code snippets** for integration
- **Configuration files** with all options
- **WebSocket message formats** and protocols
- **Testing setups** with Spring Boot
- **Custom implementations** for extensibility

## 🔧 Maintenance & Updates

This documentation is designed to be:

- **Mixed Fidelity**: contains both verified docs and historical design docs
- **Example-Rich**: Practical, runnable examples for every feature
- **Comprehensive**: Covers all public APIs and integration patterns
- **Accessible**: Multiple documentation levels for different user needs

## 📁 Module-Level Documentation

### Core Infrastructure (xa-mass-core)

- 🎯 **[EventBus Documentation](./xa-mass-core/src/main/java/com/xa/mass/base/channel/eventbus/README.md)** - 泛型事件总线完整指南
- 📊 **[JSON-DSL System](./xa-mass-core/README_NEW_DSL.md)** - 灵活的JSON DSL数据生成
- 🔄 **[Channel Messaging](./xa-mass-core/src/main/java/com/xa/mass/base/channel/queue/README.md)** - 统一消息传输抽象

### Business Logic (xa-mass-engine)

- 🎮 **[Rule Engine](./xa-mass-engine/README_RULE_ENGINE.md)** - QLExpress规则引擎
- ⚙️ **[Task Management](./xa-mass-engine/README.md)** - 任务调度与生命周�?

### Communication Layer (xa-mass-gateway)

- 🌐 **[WebSocket Gateway](./xa-mass-gateway/README.md)** - 实时通信网关
- 📡 **[Message Routing](./xa-mass-gateway/src/main/java/com/xa/mass/gateway/queue/README.md)** - 消息路由与队�?

### Testing & Development (xa-mass-mock)

- 🧪 **[Mock Framework](./xa-mass-mock/README.md)** - 完整的测试与模拟框架
- 📝 **[Testing Patterns](./xa-mass-mock/verify-no-conflict.md)** - 测试最佳实�?

## 📞 Support & Contribution

For questions, issues, or contributions:

1. Check the troubleshooting section in [QUICK_REFERENCE.md](./QUICK_REFERENCE.md)
2. Review integration examples in [API_DOCUMENTATION.md](./API_DOCUMENTATION.md)
3. Refer to existing implementation patterns for guidance

---

**Document Version**: 1.0  
**Last Updated**: April 12, 2026  
**Platform Version**: 0.0.1-SNAPSHOT  
**Coverage**: Mixed; verify against baseline/runbook before relying on a document


