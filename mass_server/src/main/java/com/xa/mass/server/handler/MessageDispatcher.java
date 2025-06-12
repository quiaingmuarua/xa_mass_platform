package com.xa.mass.server.handler;

import com.google.gson.Gson;
import com.xa.mass.model.message.WsMessage;
import io.netty.channel.Channel;

import java.util.HashMap;
import java.util.Map;

public class MessageDispatcher {

    private static final Map<String, MessageHandler> HANDLERS = new HashMap<>();

    static {
        HANDLERS.put("ping", new PingHandler());

        // 也可以这样动态注册
        // register("newType", (data, ctx) -> { ... });
    }

    public static void dispatch(String jsonText, Channel channel) {
        try {
            WsMessage msg = new Gson().fromJson(jsonText, WsMessage.class);
            String type = msg.getType();

            MessageHandler handler = HANDLERS.get(type);
            if (handler != null) {
                handler.handle(msg.getData(), channel);
            } else {
                System.out.println("Unknown message type: " + type);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void register(String type, MessageHandler handler) {
        HANDLERS.put(type, handler);
    }
}