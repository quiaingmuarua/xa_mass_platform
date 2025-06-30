//package com.xa.mass.api.config;
//
//import com.xa.mass.engine.DeviceManager;
//import com.xa.mass.engine.TaskManager;
//import com.xa.mass.engine.rules.RuleManager;
//import com.xa.mass.engine.strategy.SimpleTaskScheduler;
//import com.xa.mass.engine.strategy.TaskScheduler;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import java.util.Map;
//
//@Configuration
//public class MassManagerConfig {
//
//    @Bean
//    public TaskScheduler taskScheduler() {
//        return new SimpleTaskScheduler();
//    }
//
//    @Bean
//    public TaskManager taskManager(TaskScheduler taskScheduler) {
//        return new TaskManager(taskScheduler);
//    }
//
//    @Bean
//    public DeviceManager deviceManager() {
//        return new DeviceManager();
//    }
//
//    @Bean
//    public RuleManager<Map<String, Object>> ruleManager() {
//        return new RuleManager<>();
//    }
//}