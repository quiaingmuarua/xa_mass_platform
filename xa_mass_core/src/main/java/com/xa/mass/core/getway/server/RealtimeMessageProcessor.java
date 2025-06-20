package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.dispatcher.ServerMessageDispatcher;
import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;

public class RealtimeMessageProcessor implements IncomingMessageProcessor {
    private final ServerMessageDispatcher dispatcher;
    private final Gson gson = new Gson();

    public RealtimeMessageProcessor(ServerMessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void process(String rawJson, ChannelHandlerContext ctx) {
        Envelope envelope = gson.fromJson(rawJson, Envelope.class);
//        dispatcher.dispatch(envelope, ctx);
    }
} 