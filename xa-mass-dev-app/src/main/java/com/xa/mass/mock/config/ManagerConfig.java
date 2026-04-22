package com.xa.mass.mock.config;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.starter.MassEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.Objects;

/**
 * Exposes runtime-backed manager beans for the dev shell and API controllers.
 */
@Configuration
@Profile("dev")
public class ManagerConfig {

    @Bean
    public TaskManager taskManager(MassSdkApplication app) {
        return requireRuntimeEngine(app).getConfig().getTaskManager();
    }

    @Bean
    public WorkerManager workerManager(MassSdkApplication app) {
        return requireRuntimeEngine(app).getConfig().getWorkerManager();
    }

    @Bean
    public RuleManager<Map<String, Object>> ruleManager(MassSdkApplication app) {
        return requireRuntimeEngine(app).getConfig().getRuleManager();
    }

    @Bean
    public TaskScheduler taskScheduler(MassSdkApplication app) {
        return requireRuntimeEngine(app).getConfig().getScheduler();
    }

    private MassEngine requireRuntimeEngine(MassSdkApplication app) {
        return Objects.requireNonNull(app.getEngine(), "SDK application engine must be available in dev profile");
    }
}
