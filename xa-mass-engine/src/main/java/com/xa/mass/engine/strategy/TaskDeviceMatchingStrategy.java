package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * Strategy SPI for choosing devices for a task.
 *
 * <p>This is the main extension seam for library/SDK usage where consumers
 * want to provide different matching policies without changing the task
 * lifecycle state machine.</p>
 */
public interface TaskDeviceMatchingStrategy {

    /**
     * Match devices for the given task.
     *
     * @param task task being assigned
     * @param maxDeviceCount upper bound for matched devices
     * @return matched devices, never {@code null}
     */
    List<Device> matchDevices(Task task, int maxDeviceCount);
}
