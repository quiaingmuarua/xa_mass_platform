package com.xa.mass.gateway.dispatcher.middleware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiddlewareRegistryTest {

    @Test
    void registryKeepsFixedMainlineMiddlewareShape() {
        MiddlewareRegistry registry = MiddlewareRegistry.instance;

        assertEquals(1, registry.getInputMiddlewares().size());
        assertEquals(1, registry.getOutputMiddlewares().size());
        assertEquals(1, registry.getExceptionMiddlewareList().size());
    }
}
