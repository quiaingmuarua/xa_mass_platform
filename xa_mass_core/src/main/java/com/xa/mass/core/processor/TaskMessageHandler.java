package com.xa.mass.core.processor;

import com.google.gson.Gson;
import com.xa.mass.core.model.message.BaseMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一处理客户端回传的任务结果（msgType = response
 */
@Component
public class TaskMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaskMessageHandler.class);
    private static final Gson gson = new Gson();

    public static void onClientResponse(String json) {
        try {
            // 反序列化BaseMessage 并强result 部分（注payload 可忽略）
            BaseMessage<?> baseMsg = gson.fromJson(json, BaseMessage.class);

            if (baseMsg.getMsgType() != MessageType.RESPONSE) {
                logger.warn("Received unexpected msgType in TaskMessageHandler: {}", baseMsg.getMsgType());
                return;
            }

            MessageContext ctx = baseMsg.getContext();
            MessageResult result = gson.fromJson(gson.toJson(baseMsg.getResult()), MessageResult.class);

            String deviceId = ctx != null ? ctx.getDeviceId() : "unknown";
            String connRole = ctx != null ? ctx.getConnRole() : "unknown";

            logger.info("Received response from device [{}:{}], msgId={}, code={}, message={}",
                    deviceId, connRole, baseMsg.getMsgId(), result.getCode(), result.getMessage());

            // 根据 subMsgType 进行细粒度处理（可选扩展）
            switch (baseMsg.getSubMsgType()) {
                case "step":
                    handleStepResponse(baseMsg, result);
                    break;
                case "all":
                    handleAllResponse(baseMsg, result);
                    break;
                default:
                    logger.warn("Unknown subMsgType: {}", baseMsg.getSubMsgType());
            }

        } catch (Exception e) {
            logger.error("Failed to process client response", e);
        }
    }

    private static void handleStepResponse(BaseMessage<?> msg, MessageResult result) {
        logger.debug("Step-step result for msgId={}: {}", msg.getMsgId(), gson.toJson(result));
    }

    private static void handleAllResponse(BaseMessage<?> msg, MessageResult result) {
        logger.debug("All-step result for msgId={}: {}", msg.getMsgId(), gson.toJson(result));
        // TODO: 处理整体任务完成 —可触发业务逻辑、状态流、标记成功等
    }
}
