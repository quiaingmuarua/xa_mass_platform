package com.xa.mass.base.old.eventbus;

import com.xa.mass.base.channel.eventbus.core.HandlerWrapper;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;

import com.xa.mass.base.channel.eventbus.core.MassEventDispatcher;
import com.xa.mass.base.old.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.old.eventbus.event.device.DeviceOnlineEvent;
import com.xa.mass.base.old.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.model.Task;
/**
 * 测试优化后的RedisStreamEventBusFacade性能和功能
 */
public class OptimizedRedisEventBusTest {

    public static void main(String[] args) {
        OptimizedRedisEventBusTest test = new OptimizedRedisEventBusTest();
        test.testMassEventDispatcher();
        test.testHandlerWrapperPerformance();
    }

    /**
     * 测试事件分发器的基本功能
     */
    public void testMassEventDispatcher() {
        MassEventDispatcher dispatcher = new MassEventDispatcher();
        TestEventListener listener = new TestEventListener();
        
        // 注册监听器
        dispatcher.registerListener(listener);
        System.out.println("注册监听器后，处理器总数: " + dispatcher.getTotalHandlerCount());
        
        // 测试事件分发
        DeviceOfflineEvent deviceEvent = new DeviceOfflineEvent("device-001", "网络异常", "trace-123");
        Task task = new Task();
        task.setTid("task-001");
        TaskCreatedEvent taskEvent = new TaskCreatedEvent(task, "trace-456", "req-789");
        
        System.out.println("\n=== 开始事件分发测试 ===");
        long startTime = System.nanoTime();
        
        // 分发多个事件
        for (int i = 0; i < 1000; i++) {
            dispatcher.dispatch(deviceEvent);
            dispatcher.dispatch(taskEvent);
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0; // 转换为毫秒
        
        System.out.println("分发2000个事件耗时: " + duration + " ms");
        System.out.println("平均每个事件耗时: " + (duration / 2000) + " ms");
        
        // 注销监听器
        dispatcher.unregisterListener(listener);
        System.out.println("\n注销监听器后，处理器总数: " + dispatcher.getTotalHandlerCount());
    }

    /**
     * 测试HandlerWrapper的性能
     */
    public void testHandlerWrapperPerformance() {
        TestEventListener listener = new TestEventListener();
        
        try {
            // 创建HandlerWrapper
            HandlerWrapper wrapper = new HandlerWrapper(
                listener, 
                listener.getClass().getDeclaredMethod("onDeviceOffline", DeviceOfflineEvent.class),
                DeviceOfflineEvent.class
            );
            
            DeviceOfflineEvent event = new DeviceOfflineEvent("device-test", "测试", "trace-test");
            
            System.out.println("=== HandlerWrapper性能测试 ===");
            long startTime = System.nanoTime();
            
            // 调用1万次
            for (int i = 0; i < 10000; i++) {
                try {
                    wrapper.invoke(event);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
            
            long endTime = System.nanoTime();
            double duration = (endTime - startTime) / 1_000_000.0;
            
            System.out.println("调用1万次HandlerWrapper耗时: " + duration + " ms");
            System.out.println("平均每次调用耗时: " + (duration / 10000) + " ms");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试事件监听器
     */
    static class TestEventListener {
        private int deviceEventCount = 0;
        private int taskEventCount = 0;

        @MassSubscribe
        public void onDeviceOffline(DeviceOfflineEvent event) {
            deviceEventCount++;
            if (deviceEventCount <= 5) {
                System.out.println("处理设备下线事件: " + event.getDeviceId() + ", 原因: " + event.getReason());
            }
        }

        @MassSubscribe  
        public void onDeviceOnline(DeviceOnlineEvent event) {
            deviceEventCount++;
            if (deviceEventCount <= 5) {
                System.out.println("处理设备上线事件: " + event.getDeviceId());
            }
        }

        @MassSubscribe
        public void onTaskCreated(TaskCreatedEvent event) {
            taskEventCount++;
            if (taskEventCount <= 5) {
                System.out.println("处理任务创建事件: " + event.getTask().getTid());
            }
        }

        public int getDeviceEventCount() {
            return deviceEventCount;
        }

        public int getTaskEventCount() {
            return taskEventCount;
        }
    }
} 