package com.xa.mass.engine.listener;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;

import java.util.List;

/**
 * 消息分配监听器接口
 */
public interface TaskMsgAssignListener {
    void onMsgAssign(Task task, List<Device> devices);
} 