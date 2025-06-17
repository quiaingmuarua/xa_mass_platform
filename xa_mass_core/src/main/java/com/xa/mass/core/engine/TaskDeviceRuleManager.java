package com.xa.mass.core.engine;

import com.xa.mass.core.model.device.Device;
import com.xa.mass.core.model.task.Task;

import java.util.List;

public class TaskDeviceRuleManager {


    List<TaskDeviceRule> taskDeviceRules;

    public static void main(String[] args) {

        TaskDeviceRuleManager taskDeviceRuleManager = new TaskDeviceRuleManager();

        taskDeviceRuleManager.addRule(((task, device) -> device.getDeviceState() == 1));


    }

    public void addRule(TaskDeviceRule taskDeviceRule) {
        taskDeviceRules.add(taskDeviceRule);
    }

    public void executeAllRules(Task task, Device device) {
        for (TaskDeviceRule taskDeviceRule : taskDeviceRules) {
            taskDeviceRule.matcher(task, device);
        }

    }
}
