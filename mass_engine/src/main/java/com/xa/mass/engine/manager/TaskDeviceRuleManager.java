package com.xa.mass.engine.manager;

import com.xa.mass.engine.rule.TaskDeviceRule;
import com.xa.mass.model.Device;
import com.xa.mass.model.Task;


import java.util.List;

public class TaskDeviceRuleManager {


    List<TaskDeviceRule> taskDeviceRules;




    public void addRule(TaskDeviceRule taskDeviceRule) {
        taskDeviceRules.add(taskDeviceRule);
    }


    public void executeAllRules(Task task, Device device) {
        for (TaskDeviceRule taskDeviceRule : taskDeviceRules) {
            taskDeviceRule.matcher(task,device);
        }

    }


    public static void main(String[] args) {

        TaskDeviceRuleManager taskDeviceRuleManager = new TaskDeviceRuleManager();

        taskDeviceRuleManager.addRule(((task, device) -> device.getDeviceState()==1));




    }
}
