package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;

import java.util.List;

/**
 * 娑堟伅鍒嗛厤鐩戝惉鍣ㄦ帴鍙?
 */
public interface TaskDispatchBinder {
    List<TaskDispatchBinding> bindDispatches(Task task, List<SelectedWorkerHandle> selectedWorkers);
}

