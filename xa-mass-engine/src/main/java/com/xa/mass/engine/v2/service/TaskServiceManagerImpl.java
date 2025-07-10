package com.xa.mass.engine.v2.service;

import java.util.HashMap;
import java.util.Map;

public class TaskServiceManagerImpl implements TaskServiceManager {
    Map<String, TaskService> taskServiceMap = new HashMap<String, TaskService>();


    @Override
    public TaskService getTaskService(String project) {
        return  taskServiceMap.get(project);
    }

    @Override
    public void registerTaskService(String project,TaskService taskService) {
      taskServiceMap.put(project,taskService);
    }



}
