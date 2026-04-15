package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.List;

/**
 * 消息分配监听器接口
 */
public interface TaskMsgAssignListener {
    List<TaskMsg> onMsgAssign(Task task, List<Device> devices);
}
