package com.xa.mass.mock.config;

import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.storage.WorkerStorage;
import com.xa.mass.engine.storage.RuleStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 状态展示相关配置
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
        // 添加默认规则
        manager.addDefaultRules(RuleManagerFactory.getDefaultRuleManager().getDefaultRules());
        return manager;
    }
} 