package com.xa.mass.core.getway.server;

import com.google.gson.GsonBuilder;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MassServerConfig {
    private final int port;
    private final String websocketPath;
    private final DispatcherContext dispatcherContext;

    public MassServerConfig(
            int port,
            String websocketPath,
            DispatcherContext dispatcherContext
    ) {
        this.port = port;
        this.websocketPath = websocketPath;
        this.dispatcherContext = dispatcherContext;
    }

    public int getPort() { return port; }
    public String getWebsocketPath() { return websocketPath; }
    public DispatcherContext getDispatcherContext() { return dispatcherContext; }

    @Override
    public String toString() {
        return describe();
    }

    public String describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        DispatcherContext ctx = dispatcherContext;
        info.put("port", port);
        info.put("websocketPath", websocketPath);
        info.put("inputQueue", ctx.getInputQueue() != null ? ctx.getInputQueue().getClass().getSimpleName() : null);
        info.put("inputQueueSize", ctx.getInputQueue() != null ? ctx.getInputQueue().size() : null);
        info.put("outputQueue", ctx.getOutputQueue() != null ? ctx.getOutputQueue().getClass().getSimpleName() : null);
        info.put("outputQueueSize", ctx.getOutputQueue() != null ? ctx.getOutputQueue().size() : null);
        // Middleware
        MiddlewareRegistry registry = MiddlewareRegistry.instance;
        Map<String, List<Map<String, Object>>> mwInfo = new LinkedHashMap<>();
        // input
        List<Map<String, Object>> inputList = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : registry.getInputMiddlewareMap().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("priority", entry.getKey());
            m.put("class", entry.getValue().getClass().getSimpleName());
            m.put("enabled", registry.getInputEnabledMap().get(entry.getKey()));
            inputList.add(m);
        }
        mwInfo.put("input", inputList);
        // output
        List<Map<String, Object>> outputList = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : registry.getOutputMiddlewareMap().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("priority", entry.getKey());
            m.put("class", entry.getValue().getClass().getSimpleName());
            m.put("enabled", registry.getOutputEnabledMap().get(entry.getKey()));
            outputList.add(m);
        }
        mwInfo.put("output", outputList);
        info.put("middlewares", mwInfo);
        // SessionManager
        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        sessionInfo.put("class", ctx.getSessionManager().getClass().getSimpleName());
        sessionInfo.put("activeConnections", ctx.getSessionManager().getDeviceConnectionCount());
        info.put("sessionManager", sessionInfo);
        // 输出 JSON
        return new GsonBuilder().setPrettyPrinting().create().toJson(info);
    }
} 