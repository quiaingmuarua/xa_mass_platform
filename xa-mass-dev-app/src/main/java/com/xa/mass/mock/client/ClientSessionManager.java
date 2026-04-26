package com.xa.mass.mock.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientSessionManager {
    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    private final Map<String, MockWorkerClient> clients = new ConcurrentHashMap<>();

    public void addClient(MockWorkerClient client) {
        clients.put(client.getWorkerId(), client);
        log.info("Added mock client: {} ({})", client.getWorkerId(), client.adapterId());
    }

    public void removeClient(String workerId) {
        MockWorkerClient removed = clients.remove(workerId);
        if (removed == null) {
            log.info("Removed mock client: {}", workerId);
            return;
        }
        log.info("Removed mock client: {} ({})", removed.getWorkerId(), removed.adapterId());
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
