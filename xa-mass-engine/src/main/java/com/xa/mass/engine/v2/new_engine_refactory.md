
## entity  
基础设计完成
com.xa.mass.base.entity.DeviceEntity
com.xa.mass.base.entity.TaskMsgEntity
com.xa.mass.base.entity.DeviceEntity

```java
package com.xa.mass.engine.schedule;

import com.xa.mass.base.model.User;

import java.util.List;
import java.util.Map;
import java.util.Set;

//project 任务管理  project_task_map    {project: {task_queue:{project}_task_manager,device_token_queue:{project}_device_token_map}}


//任务管理  {project}_task_manager  {taskId:TaskEntity}
class TaskEntity {
    private String taskId;
    private String taskName;
    private String project;
    private String taskStatus; //NEW BLOCKED READY RUNNING PAUSED TERMINAL
    private String taskCountry;
    private User user;
    private String textContent;
    private TaskConfig taskConfig;
    private TaskRuntimeStat taskRuntimeStat;
    private long createTime;
    private long updateTime;
    private long startTime;
    private long endTime;


    class  TaskConfig{
        private Map<String, String> taskScheduleRules; //任务分配规则，比如batch_size
        private Map<String, String> taskDeviceMatchRules; //任务绑定设备规则
        private Map<String, String> taskDeviceRunningRules; //任务绑定设备规则
        private int maxRetryCount; //最大重试次数

        private int runTaskMinDeviceCnt; //行时最低设备数

    }

    class  TaskRuntimeStat{
        private String tid;               // 关联task id
        private long taskCount;
        private Set<String> curBindDevices; //当前绑定的设备
        private Set<String> curBindTokens;  //当前绑定的token
        private int taskInitNumber;       // 总消息数
        private int taskValidNumber;      // 有效消息数
        private int taskExecutedNumber;   // 已完成数
        private int taskUnExecutedNumber; // 剩余未完成
        private int scheduleDeviceCnt;    // 当前调度设备数

    }
}



class TaskMsgEntity {
    private String msgId;
    private String taskId;
    private String taskMsgStatus; //INIT BINDING RUNNING COMPLETE 完整的生命抓过你太
    private String completeStatus; //INIT SUCCESS FAILED EXPIRED  最终状态明细
    List<String> deviceIds; //匹配到的设备
    List<String> deviceTokens; //匹配的到的token
    List<String> batchRawSeeds; //任务种子
    private int retryCount;
    private long sendTime;
    private long createTime;
    private long startTime;
    private long completeTime;


}


// 当前设备多project token管理   {project}_device_token_map    key device_id value TokenEntity
class DeviceEntity {
    private String deviceId;
    private String deviceStatus; //
    private String agentVersion;
    private String onlineStrategy;
    private String groupId;
    private Map<String, String> projectTokens; //key project, value tokenId
    private long lockExpireTime;
    private long lastHeartbeat;
    private long createTime;
    private long updateTime;

}


class TokenEntity {
    private String tokenId;
    private String deviceId;
    private String project;
    private String country;
    private String platform;
    private String tokenStatus;      // 枚举用int或string都可
    private long lastUserTime;       //上次使用事件
    private long expireTime;         // ms
    private long createTime;         // ms
    private long updateTime;         // ms
}

```


## service



##schedule
监听TaskEntity.taskStatus
if taskStatus==NEW  是否需要审核，如果需要审核还是NEW 如果不需要 则进入BLOCKED
if taskStatus==BLOCKED 则 taskScheduleRules 和taskDeviceMatchRules 生成 TaskMsgEntity
if taskStatus==BLOCKED 且taskDeviceRunningRules 满足，则发送

