package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.WORKER_FAILURE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.Objects;

final class WebSocketBoundWorkerHandler
        extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final System.Logger LOGGER = System.getLogger(
            WebSocketBoundWorkerHandler.class.getName()
    );

    private final WebSocketWorkerRouteDirectory routes;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final String workerId;

    WebSocketBoundWorkerHandler(
            WebSocketWorkerRouteDirectory routes,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            String workerId
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        this.workerId = workerId;
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            WebSocketFrame frame
    ) {
        if (frame instanceof BinaryWebSocketFrame) {
            routes.close(
                    workerId,
                    context.channel(),
                    WebSocketCloseReason.BINARY_UNSUPPORTED
            );
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) {
            return;
        }

        DeliveryReport report = codec.decodeDeliveryReport(text.text());
        if (report == null) {
            logDrop("dropMalformedWorkerResult", null);
            return;
        }
        if (report.src() != WORKER
                || !workerId.equals(report.sourceId())) {
            logDrop("dropWorkerSourceMismatch", report);
            return;
        }
        if (report.dst() == ADAPTER) {
            if (WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                    report.messageType()
            )) {
                logDrop("dropRepeatedIdentity", report);
            } else {
                logDrop("dropUnknownWorkerEvent", report);
            }
            return;
        }
        if (report.dst() != TASK) {
            logDrop("dropUnsupportedDestination", report);
            return;
        }

        var outcome = classifyDeliveryReportOutcomeCode(report.outcomeCode());
        if (outcome != SUCCESS && outcome != WORKER_FAILURE) {
            logDrop("dropWorkerOutcome", report);
            return;
        }
        switch (reportQueue.offer(text.text())) {
            case ACCEPTED -> {
            }
            case FULL -> routes.close(
                    workerId,
                    context.channel(),
                    WebSocketCloseReason.RESULT_BUFFER_FULL
            );
            case CLOSED -> routes.close(
                    workerId,
                    context.channel(),
                    WebSocketCloseReason.ADAPTER_STOPPING
            );
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        routes.deactivate(workerId, context.channel());
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        routes.close(
                workerId,
                context.channel(),
                WebSocketCloseReason.TRANSPORT_ERROR
        );
    }

    private void logDrop(String action, DeliveryReport report) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} phase=BOUND messageType={2}",
                WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID.code(),
                "websocket." + action,
                report == null ? "<malformed>" : report.messageType()
        );
    }
}
