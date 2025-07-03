# XA Mass Platform - Documentation Index

## 📚 Documentation Overview

This directory contains comprehensive documentation for the XA Mass Platform - a multi-module, event-driven message
scheduling and distribution platform built with Spring Boot.

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

### 2. [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) ⚡

**Quick Reference Guide**

- Common API endpoints and usage
- Configuration properties
- Task status flows
- Event types reference
- Testing & mock setup
- Troubleshooting guide

**Best for:** Developers who need quick access to common operations and configurations.

### 3. [README.md](./README.md) 🏠

**Project Overview**

- Project introduction and architecture
- Module structure explanation
- Getting started guide
- Development setup instructions

**Best for:** First-time users and project overview.

## 🏗️ Platform Architecture Summary

```
xa-mass-platform/
├── xa-mass-api/        # REST API layer with controllers and DTOs
├── xa-mass-base/       # Core infrastructure & event bus system
├── xa-mass-engine/     # Business logic, task scheduling & device management
├── xa-mass-gateway/    # WebSocket gateway & message routing
├── xa-mass-starter/    # Application startup & module aggregation
└── xa-mass-mock/       # Comprehensive testing & mock framework
```

## 🎯 Key Features Documented

### ✅ REST APIs

- **Task Management**: Create, update, control, and monitor tasks
- **Session Management**: WebSocket session tracking and statistics
- **System Health**: Monitoring and observability endpoints

### ✅ Event-Driven Architecture

- **EventBus System**: Guava-based event publishing and handling
- **Event Types**: Task events, device events, system events
- **Custom Events**: Guidelines for creating business-specific events

### ✅ Core Components

- **TaskManager**: Complete task lifecycle management
- **DeviceManager**: Device state and token allocation
- **TaskScheduler**: Flexible task scheduling strategies
- **Rule Engine**: QLExpress-based rule evaluation system

### ✅ WebSocket Gateway

- **Real-time Communication**: Netty-based WebSocket server
- **Message Transport**: Multi-level message queue system
- **Protocol Adaptation**: Flexible message handling and routing

### ✅ Testing & Mock Framework

- **End-to-end Testing**: Complete mock device and task simulation
- **Configuration-driven**: JSON-based mock scenario configuration
- **Integration Testing**: Spring Boot test integration patterns

## 🚀 Getting Started Quickly

1. **First Time Users**: Start with [README.md](./README.md)
2. **API Integration**: Go to [API_DOCUMENTATION.md](./API_DOCUMENTATION.md)
3. **Quick Operations**: Use [QUICK_REFERENCE.md](./QUICK_REFERENCE.md)

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
- **Guava EventBus**: Event-driven communication infrastructure
- **QLExpress**: Rule engine for flexible business logic evaluation
- **Logback**: Structured JSON logging with observability features

## 📊 API Coverage Summary

### REST Endpoints

- ✅ **10+ Task Management APIs**: Complete CRUD and control operations
- ✅ **Session Management APIs**: Connection tracking and statistics
- ✅ **Health/Monitoring APIs**: System observability endpoints

### Java APIs

- ✅ **30+ Public Classes/Interfaces**: Comprehensive business logic APIs
- ✅ **Event System**: 15+ event types with extensible framework
- ✅ **Configuration APIs**: Flexible system configuration options

### WebSocket Protocol

- ✅ **Message Types**: Device registration, task assignment, status updates
- ✅ **Connection Management**: Session tracking and heartbeat mechanisms
- ✅ **Protocol Extensions**: Middleware chain for custom message handling

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

- **Living Documentation**: Updated alongside code changes
- **Example-Rich**: Practical, runnable examples for every feature
- **Comprehensive**: Covers all public APIs and integration patterns
- **Accessible**: Multiple documentation levels for different user needs

## 📞 Support & Contribution

For questions, issues, or contributions:

1. Check the troubleshooting section in [QUICK_REFERENCE.md](./QUICK_REFERENCE.md)
2. Review integration examples in [API_DOCUMENTATION.md](./API_DOCUMENTATION.md)
3. Refer to existing implementation patterns for guidance

---

**Document Version**: 1.0  
**Last Updated**: January 2025  
**Platform Version**: 0.0.1-SNAPSHOT  
**Coverage**: All public APIs, functions, and components documented