package com.xa.mass.starter.builder;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.starter.MassGateway;
import com.xa.mass.starter.config.GatewayConfig;

/**
 * Builder for {@link MassGateway}.
 */
public class MassGatewayBuilder {
    private GatewayConfig config = new GatewayConfig();
    private DispatchRuntimeContext dispatcherContext;

    private MassGatewayBuilder() {
    }

    public static MassGatewayBuilder create() {
        return new MassGatewayBuilder();
    }

    public MassGatewayBuilder config(GatewayConfig config) {
        this.config = config;
        return this;
    }

    public MassGatewayBuilder dispatcherContext(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
        return this;
    }

    public MassGatewayBuilder maxConnections(int maxConnections) {
        this.config.setMaxConnections(maxConnections);
        return this;
    }

    public MassGatewayBuilder enabled(boolean enabled) {
        this.config.setEnabled(enabled);
        return this;
    }

    // Additional fluent configuration methods can be added here as needed.

    public MassGateway build() {
        return new MassGateway(config, dispatcherContext);
    }
}
