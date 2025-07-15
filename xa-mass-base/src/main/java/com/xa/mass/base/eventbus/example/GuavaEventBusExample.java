package com.xa.mass.base.eventbus.example;

import com.google.common.eventbus.Subscribe;

import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.base.eventbus.core.EventBusFactory;
import com.xa.mass.base.eventbus.device.DeviceOfflineEvent;
import com.xa.mass.base.eventbus.device.DeviceOnlineEvent;
import com.xa.mass.base.eventbus.task.TaskAssignedEvent;
import com.xa.mass.base.model.Task;

import java.util.concurrent.CountDownLatch;

public class GuavaEventBusExample {
    // 监听器，带@Subscribe注解
    static class MultiEventListener {
        private final CountDownLatch latch;
        public MultiEventListener(CountDownLatch latch) {
            this.latch = latch;
        }
        @Subscribe
        public void onDeviceOffline(DeviceOfflineEvent event) {
            System.out.println("[Listener] 收到设备下线事件: " + event + ", 线程: " + Thread.currentThread().getName());
            latch.countDown();
        }
        @Subscribe
        public void onDeviceOnline(DeviceOnlineEvent event) {
            System.out.println("[Listener] 收到设备上线事件: " + event + ", 线程: " + Thread.currentThread().getName());
            latch.countDown();
        }
        @Subscribe
        public void onTaskAssigned(TaskAssignedEvent event) {
            System.out.println("[Listener] 收到任务分配事件: " + event + ", 线程: " + Thread.currentThread().getName());
            latch.countDown();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 1. 获取EventBus实例
        EventBusFacade eventBus = EventBusFactory.get("guava");
        // 2. 注册监听器
        CountDownLatch latch = new CountDownLatch(3);
        MultiEventListener listener = new MultiEventListener(latch);
        eventBus.register(listener);

        // 3. 发布多种事件（主线程）
        System.out.println("[Main] 发布事件，线程: " + Thread.currentThread().getName());
        eventBus.post(new DeviceOfflineEvent("device-001", "网络异常", "trace-123"));
        eventBus.post(new DeviceOnlineEvent("device-002", "恢复上线", "trace-456"));
        Task task = new Task();
        task.setTid("task-001");
        eventBus.post(new TaskAssignedEvent(task, "trace-789", "req-001"));

        // 4. 等待所有异步事件处理完成
        latch.await();
        System.out.println("[Main] 所有事件处理完成");
        eventBus.unregister(listener);
        eventBus.shutdown();
    }
}
