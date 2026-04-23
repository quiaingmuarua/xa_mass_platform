package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.transport.WorkerEndpointRoles;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

/**
 * WebSocket-backed worker adapter.
 *
 * <p>Bundles the dispatch side (push task messages to connected workers over
 * WebSocket) and the result side (receive task-step callbacks from workers)
 * into a single composable object.
 *
 * <p>To add an HTTP or gRPC adapter, create a parallel class implementing
 * {@link WorkerAdapter} and {@link MassMessageHandler} for that transport.
 */
public class WebSocketWorkerAdapter implements WorkerAdapter, MassMessageHandler, TaskDispatchChannel, TaskResultIngestChannel {

    public static final String PROTOCOL = "websocket";
    public static final String DEFAULT_CONN_ROLE = WorkerEndpointRoles.TASK_DISPATCH;

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWorkerAdapter.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final TaskManager taskManager;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public WebSocketWorkerAdapter(DispatchRuntimeContext dispatchRuntimeContext, TaskManager taskManager) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
        this.taskManager = taskManager;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ws", WorkerTransportHints.REALTIME, "push");
    }

    // Dispatch side.

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        dispatchTaskMessages(task, taskMsgs);
    }

    @Override
    public void dispatchTaskMessages(Task task, List<TaskMsg> taskMsgs) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getMessageCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }

        for (TaskMsg taskMsg : taskMsgs) {
            TaskDispatchItem dispatchItem = TaskDispatchItem.from(task, taskMsg);
            MassMessage message = messageMapper.toDispatchMessage(dispatchItem, DEFAULT_CONN_ROLE);
            String json = dispatchRuntimeContext.getMessageCodec().encode(message);
            Envelope envelope = Envelope.builder()
                    .workerId(taskMsg.getLatestAttemptWorkerId())
                    .connRole(DEFAULT_CONN_ROLE)
                    .project(task.getProject())
                    .traceId(taskMsg.getMsgId())
                    .receivedAt(System.currentTimeMillis())
                    .rawJson(json)
                    .build();
            dispatchRuntimeContext.getMessageTransporter().sendOutput(envelope);
        }
    }

    // Result side.

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        TaskResultReport report;
        try {
            report = messageMapper.toTaskResultReport(msg);
        } catch (IllegalArgumentException ex) {
            return List.of(messageMapper.buildAck(msg, 400, ex.getMessage()));
        }
        boolean handled = ingestTaskResult(
                report.getTaskId(),
                report.getMsgId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
        int code = handled ? 200 : 404;
        String message = handled ? "task result processed" : "task result ignored";
        return List.of(messageMapper.buildAck(msg, code, message));
    }

    @Override
    public boolean ingestTaskResult(
            String taskId,
            String msgId,
            boolean success,
            String detail,
            String errorCode,
            Map<String, Object> output
    ) {
        return taskManager.handleTaskMessageResult(
                taskId,
                msgId,
                success,
                detail,
                errorCode,
                output
        );
    }

}
