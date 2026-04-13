## 过滤器新接口说明（2025年7月更新）

### 1. 统一结构化返回

- 所有过滤相关API统一返回 `FilterResult<T>`，包含：
    - `getPassed()`：通过的对象列表
    - `getFailed()`：被拒绝明细（含未通过原因）
    - `getRejectRate()`：拒绝率
    - `getTotal()`：总数
- 通过 `ProcessingContext` 参数可灵活控制是否包含失败明细（如 `ctx.setParameter("includeFailedDetail", true)`）。

### 2. filterWithReport 已废弃

- 原 `filterWithReport` 接口已移除，所有明细和统计需求请用 `FilterResult` 结构。
- 示例：

```java
ProcessingContext ctx = new ProcessingContext();
ctx.

setParameter("includeFailedDetail",true);

FilterResult<Device> result = filterProcessor.filter(devices, filterDef, ctx);
System.out.

println("通过: "+result.getPassed().

size());
        System.out.

println("拒绝率: "+result.getRejectRate());
        if(result.

getFailed() !=null){
        for(
FilterReport.FilterFail<Device> fail :result.

getFailed()){
        System.out.

println("设备: "+fail.getObject().

getDeviceId() +", 原因: "+fail.

getFailedConditions());
        }
        }
```

### 3. 兼容性

- 所有原有 List<T> filter(...) 调用点已迁移为 `FilterResult<T>.getPassed()`。
- 测试和示例代码已同步更新。

### 4. 适用场景

- 支持业务/测试灵活获取过滤明细、统计、通过/拒绝对象等。
- 便于后续扩展更多统计项或自定义返回内容。 