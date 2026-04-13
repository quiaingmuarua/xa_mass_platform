package com.xa.mass.engine.v2.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 引擎统一注册表
 * 提供默认的 TaskService 和 DeviceService，同时支持自定义注册
 */
public class EngineRegistry {
    private static final ConcurrentMap<String, TaskService> taskServiceRegistry = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, DeviceService> deviceServiceRegistry = new ConcurrentHashMap<>();
    
    // 默认服务实例
    private static volatile TaskService defaultTaskService;
    private static volatile DeviceService defaultDeviceService;

    // 获取默认 TaskService
    public static TaskService getTaskService() {
        return defaultTaskService;
    }

    // 获取默认 DeviceService  
    public static DeviceService getDeviceService() {
        return defaultDeviceService;
    }

    // 设置默认 TaskService
    public static void setDefaultTaskService(TaskService service) {
        defaultTaskService = service;
    }

    // 设置默认 DeviceService
    public static void setDefaultDeviceService(DeviceService service) {
        defaultDeviceService = service;
    }

    // 注册自定义 TaskService
    public static void registerTaskService(String name, TaskService service) {
        taskServiceRegistry.put(name, service);
    }

    // 注册自定义 DeviceService
    public static void registerDeviceService(String name, DeviceService service) {
        deviceServiceRegistry.put(name, service);
    }

    // 获取自定义 TaskService
    public static TaskService getTaskService(String name) {
        return taskServiceRegistry.get(name);
    }

    // 获取自定义 DeviceService
    public static DeviceService getDeviceService(String name) {
        return deviceServiceRegistry.get(name);
    }

    // 获取所有已注册的自定义服务名称
    public static java.util.Set<String> getRegisteredTaskServiceNames() {
        return new java.util.HashSet<>(taskServiceRegistry.keySet());
    }

    public static java.util.Set<String> getRegisteredDeviceServiceNames() {
        return new java.util.HashSet<>(deviceServiceRegistry.keySet());
    }

    // 移除自定义注册
    public static void unregisterTaskService(String name) {
        taskServiceRegistry.remove(name);
    }

    public static void unregisterDeviceService(String name) {
        deviceServiceRegistry.remove(name);
    }

    // 检查是否有默认服务
    public static boolean hasDefaultTaskService() {
        return defaultTaskService != null;
    }

    public static boolean hasDefaultDeviceService() {
        return defaultDeviceService != null;
    }
    
    // 清除默认服务（主要用于测试）
    public static void clearDefaultServices() {
        defaultTaskService = null;
        defaultDeviceService = null;
    }
    
    // 清除所有注册的服务（主要用于测试）
    public static void clearAllServices() {
        taskServiceRegistry.clear();
        deviceServiceRegistry.clear();
        defaultTaskService = null;
        defaultDeviceService = null;
    }
} 