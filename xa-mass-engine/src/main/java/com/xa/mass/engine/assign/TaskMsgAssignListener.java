package com.xa.mass.engine.assign;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.task.Task;

import java.util.List;

/**
 * 消息分配监听器接口
 */
public interface TaskMsgAssignListener {
    void onMsgAssign(Task task, List<Device> devices);
} 