package com.xa.mass.core.engine;


import com.xa.mass.core.engine.model.device.Device;
import com.xa.mass.core.engine.model.task.Task;

@FunctionalInterface
public interface TaskDeviceRule {

    boolean matcher(Task task, Device device);
}
