package com.xa.mass.engine.v2.entity;

import com.xa.mass.base.model.User;

import java.util.Map;

public class TaskEntity {
    private String taskId;
    private String taskName;
    private String project;
    private String taskStatus; //NEW BLOCKED READY RUNNING PAUSED TERMINAL
    private String taskCountry;
    private User user;
    private long  taskCount;
    private String textContent;
    private Map<String,Object> taskScheduleRules; //任务分配规则，比如batch_size
    private Map<String,Object> taskDeviceMatchRules; //任务绑定设备规则
    private long  createTime;
    private long  updateTime;
    private long  startTime;
    private long  endTime;
}