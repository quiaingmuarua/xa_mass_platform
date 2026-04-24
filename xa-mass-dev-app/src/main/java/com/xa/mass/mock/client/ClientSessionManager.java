package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientSessionManager {
    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    private final Map<String, MockWorkerClient> clients = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public void addClient(MockWorkerClient client) {
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
        MockWorkerClient client = clients.get(workerId);

        if (client != null && client.isConnected()) {
            MassMessage taskMessage = createMockTaskMessage(workerId);
            try {
                client.sendMessage(new Gson().toJson(taskMessage));
                log.info("Sent mock task to client: {}", workerId);
            } catch (Exception e) {
                log.warn("Failed to send mock task to client {}", workerId, e);
            }
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

        JsonObject payload = new JsonObject();
        JsonArray steps = new JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("stepId", "step_" + System.currentTimeMillis());
        steps.add(step);
        payload.add("steps", steps);
        message.setPayload(new Gson().toJsonTree(payload));

        return message;
    }

    public Collection<MockWorkerClient> getAllClients() {
        return clients.values();
    }

    public MockWorkerClient getClient(String workerId) {
        return clients.get(workerId);
    }

    public int getClientCount() {
        return clients.size();
    }
}
