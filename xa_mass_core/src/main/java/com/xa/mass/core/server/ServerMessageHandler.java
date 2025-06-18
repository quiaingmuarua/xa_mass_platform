package com.xa.mass.core.server;

import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.queue.MessageContextValidator;
import com.xa.mass.core.queue.MessageDecoder;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.queue.Envelope;
import com.xa.mass.core.session.ServerSessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class ServerMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageHandler.class);

    @Autowired
    private ServerSessionManager sessionManager;

    @Autowired
    private MessageDecoder messageDecoder;

    @Autowired
    private MessageContextValidator contextValidator;

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<Envelope> inputQueue;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();

        if (raw == null || !raw.trim().startsWith("{")) {
            logger.warn("Dropped invalid message from {}: {}", ctx.channel().remoteAddress(), raw);
            return;
        }

        MassMessage<?> parsed = messageDecoder.tryDecode(raw);
        if (!contextValidator.isValid(parsed)) {
            logger.warn("Message missing context info from {}: {}", ctx.channel().remoteAddress(), raw);
            return;
        }

        MessageContext context = parsed.getContext();
        sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel(), ctx);

        Envelope sm = messageDecoder.toStoredMessage(raw, parsed);
        inputQueue.offer(sm);
        logger.debug("Offered to inputQueue: {}", sm);

    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        sessionManager.removeSession(ctx.channel());
        logger.info("Connection closed, session removed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("New connection: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error on channel {}:", ctx.channel().remoteAddress(), cause);
        sessionManager.removeSession(ctx.channel());
        ctx.close();
    }
}

