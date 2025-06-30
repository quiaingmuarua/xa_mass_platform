package com.xa.mass.api.config;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 状态展示相关配置
 */
@Configuration
public class StatusConfig {

    @Bean
    public TaskStorage taskStorage() {
        return TaskStorageFactory.createDefaultTaskStorage();
    }

    @Bean
    public DeviceStorage deviceStorage() {
        return TaskStorageFactory.createDefaultDeviceStorage();
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
    public DeviceManager deviceManager(DeviceStorage deviceStorage) {
        return new DeviceManager(deviceStorage);
    }
} 