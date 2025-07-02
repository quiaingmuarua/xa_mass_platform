# 应用冲突验证

## 端口分配

| 应用 | 端口 | 用途 | 冲突风险 |
|------|------|------|----------|
| MockApplicationSpringBootApp | 8088 | Web API | ❌ 无 |
| WebSocketClientSpringBootApp | 8089 | Web API | ❌ 无 |
| MockApplicationSpringBootApp | 18088 | WebSocket Server | ❌ 无 |
| WebSocketClientSpringBootApp | - | WebSocket Client | ❌ 无 |

## Profile 分离

| 应用 | Profile | 配置文件 | 组件 |
|------|---------|----------|------|
| MockApplicationSpringBootApp | dev/prod | application-dev.yml | Gateway + Engine + API |
| WebSocketClientSpringBootApp | client | application-client.yml | Client + Monitor |

## 同时启动验证

### 1. 启动服务端
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 2. 启动客户端
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=client
```

### 3. 验证服务
```bash
# 检查服务端
curl http://localhost:8088/status

# 检查客户端
curl http://localhost:8089/status

# 检查端口占用
netstat -an | grep -E "8088|8089|18088"
```

## 设计优势

1. **端口隔离**: 不同端口避免冲突
2. **配置隔离**: 不同Profile使用不同配置
3. **组件隔离**: 服务端和客户端职责分离
4. **资源隔离**: 各自管理自己的线程池和连接
5. **监控隔离**: 各自提供独立的状态监控

## 典型使用场景

### 开发测试
- 同时启动服务端和客户端
- 模拟真实的多设备连接场景
- 测试任务分配和设备管理

### 性能测试
- 启动多个客户端实例
- 测试服务端并发处理能力
- 验证连接池和资源管理

### 调试分析
- 服务端专注业务逻辑
- 客户端专注连接管理
- 独立监控和日志分析 