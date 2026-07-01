package com.xa.mass.task.runtime.starter;

import com.xa.mass.task.runtime.TaskRuntimeAppendPort;
import com.xa.mass.task.runtime.TaskRuntimeClaimPort;
import com.xa.mass.task.runtime.TaskRuntimeDiscardPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressPort;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeRepairPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPort;
import com.xa.mass.task.runtime.TaskRuntimeSchedulerPort;

public interface TaskRuntimePortSet extends TaskRuntimeAppendPort,
        TaskRuntimeSchedulerPort,
        TaskRuntimeClaimPort,
        TaskRuntimeResultPort,
        TaskRuntimeRepairPort,
        TaskRuntimeProgressPort,
        TaskRuntimeReadPort,
        TaskRuntimeDiscardPort {
}
