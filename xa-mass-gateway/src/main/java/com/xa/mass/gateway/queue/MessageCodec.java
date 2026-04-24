package com.xa.mass.gateway.queue;

import com.google.gson.JsonObject;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

public interface MessageCodec {

    JsonObject parseObject(String json);

    String extractWorkerId(JsonObject frame);

    String extractProject(JsonObject frame);

    String extractMessageId(JsonObject frame);

    String extractEventCode(JsonObject frame);

    JsonObject extractPayload(JsonObject frame);

    JsonObject extractControlResponseData(JsonObject frame);

    boolean isEventFirstControlRequest(JsonObject frame);

    boolean isEventFirstControlResponse(JsonObject frame);

    boolean isHeartbeatPing(JsonObject frame);

    boolean isHeartbeatPong(JsonObject frame);

    boolean isTaskStep(JsonObject frame);

    String encodeHeartbeatPong(JsonObject requestFrame);

    String encodeTaskDispatch(TaskDispatchItem item);

    TaskResultReport decodeTaskResult(JsonObject frame);

    String encodeTaskAck(JsonObject requestFrame, int code, String message);

    EventRequest decodeControlEventRequest(JsonObject frame);

    EventPrincipal decodeControlEventPrincipal(JsonObject frame);

    String encodeControlEventResponse(JsonObject requestFrame, EventResponse response);
}
