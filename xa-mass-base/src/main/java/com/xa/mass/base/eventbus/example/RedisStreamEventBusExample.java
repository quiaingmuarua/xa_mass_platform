package com.xa.mass.base.eventbus.example;

import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.base.eventbus.core.EventBusFactory;
import com.xa.mass.base.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.eventbus.event.device.DeviceOnlineEvent;
import com.xa.mass.base.tool.RedisConnectionManager;


public class RedisStreamEventBusExample {
    public static void main(String[] args) throws InterruptedException {
        RedisConnectionManager.init("localhost", 6379, null, 0);
        EventBusFacade eventBus = EventBusFactory.get("redis");

        // 启动后台监听服务
        Thread listenerThread = new Thread(new DeviceEventListenerService(eventBus), "DeviceEventListenerService");
        listenerThread.start();

        // 主线程批量发布多类型事件
        System.out.println("[Main] 批量发布多类型事件，线程: " + Thread.currentThread().getName());
        for (int i = 1; i <= 5; i++) {
            eventBus.post(new DeviceOfflineEvent("device-" + i, "网络异常", "trace-redis-" + i));
            eventBus.post(new DeviceOnlineEvent("device-" + i, "恢复上线", "trace-redis-" + i));

        }

        // 让主线程等待一会儿，观察后台服务输出
        Thread.sleep(6000);

        // 关闭服务
        listenerThread.interrupt();
        listenerThread.join();
        eventBus.shutdown();
        RedisConnectionManager.shutdown();
    }
} 