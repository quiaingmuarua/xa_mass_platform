package com.xa.mass.workerpack.sample.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SampleWorkerCommandFrameHandler {

    private static final Logger logger = LoggerFactory.getLogger(SampleWorkerCommandFrameHandler.class);
    private static final String WORKER_COMMAND_FRAME_TYPE = "worker.command";

    boolean isWorkerCommandFrame(JsonObject frame) {
        return frame != null && WORKER_COMMAND_FRAME_TYPE.equals(readString(frame, "type"));
    }

    void handleCommandFrame(JsonObject frame, String currentWorkerId) {
        if (frame == null) {
            return;
        }
        String commandId = readString(frame, "commandId");
        if (commandId == null || commandId.isBlank()) {
            logger.warn("[{}] Ignoring worker command frame without commandId", currentWorkerId);
            return;
        }
        String frameWorkerId = readString(frame, "workerId");
        if (frameWorkerId != null && !frameWorkerId.equals(currentWorkerId)) {
            logger.warn("[{}] Ignoring worker command {} for different worker {}",
                    currentWorkerId, commandId, frameWorkerId);
            return;
        }
        WorkerControlOperations workerControl = SampleCommandRuntime.getService(WorkerControlOperations.class);
        if (workerControl == null) {
            logger.warn("[{}] Ignoring worker command {} because WorkerControlOperations is not registered",
                    currentWorkerId, commandId);
            return;
        }
        workerControl.acknowledgeWorkerCommand(new WorkerCommandAcknowledgementRequest(
                commandId,
                "SUCCEEDED",
                "realtime command frame processed by sample worker"
        ));
    }

    private static String readString(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
