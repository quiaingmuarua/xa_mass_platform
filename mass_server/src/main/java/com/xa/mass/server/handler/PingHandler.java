package com.xa.mass.server.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.server.manager.OutboundQueueManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import com.xa.mass.server.handler.OutboundMessage;

public class PingHandler implements MessageHandler {

    private static final Gson gson = new Gson();

    @Override
    public void handle(JsonObject data, Channel channel) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "pong");
        response.addProperty("timestamp", System.currentTimeMillis());

        String responseText = gson.toJson(response);
        OutboundQueueManager.getInstance().enqueue(
            new OutboundMessage(
                "111",
                responseText,
                channel,
                null,
                null,
                null
            )
        );
    }
}
