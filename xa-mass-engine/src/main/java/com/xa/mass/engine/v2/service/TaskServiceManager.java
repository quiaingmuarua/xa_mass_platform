package com.xa.mass.engine.v2.service;


public interface TaskServiceManager {

     TaskService getTaskService(String project);

      void registerTaskService(String project,TaskService taskService);
}
