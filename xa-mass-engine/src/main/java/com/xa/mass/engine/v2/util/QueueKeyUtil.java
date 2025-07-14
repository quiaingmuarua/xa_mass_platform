package com.xa.mass.engine.v2.util;

import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.TaskEntity;

public class QueueKeyUtil {
    /**
     * 获取任务队列 key
     */
    public static String getTaskQueueKey(Project project, String taskId) {
        return "task:" + project.getCode() + ":" + taskId;
    }


    public static String getProjectAllTaskHashKey(Project project){
        return "task:project:" + project.getCode() + ":all:tasks";
    }

    public static String getProjectAllTokenHashKey(Project project){
        return "device:project:" + project.getCode() + ":all:tokens";
    }


    public static  String getDeviceHashKey(){
        return "device:all";
    }

    /**
     * 获取任务种子流队列 key
     */
    public static String getSeedStreamKey(Project project, String taskId) {
        return "task:" + project.getCode() + ":" + taskId + ":seeds";
    }

    /**
     * 获取任务消息流队列 key
     */
    public static String getMsgStreamKey(Project project, String taskId) {
        return "task:" + project.getCode() + ":" + taskId + ":msgs";
    }

    /**
     * 获取项目下某个任务的主队列 key（如 task:project:taskId:tasks）
     */
    public static String getProjectTaskStreamKey(Project project, String taskId) {
        return "task:" + project.getCode() + ":" + taskId + ":tasks";
    }

    /**
     * 获取项目下某个任务的主队列 key，支持 TaskEntity
     */
    public static String getProjectTaskStreamKey(TaskEntity taskEntity) {
        return getProjectTaskStreamKey(Project.valueOf(taskEntity.getProject()), taskEntity.getTaskId());
    }
} 