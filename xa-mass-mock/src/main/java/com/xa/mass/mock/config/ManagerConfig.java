package com.xa.mass.mock.config;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.storage.RuleStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.storage.WorkerStorage;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Core manager wiring used by the mock Spring Boot shell.
 */
@Configuration
public class ManagerConfig {

    @Bean
    public TaskStorage taskStorage() {
        return TaskStorageFactory.createDefaultTaskStorage();
    }

    @Bean
    public WorkerStorage workerStorage() {
        return TaskStorageFactory.createDefaultWorkerStorage();
    }

    @Bean
    public RuleStorage ruleStorage() {
        return TaskStorageFactory.createDefaultRuleStorage();
    }

    @Bean
    public TaskScheduler taskScheduler() {
        return new SimpleTaskScheduler();
    }

    @Bean
    public TaskManager taskManager(TaskScheduler taskScheduler, TaskStorage taskStorage) {
        return new TaskManager(taskScheduler, taskStorage);
    }

    @Bean
    public WorkerManager workerManager(WorkerStorage workerStorage) {
        return new WorkerManager(workerStorage);
    }

    @Bean
    public RuleManager<Map<String, Object>> ruleManager(RuleStorage ruleStorage) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage);
        // Keep the default baseline rules available in the mock runtime.
        manager.addDefaultRules(RuleManagerFactory.getDefaultRuleManager().getDefaultRules());
        return manager;
    }
}
