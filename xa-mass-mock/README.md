# xa-mass-mock

Mock 全链路应用模块，提供完整的模拟环境，包括 Gateway、Engine 和 API 服务。

## 功能特性

- 🚀 **全链路启动** - 一键启动 Gateway、Engine 和 Web API
- ⚙️ **灵活配置** - 支持开发/生产环境配置
- 🔧 **组件选择** - 可选择启动特定组件
- 📊 **Mock数据** - 支持设备、任务、规则数据模拟
- 🔌 **WebSocket服务** - 提供实时通信能力

## 快速启动

### 1. 开发环境启动
```bash
# 使用默认配置启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或指定配置文件
java -jar xa-mass-mock.jar --spring.profiles.active=dev
```

### 2. 生产环境启动
```bash
# 使用生产配置启动
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# 或指定配置文件
java -jar xa-mass-mock.jar --spring.profiles.active=prod
```

## 配置说明

### 核心配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `mass.server.port` | 18088 | Mass WebSocket 服务器端口 |
| `mass.gateway.max-connections` | 1000 | 网关最大连接数 |
| `mass.gateway.enabled` | true | 是否启用网关 |
| `mass.engine.worker-threads` | 8 | 引擎工作线程数 |
| `mass.engine.enabled` | true | 是否启用引擎 |
| `mass.mock.data.devices` | mock/mock_devices.json | 设备配置文件路径 |
| `mass.mock.data.tasks` | mock/mock_tasks.json | 任务配置文件路径 |
| `mass.mock.data.rules` | mock/mock_rules.json | 规则配置文件路径 |

### 环境配置

#### 开发环境 (application-dev.yml)
- 较小的连接数和线程数
- DEBUG 级别日志
- 适合开发和测试

#### 生产环境 (application-prod.yml)
- 更大的连接数和线程数
- INFO 级别日志
- 适合生产部署

## 服务地址

启动成功后，可通过以下地址访问服务：

### Web API 服务
- **状态概览**: http://localhost:8088/status
- **任务管理**: http://localhost:8088/status/tasks
- **设备管理**: http://localhost:8088/status/devices
- **规则管理**: http://localhost:8088/status/rules
- **API 文档**: http://localhost:8088/doc.html

### WebSocket 服务
- **WebSocket**: ws://localhost:18088

## 启动流程

1. **Spring Boot 启动** - 初始化 Web API 服务
2. **MassApplication 构建** - 使用 Builder 模式构建应用
3. **组件启动** - 根据配置启动 Gateway 和 Engine
4. **健康检查** - 验证组件启动状态
5. **Mock数据加载** - 加载模拟数据
6. **事件发布** - 发布初始任务事件

## 错误处理

- **启动失败** - 详细的错误日志和异常分类
- **Mock数据加载失败** - 警告日志，不影响启动
- **事件发布失败** - 警告日志，不影响启动
- **优雅关闭** - 注册关闭钩子，确保资源释放

## 扩展配置

### 自定义配置
```yaml
# application-custom.yml
mass:
  server:
    port: 19000
  gateway:
    max-connections: 2000
  engine:
    worker-threads: 12
  mock:
    data:
      devices: custom/devices.json
      tasks: custom/tasks.json
      rules: custom/rules.json
```

### 启动命令
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=custom
```

## 依赖模块

- **xa-mass-starter** - 启动和配置管理
- **xa-mass-gateway** - 网关服务
- **xa-mass-engine** - 引擎服务
- **xa-mass-api** - Web API 服务

## 开发指南

### 添加新配置
1. 在 `MockApplicationSpringBootApp` 中添加 `@Value` 注解
2. 在配置文件中添加对应配置项
3. 在 Builder 中使用配置值

### 添加新组件
1. 在 `MassApplicationBuilder` 中添加组件配置
2. 在 `MassApplication` 中添加组件生命周期管理
3. 在配置文件中添加组件配置项

## 故障排除

### 常见问题

1. **端口冲突**
   - 检查 8088 和 18088 端口是否被占用
   - 修改配置文件中的端口设置

2. **Mock数据加载失败**
   - 检查配置文件路径是否正确
   - 确认 JSON 文件格式是否有效

3. **组件启动失败**
   - 检查组件配置是否正确
   - 查看详细错误日志

### 日志级别调整
```yaml
logging:
  level:
    com.xa.mass: DEBUG  # 调整为 DEBUG 获取更多信息
``` 