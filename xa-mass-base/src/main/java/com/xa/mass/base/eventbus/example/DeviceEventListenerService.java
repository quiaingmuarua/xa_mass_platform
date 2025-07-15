package com.xa.mass.base.eventbus.example;

import com.xa.mass.base.eventbus.core.*;
import com.xa.mass.base.eventbus.device.DeviceOfflineEvent;
import com.xa.mass.base.eventbus.device.DeviceOnlineEvent;

public class DeviceEventListenerService implements Runnable {
    private final EventBusFacade eventBus;

    public DeviceEventListenerService(EventBusFacade eventBus) {
        this.eventBus = eventBus;
    }

    @MassSubscribe
    public void onDeviceOffline(DeviceOfflineEvent event) {
        System.out.println("[Service] 收到设备下线事件: " + event + ", 线程: " + Thread.currentThread().getName());
        // 这里可以做业务处理
    }

    @MassSubscribe
    public void onDeviceOnline(DeviceOnlineEvent event) {
        System.out.println("[Service] 收到设备上线事件: " + event + ", 线程: " + Thread.currentThread().getName());
    }

    @Override
    public void run() {
        eventBus.register(this);
        System.out.println("[Service] 事件监听服务已启动，线程: " + Thread.currentThread().getName());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000); // 保持线程活跃
            }
        } catch (InterruptedException e) {
            // 线程被中断，退出
        } finally {
            eventBus.unregister(this);
            System.out.println("[Service] 事件监听服务已关闭");
        }
    }
} 