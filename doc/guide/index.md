# XA Mass 使用与开发指南

Status: current user and developer guide entrypoint.

XA Mass 将一批工作交给 Java 或 Android Worker 执行，记录进度、结果与后续观察，
并根据 Worker 的状态和能力继续分配工作。Host 安装实际能力，平台负责通用任务与执行流程。
先选择你要完成的工作，再进入需要的机制。

## 从这里开始

| 你的任务 | 阅读路线 | 完成标志 |
| --- | --- | --- |
| **使用与运维现有环境** | [认识并接管环境](operations/deploy.md#release-preview) → [配置与生效](operations/configuration.md) → [按现象排障](operations/troubleshooting.md) | 知道环境身份、可用业务、状态含义和变更入口 |
| **在本地体验** | [快速启动](quick-start.md) → [第一个 MD5 任务](first-task.md) | 核对真实 Worker 的执行、费用、三个答案和 CSV |
| **让 Agent 修改代码** | [按任务定位](development/index.md) → [能力接入走读](development/walkthrough.md) | 找到 Owner、实现、调用者和对应断言 |

已有环境不要求安装 Preview 的演示能力；以该环境生效的 Catalog、授权和实际 Host 为准。
项目理解和普通开发使用仓库证据；只有明确的部署、现场诊断或公网验收任务才访问主机。

## 可以使用哪些能力

| 能力 | 用途与入口 | 使用前确认 |
| --- | --- | --- |
| [Batch Checks](usage/batch-checks.md) | 导入号码或文本，核对费用，批量检查与导出 | 检查配置、用户积分、Group 资源和实际 Handler |
| [Messages](usage/messages.md) | 有限发送任务及后续送达、已读、回复 | 应用到 Group 的绑定和接收端的真实能力 |
| [SMS Reception](usage/sms.md) | 取号并用原 messageId 读取最新短信 | Preview 场景；演示短信由 Host 输入 |
| [任务工作台](usage/workbench.md) | 有限 Task、可复用 RPC、定向 Direct Debug | 选择合适执行方式及结果读取语义 |
| 历史结果与日常管理 | 任务的 Storage 历史、用户／密钥／积分、Worker 接入控制 | 角色／Project 授权；[数据与配置归属](operations/configuration.md) |

`consumer` 默认启用 Batch Checks 和 Messages，需提供外部业务与资源配置；Worker 独立接入。
`preview` 提供三个场景和 Java Simulator，公开 Mock 只模拟页面。演示中的注册查询、短信、
收件端不代表真实外部服务；页面声明也不会安装 Worker Handler。

## 按任务逐步认识概念

使用业务先认识 **Task / Item、Worker / Group、Event / Handler、Result**。
运维再区分 **环境版本、两类凭据、配置生效、Runtime / Storage**；
开发资源选择时进入 **Properties、Pool、query**，修改调度才深入 **Score、lease、Pacer**。
[架构图](architecture.md)解释关系，[验证](verification.md)说明证据边界，
[能力记录](capabilities.md)给出有日期和条件的实测结论。

本指南解释当前构建的操作与导航。精确字段、状态和生命周期由同版本 Owner 维护，
入口见[仓库阅读路径](../../README.md#reading-path)和[文档职责索引](../README.md)。
项目持续迭代，不承诺旧 API、配置或数据形状继续兼容；随包文档也不代表最新 main。
