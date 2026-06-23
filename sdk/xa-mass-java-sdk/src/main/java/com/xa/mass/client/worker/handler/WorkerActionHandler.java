package com.xa.mass.client.worker.handler;

import com.xa.mass.client.worker.WorkerAction;

@FunctionalInterface
public interface WorkerActionHandler {
    WorkerActionResult handle(WorkerAction action) throws Exception;
}
