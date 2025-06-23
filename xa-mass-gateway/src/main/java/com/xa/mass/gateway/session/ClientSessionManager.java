package com.xa.mass.gateway.session;

import com.google.gson.Gson;
import com.xa.mass.gateway.client.MassWebSocketClientImpl;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.payload.TaskPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientSessionManager {
    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);
    
    private final Map<String, MassWebSocketClientImpl> clients = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public void addClient(MassWebSocketClientImpl client) {
        clients.put(client.getDeviceId(), client);
        log.info("Added mock client: {}", client.getDeviceId());
    }

    public void removeClient(String deviceId) {
        clients.remove(deviceId);
        log.info("Removed mock client: {}", deviceId);
    }

    public void sendMockTask() {
        if (clients.isEmpty()) {
            log.warn("No connected clients available to send mock task.");
            return;
        }

        // 随机选择一个客户端
        String deviceId = new ArrayList<>(clients.keySet()).get(random.nextInt(clients.size()));
        MassWebSocketClientImpl client = clients.get(deviceId);

        if (client != null && client.isOpen()) {
            MassMessage taskMessage = createMockTaskMessage(deviceId);
            client.send(new Gson().toJson(taskMessage));
            log.info("📤 Sent mock task to client: {}", deviceId);
        }
    }

    private MassMessage createMockTaskMessage(String deviceId) {
        MassMessage message = new MassMessage();
        message.setMsgId("task-" + UUID.randomUUID().toString());
        message.setMsgType(MessageType.TASK);
        message.setFrom(MessageDirection.SERVER);
        message.setSubMsgType("mock_task");

        MessageContext ctx = new MessageContext();
        ctx.setDeviceId(deviceId);
        ctx.setConnRole("messages_task");
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
