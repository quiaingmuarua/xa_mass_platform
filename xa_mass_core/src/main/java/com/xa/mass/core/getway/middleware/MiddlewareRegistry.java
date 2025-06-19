package com.xa.mass.core.getway.middleware;

import java.util.*;

public class MiddlewareRegistry {
    private final NavigableMap<Integer, EnvelopeMiddleware> inputMiddlewareMap = new TreeMap<>();
    private final NavigableMap<Integer, EnvelopeMiddleware> outputMiddlewareMap = new TreeMap<>();
    private final Map<Integer, Boolean> inputEnabledMap = new HashMap<>();
    private final Map<Integer, Boolean> outputEnabledMap = new HashMap<>();

    public void registerInput(int priority, EnvelopeMiddleware mw) {
        inputMiddlewareMap.put(priority, mw);
        inputEnabledMap.put(priority, true);
    }
    public void unregisterInput(int priority) {
        inputMiddlewareMap.remove(priority);
        inputEnabledMap.remove(priority);
    }
    public void setInputEnabled(int priority, boolean enabled) {
        if (inputMiddlewareMap.containsKey(priority)) {
            inputEnabledMap.put(priority, enabled);
        }
    }
    public List<EnvelopeMiddleware> getActiveInputMiddlewares() {
        List<EnvelopeMiddleware> list = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : inputMiddlewareMap.entrySet()) {
            if (Boolean.TRUE.equals(inputEnabledMap.get(entry.getKey()))) {
                list.add(entry.getValue());
            }
        }
        return list;
    }
    public void registerOutput(int priority, EnvelopeMiddleware mw) {
        outputMiddlewareMap.put(priority, mw);
        outputEnabledMap.put(priority, true);
    }
    public void unregisterOutput(int priority) {
        outputMiddlewareMap.remove(priority);
        outputEnabledMap.remove(priority);
    }
    public void setOutputEnabled(int priority, boolean enabled) {
        if (outputMiddlewareMap.containsKey(priority)) {
            outputEnabledMap.put(priority, enabled);
        }
    }
    public List<EnvelopeMiddleware> getActiveOutputMiddlewares() {
        List<EnvelopeMiddleware> list = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : outputMiddlewareMap.entrySet()) {
            if (Boolean.TRUE.equals(outputEnabledMap.get(entry.getKey()))) {
                list.add(entry.getValue());
            }
        }
        return list;
    }
    public NavigableMap<Integer, EnvelopeMiddleware> getInputMiddlewareMap() { return inputMiddlewareMap; }
    public NavigableMap<Integer, EnvelopeMiddleware> getOutputMiddlewareMap() { return outputMiddlewareMap; }
    public Map<Integer, Boolean> getInputEnabledMap() { return inputEnabledMap; }
    public Map<Integer, Boolean> getOutputEnabledMap() { return outputEnabledMap; }
} 