package com.xa.mass.core.session;


import com.google.gson.Gson;
import com.xa.mass.core.client.MassWebSocketClientImpl;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.model.message.TaskStep;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.model.message.payload.TaskPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientSessionManager {
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
            MassMessage<TaskPayload> taskMessage = createMockTaskMessage(deviceId);
            client.send(new Gson().toJson(taskMessage));
            log.info("📤 Sent mock task to client: {}", deviceId);
        }
    }

    private MassMessage<TaskPayload> createMockTaskMessage(String deviceId) {
        MassMessage<TaskPayload> message = new MassMessage<>();
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
        ;
        steps.add(step);
        payload.setSteps(steps);
        message.setPayload(payload);

        return message;
    }

    public Collection<MassWebSocketClientImpl> getAllClients() {
        return clients.values();
    }

    public int getClientCount() {
        return clients.size();
    }
} 
