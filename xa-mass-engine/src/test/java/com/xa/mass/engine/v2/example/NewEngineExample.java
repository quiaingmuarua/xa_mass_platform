package com.xa.mass.engine.v2.example;

import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.service.EngineRegistry;
import com.xa.mass.engine.v2.schedule.DaemonServiceRegistry;
import com.xa.mass.engine.v2.service.TaskService;

public class NewEngineExample {


    public static void main(String[] args) {
        //启动后台服务
        DaemonServiceRegistry daemonServiceRegistry = new DaemonServiceRegistry();
        daemonServiceRegistry.startAll();


        //模拟task 创建
        TaskService taskService = EngineRegistry.getTaskService();

        //模拟任务创建
//        taskService.createTask(new );




    }
}
