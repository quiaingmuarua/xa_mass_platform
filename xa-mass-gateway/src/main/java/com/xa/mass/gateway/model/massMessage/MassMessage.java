package com.xa.mass.gateway.model.massMessage;

import com.google.gson.JsonElement;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;

/**
 * WebSocket compatibility frame DTO.
 *
 * <p>This type exists to encode/decode the current gateway wire payload and to
 * bridge legacy tuple-routed frames into the runtime. It is not the canonical
 * model for platform capabilities. Global SDK event identity lives in
 * {@code eventCode} / {@code EventRequest}, while task data-plane semantics live
 * in transport-neutral models such as task dispatch/result DTOs.
 *
 * <p>When adding new business or control capabilities, do not extend
 * {@code MassMessage} with new semantic identity fields or tuple branches unless
 * the WebSocket wire protocol itself requires a compatibility shim.
 */
public class MassMessage {
    private String msgId;               // 全局唯一消息 ID
    private boolean response = false;   // 是否为响应消息（response消息可复用 MessageType）
    private MessageType msgType;        // 协议帧主类型，如 TASK、PING、RESPONSE 等
    private String subMsgType;          // 协议帧子类型，如 step、all、ack、configType 等；不是全局能力标识
    private MessageDirection from;      // CLIENT / SERVER
    private MessageContext context;     // 通信上下文（设备ID、角色、连接信息等）
    private String project;            // canonical task/project code carried across the transport boundary
    private JsonElement payload;        // 实际消息体，统一为 JsonElement

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public boolean isResponse() {
        return response;
    }

    public void setResponse(boolean response) {
        this.response = response;
    }

    public MessageType getMsgType() {
        return msgType;
    }

    public void setMsgType(MessageType msgType) {
        this.msgType = msgType;
    }

    public String getSubMsgType() {
        return subMsgType;
    }

    public void setSubMsgType(String subMsgType) {
        this.subMsgType = subMsgType;
    }

    public MessageDirection getFrom() {
        return from;
    }

    public void setFrom(MessageDirection from) {
        this.from = from;
    }

    public MessageContext getContext() {
        return context;
    }

    public void setContext(MessageContext context) {
        this.context = context;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project == null || project.isBlank() ? null : project.trim();
    }

    public JsonElement getPayload() {
        return payload;
    }

    public void setPayload(JsonElement payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "MassMessage{" +
                "msgId='" + msgId + '\'' +
                ", response=" + response +
                ", msgType=" + msgType +
                ", subMsgType='" + subMsgType + '\'' +
                ", from=" + from +
                ", context=" + context +
                ", project='" + project + '\'' +
                ", payload=" + payload +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MassMessage that = (MassMessage) o;
        return response == that.response &&
                (msgId != null ? msgId.equals(that.msgId) : that.msgId == null) &&
                msgType == that.msgType &&
                (subMsgType != null ? subMsgType.equals(that.subMsgType) : that.subMsgType == null) &&
                from == that.from &&
                (context != null ? context.equals(that.context) : that.context == null) &&
                (project != null ? project.equals(that.project) : that.project == null) &&
                (payload != null ? payload.equals(that.payload) : that.payload == null);
    }

    @Override
    public int hashCode() {
        int result = msgId != null ? msgId.hashCode() : 0;
        result = 31 * result + (response ? 1 : 0);
        result = 31 * result + (msgType != null ? msgType.hashCode() : 0);
        result = 31 * result + (subMsgType != null ? subMsgType.hashCode() : 0);
        result = 31 * result + (from != null ? from.hashCode() : 0);
        result = 31 * result + (context != null ? context.hashCode() : 0);
        result = 31 * result + (project != null ? project.hashCode() : 0);
        result = 31 * result + (payload != null ? payload.hashCode() : 0);
        return result;
    }
}
