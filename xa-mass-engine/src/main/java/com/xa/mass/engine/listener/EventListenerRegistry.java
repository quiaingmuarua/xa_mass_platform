package com.xa.mass.engine.listener;

import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.base.eventbus.device.DeviceOfflineEvent;
import com.xa.mass.base.eventbus.device.DeviceOnlineEvent;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.service.AssignmentService;
import com.xa.mass.engine.service.AuditService;
import com.xa.mass.engine.service.PipelineService;
import com.xa.mass.engine.service.TaskAssignWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件监听注册中心，集中管理所有事件监听器的注册
 */
public class EventListenerRegistry {
    private static final Logger log = LoggerFactory.getLogger(EventListenerRegistry.class);

    private EventListenerRegistry() {}

    public static void registerAll(EventBusFacade eventBus, DeviceManager deviceManager, TaskAssignWorkerService assignWorkerService, AuditService auditService, AssignmentService assignmentService, PipelineService pipelineService) {
        log.info("registerAll: register all event listeners ...");
        DeviceManager.DeviceStatusEventListener listener = new DeviceManager.DeviceStatusEventListener(deviceManager);
        eventBus.register(DeviceOnlineEvent.class, listener::onDeviceOnline);
        eventBus.register(DeviceOfflineEvent.class, listener::onDeviceOffline);
    }
    // 后续可扩展注册更多监听器
} 