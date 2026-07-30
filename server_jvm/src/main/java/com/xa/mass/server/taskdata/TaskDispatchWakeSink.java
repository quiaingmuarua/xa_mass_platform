package com.xa.mass.server.taskdata;

public interface TaskDispatchWakeSink {

    boolean offer(String taskId);
}
