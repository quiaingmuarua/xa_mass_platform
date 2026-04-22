package com.xa.mass.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassApplicationBootstrapCompatibilityTest {

    @Test
    void deprecatedLoadMockDataFailsClearlyWhenNoBootstrapProviderConfigured() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setScheduler(new SimpleTaskScheduler());
        MassApplication app = new MassApplication(new MassEngine(engineConfig), 0, "/", new GatewayConfig(), engineConfig);

        IllegalStateException error = assertThrows(IllegalStateException.class, app::loadMockData);
        assertTrue(error.getMessage().contains("bootstrap data provider"));
    }

    @Test
    void deprecatedLoadMockDataDelegatesToConfiguredBootstrapProvider() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setScheduler(new SimpleTaskScheduler());
        AtomicBoolean invoked = new AtomicBoolean(false);
        engineConfig.setBootstrapDataProvider(runtime -> invoked.set(true));
        MassApplication app = new MassApplication(new MassEngine(engineConfig), 0, "/", new GatewayConfig(), engineConfig);

        app.loadMockData();

        assertTrue(invoked.get());
    }
}
