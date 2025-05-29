package com.xa.mass.engine.rule;


import com.xa.mass.model.Device;
import com.xa.mass.model.Task;

@FunctionalInterface
public interface TaskDeviceRule {

     boolean matcher(Task task, Device device);
}
