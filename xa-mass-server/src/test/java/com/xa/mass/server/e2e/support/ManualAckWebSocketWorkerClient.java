package com.xa.mass.server.e2e.support;

import com.google.gson.JsonObject;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reusable websocket test worker that captures task-dispatch frames and sends
 * explicit result callbacks under test control.
 *
 * <p>This keeps lifecycle/result proof tests on the real dispatch/result path
 * without each class re-implementing the same frame capture and ack logic.</p>
 */
public class ManualAckWebSocketWorkerClient extends SampleWorkerWebSocketClient {
    private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

    public ManualAckWebSocketWorkerClient(URI serverUri, String workerGroupId, String workerId) {
        super(AbstractSampleE2eTest.withWorkerRouteKey(
                serverUri,
                CanonicalWorkerGroupRouteKeyCodec.encode(workerGroupId)
        ), workerId, workerGroupId);
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject frame = WsFrameTestSupport.parse(message);
            if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                taskQueue.offer(frame);
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the base client for non-task frames or malformed payloads.
        }
        super.onMessage(message);
    }

    public JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
        return taskQueue.poll(timeout, unit);
    }

    public void sendSuccess(JsonObject taskFrame, String detail) throws Exception {
        sendResult(taskFrame, "SUCCESS", detail);
    }

    public void sendFailure(JsonObject taskFrame, String detail) throws Exception {
        sendResult(taskFrame, "FAILED", detail);
    }

    public void sendResult(JsonObject taskFrame, String status, String detail) throws Exception {
        sendMessage(WsFrameTestSupport.buildTaskResult(
                WsFrameTestSupport.resultCorrelationRef(taskFrame),
                status,
                detail
        ));
    }
}
