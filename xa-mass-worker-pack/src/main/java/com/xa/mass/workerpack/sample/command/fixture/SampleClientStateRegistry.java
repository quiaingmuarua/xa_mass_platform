package com.xa.mass.workerpack.sample.command.fixture;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SampleClientStateRegistry {

    private final Map<String, SampleClientState> states = new ConcurrentHashMap<>();

    public SampleClientState getOrCreate(String workerId) {
        return states.computeIfAbsent(workerId, ignored -> new SampleClientState());
    }

    public Map<String, Object> snapshot(String workerId) {
        return getOrCreate(workerId).snapshot();
    }

    public void reset(String workerId) {
        getOrCreate(workerId).reset();
    }
}

