package com.xa.mass.engine.model.task;

import java.util.List;
import java.util.Map;

/**
 * 任务创建请求 DTO
 * 支持多业务、多通道扩展
 */
public class TaskCreateRequestDto {
    /** 业务归属用户（或ID） */
    private String userId;

    /** 所属 project/app（如 "whatsapp", "rcs" 等） */
    private String project;

    /** 任务名称 */
    private String taskName;

    /** 任务内容（支持模版） */
    private String textContent;

    /** 目标列表（最简单的手机号、账号等） */
    private List<String> targetList;

    /** 目标类型（如 "phone", "email", "json"），预留后续多通道扩展 */
    private String targetType;

    /** 区域/国家代码 */
    private String countryCode;

    /** 附加属性（可选，业务自定义，比如计划时间、分组等） */
    private Map<String, Object> extraParams;

    /** 扩展：自定义 TaskMsg 的 Json 字符串（如果不是简单 string） */
    private List<String> targetJsonList;

    /** 每个设备批次消息数 */
    private int batchSize;

    public TaskCreateRequestDto() {}

    public TaskCreateRequestDto(String userId, String project, String taskName, String textContent, List<String> targetList, String targetType, String countryCode, Map<String, Object> extraParams, List<String> targetJsonList) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.textContent = textContent;
        this.targetList = targetList;
        this.targetType = targetType;
        this.countryCode = countryCode;
        this.extraParams = extraParams;
        this.targetJsonList = targetJsonList;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public List<String> getTargetList() { return targetList; }
    public void setTargetList(List<String> targetList) { this.targetList = targetList; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public Map<String, Object> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; }
    public List<String> getTargetJsonList() { return targetJsonList; }
    public void setTargetJsonList(List<String> targetJsonList) { this.targetJsonList = targetJsonList; }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
