package com.xa.mass.starter;

import com.google.gson.Gson;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.session.SessionRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GatewayTaskMsgPublisher implements TaskMsgDispatchListener {

    public static final String DEFAULT_CONN_ROLE = SessionRoles.TASK_MESSAGES;

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskMsgPublisher.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final Gson gson = new Gson();

    public GatewayTaskMsgPublisher(DispatchRuntimeContext dispatchRuntimeContext) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getMessageCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }

        for (TaskMsg taskMsg : taskMsgs) {
            MassMessage message = buildMessage(task, taskMsg);
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

    private MassMessage buildMessage(Task task, TaskMsg taskMsg) {
        MassMessage message = new MassMessage();
        message.setMsgId(taskMsg.getMsgId());
        message.setMsgType(MessageType.TASK);
        message.setSubMsgType("step");
        message.setFrom(MessageDirection.SERVER);
        message.setProject(task.getProject());
        message.setContext(buildContext(task, taskMsg));
        message.setPayload(gson.toJsonTree(buildPayload(task, taskMsg)));
        return message;
    }

    private MessageContext buildContext(Task task, TaskMsg taskMsg) {
        MessageContext context = new MessageContext();
        context.setWorkerId(taskMsg.getLatestAttemptWorkerId());
        context.setConnRole(DEFAULT_CONN_ROLE);
        context.setTid(task.getTid());
        context.setRetryCount(taskMsg.getRetryCount());
        return context;
    }

    private TaskPayload buildPayload(Task task, TaskMsg taskMsg) {
        TaskStep step = new TaskStep();
        step.setStepId(taskMsg.getLatestAttemptBatchId() != null ? taskMsg.getLatestAttemptBatchId() : taskMsg.getMsgId());
        step.setAction("task-dispatch");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", task.getTid());
        params.put("taskName", task.getTaskName());
        if (taskMsg.getInput() != null) {
            params.putAll(taskMsg.getInput());
        }
        params.put("workerId", taskMsg.getLatestAttemptWorkerId());
        params.put("workerContextId", taskMsg.getLatestAttemptWorkerContextId());
        params.put("batchId", taskMsg.getLatestAttemptBatchId());
        if (task.getSharedConfig() != null) {
            params.putAll(task.getSharedConfig());
        }
        step.setParams(params);

        TaskPayload payload = new TaskPayload();
        payload.setSteps(List.of(step));
        return payload;
    }
}
