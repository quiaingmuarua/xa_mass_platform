# XA Mass 使用与开发指南

Status: current user and developer guide entrypoint.

XA Mass 把一批工作交给持续变化的 Java 或 Android Worker 执行，记录任务进度和结果，
并根据 Worker 的状态、属性和执行反馈继续分配工作。业务通过现有 API、页面和 Worker
能力接入；资源选择、执行资格和任务收尾分别由平台中明确的 Owner 负责。

## 从这里开始

| 你现在要做什么 | 阅读入口 | 完成后应得到什么 |
| --- | --- | --- |
| 第一次体验项目 | [快速启动](quick-start.md) → [完成第一个任务](first-task.md) | 真实 Worker 执行、费用确认、逐条结果和导出 |
| 批量查询号码或文本 | [Batch Checks](usage/batch-checks.md) | 选择检查能力，导入种子，审核启动并读取业务答案 |
| 发送消息并观察后续回执 | [Messages](usage/messages.md) | 分清发送完成与送达、已读、回复 |
| 申请号码并接收模拟短信 | [SMS](usage/sms.md) | 用原 messageId 持续查询接收窗口 |
| 调试能力或使用通用任务 | [任务工作台](usage/workbench.md) | 选择有限 Task、RPC 或 Direct Debug |
| 部署或处理运行问题 | [部署](operations/deploy.md) → [配置归属](operations/configuration.md) → [排障](operations/troubleshooting.md) | 明确环境坐标、资源声明和故障所在层 |
| 让 Agent 修改代码 | [开发入口](development/index.md) → [能力接入走读](development/walkthrough.md) | 找到 Owner、调用者、消费者及所需证明 |
| 理解机制及项目能力 | [架构](architecture.md)、[验证](verification.md)、[实测能力](capabilities.md) | 区分系统机制、测试主张和特定版本的实测结果 |

## 当前可以做什么

平台提供有限批量任务、可复用 RPC Task、按身份直接调用，以及 Worker 注册、连接、
属性和任务结果观察。Preview 将 Batch Checks、Messages 和 SMS 装配在同一平台上，
让这些流程通过真实 Java Worker SDK 运行。

Preview 中的注册查询、消息收件端和短信输入是演示能力。更换为真实业务时，需要
Host 安装对应 Handler，并配置 Group、Project 和所需资源；勾选页面选项不会安装
Worker 能力。公开 Mock 演示只模拟页面与业务状态，不能证明后端执行。

## 按需要理解概念

先认识 **Task / Item、Worker / Group、Event / Handler、Result**，就能使用基本流程。
配置资源选择时再阅读 **Properties、Pool、query**；修改执行资格、重试或恢复时，
才需要进入 **Score、lease、Pacer**。这些概念的定义和写入权留在
[架构主线](../kernel/scheduling-overview.md)与各模块 Owner。

本指南解释如何使用、定位和验证。详细字段、状态转换、配置默认值和失败窗口，以
同一源码版本的 Owner 和实现为准。项目仍在快速迭代；当前页面不承诺历史 API、
配置或数据形状继续兼容。仓库内阅读从 [README](../../README.md) 和
[文档职责索引](../README.md)继续。
