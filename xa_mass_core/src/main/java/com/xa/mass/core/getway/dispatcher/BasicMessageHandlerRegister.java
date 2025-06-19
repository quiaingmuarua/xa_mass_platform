package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;

/**
 * 自动注册基础的 PING、PONG、TASK 处理器
 */
public class BasicMessageHandlerRegister {
    private static final Gson gson = new Gson();

    public static void registerBasicHandlers() {
        MessageHandlerRegistry.register(MessageType.PING, "", BasicMessageHandlerRegister::handlePing);
        MessageHandlerRegistry.register(MessageType.PONG, "", BasicMessageHandlerRegister::handlePong);
        MessageHandlerRegistry.register(MessageType.TASK, "", BasicMessageHandlerRegister::handleTask);
    }

    private static List<MassMessage> handlePing(MassMessage msg) {
        MassMessage pong = new MassMessage();
        pong.setMsgId(msg.getMsgId());
        pong.setResponse(true);
        pong.setMsgType(MessageType.PONG);
        pong.setSubMsgType("");
        pong.setFrom(MessageDirection.SERVER);
        pong.setContext(msg.getContext());
        pong.setPayload(gson.toJsonTree(new MessageResult(200, "pong")));
        return Collections.singletonList(pong);
    }

    private static List<MassMessage> handlePong(MassMessage msg) {
        System.out.println("Received PONG from " +
                msg.getContext().getDeviceId() + "/" + msg.getContext().getConnRole());
        return Collections.emptyList();
    }

    private static List<MassMessage> handleTask(MassMessage msg) {
        MassMessage ack = new MassMessage();
        ack.setMsgId(msg.getMsgId());
        ack.setResponse(true);
        ack.setMsgType(MessageType.TASK);
        ack.setSubMsgType("");
        ack.setFrom(MessageDirection.SERVER);
        ack.setContext(msg.getContext());
        ack.setPayload(gson.toJsonTree(new MessageResult(200, "task received")));
        return Collections.singletonList(ack);
    }
}
