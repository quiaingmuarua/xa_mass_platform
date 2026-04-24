package com.xa.mass.gateway.dispatcher.middleware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiddlewareRegistryTest {

    @Test
    void autoRegisterKeepsFixedMainlineMiddlewareShapeAndResetsDefaultExceptionHandler() {
        MiddlewareRegistry registry = MiddlewareRegistry.instance;
        int baselineExceptionHandlers = registry.getExceptionMiddlewareList().size();

        registry.registerExceptionMiddleware((envelope, context, ex) -> true);
        assertEquals(baselineExceptionHandlers + 1, registry.getExceptionMiddlewareList().size());

        MiddlewareRegistry.autoRegister();

        assertEquals(1, registry.getInputMiddlewares().size());
        assertEquals(1, registry.getOutputMiddlewares().size());
        assertEquals(baselineExceptionHandlers, registry.getExceptionMiddlewareList().size());
    }
}
