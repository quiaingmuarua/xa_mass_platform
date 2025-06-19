package com.xa.mass.core.getway.middleware;

import java.util.ArrayList;
import java.util.List;

public class MiddlewareRegistry {
    private static final List<MessageMiddleware> inputMiddlewares = new ArrayList<>();
    private static final List<OutputMessageMiddleware> outputMiddlewares = new ArrayList<>();

    public static void registerInput(MessageMiddleware middleware) {
        inputMiddlewares.add(middleware);
    }

    public static void registerOutput(OutputMessageMiddleware middleware) {
        outputMiddlewares.add(middleware);
    }

    public static List<MessageMiddleware> getInputMiddlewares() {
        return inputMiddlewares;
    }

    public static List<OutputMessageMiddleware> getOutputMiddlewares() {
        return outputMiddlewares;
    }
}
