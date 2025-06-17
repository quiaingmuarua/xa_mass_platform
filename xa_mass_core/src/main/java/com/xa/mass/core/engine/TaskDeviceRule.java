package com.xa.mass.core.engine;


import com.xa.mass.core.model.device.Device;
import com.xa.mass.core.model.task.Task;

@FunctionalInterface
public interface TaskDeviceRule {

    boolean matcher(Task task, Device device);
}
