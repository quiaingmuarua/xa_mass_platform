# xa-mass-mock

本模块为测试与自测模块，负责：
- 集成测试、端到端自测
- WebSocket 客户端/服务端 mock
- 全链路任务/设备/分配 mock 与观测
- 演示与开发环境联调

## 主要能力

- 支持通过 `mock_config.json`（位于 `src/main/resources/`）灵活配置 mock 设备（支持多 token）、任务等，支持模板、批量、占位符。
- mock 主流程入口：`com.xa.mass.mock.engine.MockTaskEngineExample`，支持 main 启动，自动加载 mock 配置。
- 支持外部 JSON 文件热加载，便于多场景切换和复现。
- mock 结果支持分配全链路日志、规则链评估、冲突检测、分配统计等观测能力。
- 适用于端到端集成测试、规则链调试、批量分配演练、设备 token 轮询等复杂场景。

## mock_config.json 示例

详见 `src/main/resources/mock_config.json`，支持如下结构：

```json
{
  "devices": [
    {
      "deviceIdTemplate": "device-{i}",
      "count": 5,
      "groupId": "us",
      "tokens": [
        {
          "tokenIdTemplate": "token-{i}-{j}",
          "count": 2,
          "channel": "us"
        }
      ]
    }
  ],
  "tasks": [
    {
      "taskNameTemplate": "Task-{country}-{i}",
      "countryList": ["us", "gb"],
      "countPerCountry": 2,
      "msgPerTask": 10,
      "batchSize": 3,
      "project": "demoApp"
    }
  ]
}
```

> 不包含生产业务逻辑。 