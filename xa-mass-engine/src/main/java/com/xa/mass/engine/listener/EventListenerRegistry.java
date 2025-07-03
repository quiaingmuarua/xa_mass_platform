package com.xa.mass.engine.listener;

import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.engine.DeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件监听注册中心，只注册设备上下线事件
 */
public class EventListenerRegistry {
    private static final Logger log = LoggerFactory.getLogger(EventListenerRegistry.class);

    private EventListenerRegistry() {
    }

    public static void registerDeviceStatusListeners(EventBusFacade eventBus, DeviceManager deviceManager) {
        log.info("registerDeviceStatusListeners: register device status event listeners ...");
        DeviceManager.DeviceStatusEventListener listener = new DeviceManager.DeviceStatusEventListener(deviceManager);
        eventBus.register(listener);
    }
} 