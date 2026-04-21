package com.xa.mass.mock.command.mock;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MockClientStateRegistry {

    private final Map<String, MockClientState> states = new ConcurrentHashMap<>();

    public MockClientState getOrCreate(String workerId) {
        return states.computeIfAbsent(workerId, ignored -> new MockClientState());
    }

    public Map<String, Object> snapshot(String workerId) {
        return getOrCreate(workerId).snapshot();
    }

    public void reset(String workerId) {
        getOrCreate(workerId).reset();
    }
}
