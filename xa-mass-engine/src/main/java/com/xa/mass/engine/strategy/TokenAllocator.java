package com.xa.mass.engine.strategy;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;

import java.util.List;
import java.util.Map;

/**
 * Token分配器接口
 * 为设备分配可用的Token
 */
public interface TokenAllocator {
    
    /**
     * 为指定设备分配Token
     * 
     * @param device 设备
     * @param task 任务
     * @param availableTokens 该设备可用的Token列表
     * @return 分配的Token，如果没有可用Token则返回null
     */
    Token allocateToken(Device device, Task task, List<Token> availableTokens);
    
    /**
     * 批量分配Token
     * 
     * @param deviceTokenMap 设备到可用Token列表的映射
     * @param task 任务
     * @return 设备到分配Token的映射
     */
    Map<Device, Token> allocateTokens(Map<Device, List<Token>> deviceTokenMap, Task task);
    
    /**
     * 检查Token是否适合分配给指定任务
     * 
     * @param token Token
     * @param task 任务
     * @return 是否适合
     */
    boolean isTokenSuitable(Token token, Task task);
    
    /**
     * 获取Token优先级分数（用于排序）
     * 
     * @param token Token
     * @param task 任务
     * @return 优先级分数，分数越高优先级越高
     */
    double getTokenPriority(Token token, Task task);
    
    /**
     * 释放Token
     * 
     * @param token 要释放的Token
     * @return 是否成功释放
     */
    boolean releaseToken(Token token);
} 