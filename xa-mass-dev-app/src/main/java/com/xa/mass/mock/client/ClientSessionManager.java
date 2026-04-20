package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientSessionManager {
    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    private final Map<String, MassWebSocketClientImpl> clients = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public void addClient(MassWebSocketClientImpl client) {
        clients.put(client.getWorkerId(), client);
        log.info("Added mock client: {}", client.getWorkerId());
    }

    public void removeClient(String workerId) {
        clients.remove(workerId);
        log.info("Removed mock client: {}", workerId);
    }

    public void sendMockTask() {
        if (clients.isEmpty()) {
            log.warn("No connected clients available to send mock task.");
            return;
        }

        // Pick one connected client at random.
        String workerId = new ArrayList<>(clients.keySet()).get(random.nextInt(clients.size()));
        MassWebSocketClientImpl client = clients.get(workerId);

        if (client != null && client.isOpen()) {
            MassMessage taskMessage = createMockTaskMessage(workerId);
            client.send(new Gson().toJson(taskMessage));
            log.info("Sent mock task to client: {}", workerId);
        }
    }

    private MassMessage createMockTaskMessage(String workerId) {
        MassMessage message = new MassMessage();
        message.setMsgId("task-" + UUID.randomUUID());
        message.setMsgType(MessageType.TASK);
        message.setFrom(MessageDirection.SERVER);
        message.setSubMsgType("mock_task");

        MessageContext ctx = new MessageContext();
        ctx.setWorkerId(workerId);
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        ctx.setTid("mock_task_" + System.currentTimeMillis());
        message.setContext(ctx);

        TaskPayload payload = new TaskPayload();
        List<TaskStep> steps = new ArrayList<>();
        TaskStep step = new TaskStep();
        step.setStepId("step_" + System.currentTimeMillis());
        steps.add(step);
        payload.setSteps(steps);
        message.setPayload(new Gson().toJsonTree(payload));

        return message;
    }

    public Collection<MassWebSocketClientImpl> getAllClients() {
        return clients.values();
    }

    public int getClientCount() {
        return clients.size();
    }
}
