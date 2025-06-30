# xa-mass-api 模块接口文档

## 1. 任务管理相关接口（TaskApiController）

| 方法   | 路径                                 | 描述         | 请求体/参数         | 返回示例/说明         |
|--------|--------------------------------------|--------------|---------------------|-----------------------|
| POST   | /status/api/tasks                    | 创建任务     | TaskCreateRequestDto| { success, message, taskId } |
| GET    | /status/api/tasks/{taskId}           | 获取任务详情 | 路径参数 taskId     | { success, task }     |
| PUT    | /status/api/tasks/{taskId}/status    | 更新任务状态 | 路径参数 taskId, 请求参数 status (TaskStatus) | { success, message, newStatus } |
| POST   | /status/api/tasks/{taskId}/audit     | 审核任务     | 路径参数 taskId, 请求参数 approved, comment | { success, message, newStatus } |
| POST   | /status/api/tasks/{taskId}/pause     | 暂停任务     | 路径参数 taskId     | { success, message }  |
| POST   | /status/api/tasks/{taskId}/resume    | 恢复任务     | 路径参数 taskId     | { success, message }  |
| POST   | /status/api/tasks/{taskId}/terminate | 中止任务     | 路径参数 taskId     | { success, message }  |
| DELETE | /status/api/tasks/{taskId}           | 删除任务     | 路径参数 taskId     | { success, message }  |
| PUT    | /status/api/tasks/{taskId}           | 编辑任务     | 路径参数 taskId, 请求体 TaskCreateRequestDto | { success, message }  |

### 说明
- 所有接口返回统一结构：`{ success: boolean, message: string, ... }`
- 任务状态（status）为枚举类型：如 `READY`、`PAUSED`、`TERMINAL` 等
- 创建/编辑任务时需传递 `TaskCreateRequestDto`，字段包括 taskName、project、countryCode、textContent、userId、targetList、batchSize 等

---

## 2. 全局配置相关接口（GlobalConfigController）

| 方法 | 路径                | 描述         | 请求体/参数 | 返回示例/说明         |
|------|---------------------|--------------|-------------|-----------------------|
| GET  | /api/config/projects| 获取所有项目 | 无          | ApiResponse<List<Project>> |

### 说明
- 返回结构为 `ApiResponse`，包含 code、message、data 字段
- Project 为枚举，包含 code、name 等属性

---

## 3. 典型响应结构

### ApiResponse<T>
```json
{
  "code": 0,
  "message": "成功",
  "data": { ... }
}
```
或
```json
{
  "code": 500,
  "message": "错误信息",
  "data": null
}
```

### 任务相关接口返回
```json
{
  "success": true,
  "message": "任务创建成功",
  "taskId": "123456"
}
```

---

## 4. 页面渲染相关接口（StatusPageController、ConfigController）

| 方法 | 路径             | 描述         | 返回         |
|------|------------------|--------------|--------------|
| GET  | /status          | 状态总览页面 | status.html  |
| GET  | /status/tasks    | 任务页面     | tasks.html   |
| GET  | /status/devices  | 设备页面     | devices.html |
| GET  | /status/rules    | 规则页面     | rules.html   |
| GET  | /config          | 全局配置页面 | config.html  |

> 这些接口返回页面模板，供浏览器直接访问。

---

## 5. 其他说明

- 所有 API 路径均为 RESTful 风格，推荐使用 JSON 作为数据交互格式。
- 建议前端统一处理 `ApiResponse` 和 `{ success, message, ... }` 结构的响应。
- 如需详细字段说明或补充其他接口，请联系后端开发。 