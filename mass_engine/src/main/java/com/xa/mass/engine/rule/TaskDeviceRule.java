package com.xa.mass.engine.rule;


import com.xa.mass.model.device.Device;
import com.xa.mass.model.task.Task;

@FunctionalInterface
public interface TaskDeviceRule {

    boolean matcher(Task task, Device device);
}
