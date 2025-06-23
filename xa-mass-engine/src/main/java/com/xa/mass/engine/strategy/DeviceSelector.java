package com.xa.mass.core.engine.strategy;

import com.xa.mass.core.engine.model.Device;
import com.xa.mass.core.engine.model.task.Task;

import java.util.List;

/**
 * 设备选择器接口
 * 按project/国家/能力/网络等过滤设备
 */
public interface DeviceSelector {
    
    /**
     * 根据任务需求选择合适的设备
     * 
     * @param task 任务信息
     * @param availableDevices 可用设备列表
     * @param requiredCount 需要的设备数量
     * @return 选中的设备列表
     */
    List<Device> selectDevices(Task task, List<Device> availableDevices, int requiredCount);
    
    /**
     * 检查设备是否满足任务要求
     * 
     * @param device 设备
     * @param task 任务
     * @return 是否满足要求
     */
    boolean isDeviceSuitable(Device device, Task task);
    
    /**
     * 获取设备优先级分数（用于排序）
     * 
     * @param device 设备
     * @param task 任务
     * @return 优先级分数，分数越高优先级越高
     */
    double getDevicePriority(Device device, Task task);
} 