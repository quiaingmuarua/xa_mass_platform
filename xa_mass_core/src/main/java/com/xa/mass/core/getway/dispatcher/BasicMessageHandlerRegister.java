package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import org.springframework.stereotype.Component;
import com.google.gson.Gson;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;

/**
 * 自动注册基础的 PING、PONG、TASK 处理器
 */
@Component
public class BasicMessageHandlerRegister {

    private final Gson gson = new Gson();

    @PostConstruct
    public void init() {
        // PING → 生成 PONG 响应
        MessageHandlerRegistry.register(MessageType.PING, "", this::handlePing);

        // PONG → 简单日志，由业务决定是否需要后续动作
        MessageHandlerRegistry.register(MessageType.PONG, "", this::handlePong);

        // TASK → 处理任务请求（示例：直接返回 ACK 结果）
        MessageHandlerRegistry.register(MessageType.TASK, "", this::handleTask);
    }

    private List<MassMessage> handlePing(MassMessage msg) {
        // 构建一个 PONG 消息
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

    private List<MassMessage> handlePong(MassMessage msg) {
        // 客户端回应的 pong，可用于更新心跳状态
        // 这里只做简单日志，返回空列表
        System.out.println("Received PONG from " +
                msg.getContext().getDeviceId() + "/" + msg.getContext().getConnRole());
        return Collections.emptyList();
    }

    private List<MassMessage> handleTask(MassMessage msg) {
        // 示例：收到 TASK，可将业务分发给 TaskManager
        // 这里只返回一个 ACK
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
