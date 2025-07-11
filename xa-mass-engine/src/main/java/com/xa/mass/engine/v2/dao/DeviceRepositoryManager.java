package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * 设备仓库管理器
 * 使用 MessageMap 管理设备、令牌和项目映射关系
 * 
 * 数据结构：
 * - projectTokenEntityMap: Map<Project, MessageMap<DeviceId, TokenEntity>> - 项目设备令牌映射
 * - deviceEntityMap: MessageMap<DeviceId, DeviceEntity> - 设备实体映射
 * - tokenEntityMap: MessageMap<DeviceId, TokenEntity> - 设备令牌映射
 */
public class DeviceRepositoryManager {
    
    // 设备ID -> 设备实体
    private final MessageMap<String, DeviceEntity> deviceEntityMap;
    // 设备ID -> Token实体
    private final MessageMap<String, TokenEntity> tokenEntityMap;
    // 项目 -> tokenId集合
    private final ConcurrentMap<String, Set<String>> projectTokenIdMap = new ConcurrentHashMap<>();

    /**
     * 构造函数
     * @param deviceEntityMap 设备实体映射
     * @param tokenEntityMap 设备令牌映射
     */
    public DeviceRepositoryManager(MessageMap<String, DeviceEntity> deviceEntityMap, 
                                 MessageMap<String, TokenEntity> tokenEntityMap) {
        this.deviceEntityMap = deviceEntityMap;
        this.tokenEntityMap = tokenEntityMap;
    }

    /**
     * 添加设备绑定令牌
     * @param tokenEntity 令牌实体
     */
    public void addDeviceBindToken(TokenEntity tokenEntity) {
        Objects.requireNonNull(tokenEntity, "Token entity cannot be null");
        Objects.requireNonNull(tokenEntity.getProject(), "Token project cannot be null");
        Objects.requireNonNull(tokenEntity.getDeviceId(), "Token device ID cannot be null");
        tokenEntityMap.put(tokenEntity.getDeviceId(), tokenEntity);
    }

    /**
     * 添加设备
     * @param deviceEntity 设备实体
     */
    public void addDevice(DeviceEntity deviceEntity) {
        Objects.requireNonNull(deviceEntity, "Device entity cannot be null");
        Objects.requireNonNull(deviceEntity.getDeviceId(), "Device ID cannot be null");
        deviceEntityMap.put(deviceEntity.getDeviceId(), deviceEntity);
    }

    /**
     * 获取设备实体
     * @param deviceId 设备ID
     * @return 设备实体，如果不存在返回null
     */
    public DeviceEntity getDeviceEntity(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return deviceEntityMap.get(deviceId);
    }

    /**
     * 获取设备令牌
     * @param deviceId 设备ID
     * @param project 项目代码
     * @return 令牌实体，如果不存在返回null
     */
    public TokenEntity getToken(String deviceId, String project) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        Objects.requireNonNull(project, "Project cannot be null");
        
        // The original code used projectTokenEntityMap here, which is being removed.
        // This method will need to be refactored to use projectTokenIdMap if it's to remain functional.
        // For now, assuming projectTokenIdMap is the intended source for project-specific tokens.
        return tokenEntityMap.values().stream()
            .filter(token -> token.getProject().equals(project))
            .filter(token -> token.getDeviceId().equals(deviceId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 获取项目下所有TokenEntity
     */
    public java.util.List<TokenEntity> getProjectTokens(String project) {
        Objects.requireNonNull(project, "Project cannot be null");
        return tokenEntityMap.values().stream()
            .filter(token -> project.equals(token.getProject()))
            .collect(Collectors.toList());
    }

    /**
     * 检查设备是否存在
     * @param deviceId 设备ID
     * @return 是否存在
     */
    public boolean containsDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return deviceEntityMap.containsKey(deviceId);
    }

    /**
     * 检查设备令牌是否存在
     * @param deviceId 设备ID
     * @return 是否存在
     */
    public boolean containsToken(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return tokenEntityMap.containsKey(deviceId);
    }

    /**
     * 移除设备
     * @param deviceId 设备ID
     * @return 被移除的设备实体，如果不存在返回null
     */
    public DeviceEntity removeDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return deviceEntityMap.remove(deviceId);
    }

    /**
     * 移除设备令牌
     * @param deviceId 设备ID
     * @return 被移除的令牌实体，如果不存在返回null
     */
    public TokenEntity removeToken(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return tokenEntityMap.remove(deviceId);
    }

    /**
     * 获取设备数量
     * @return 设备数量
     */
    public int getDeviceCount() {
        return deviceEntityMap.size();
    }

    /**
     * 获取令牌数量
     * @return 令牌数量
     */
    public int getTokenCount() {
        return tokenEntityMap.size();
    }

    /**
     * 获取项目数量
     * @return 项目数量
     */
    public int getProjectCount() {
        // 统计不同project数量
        return (int) tokenEntityMap.values().stream().map(TokenEntity::getProject).distinct().count();
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        // 创建内存映射实例
        MessageMap<String, DeviceEntity> deviceMap = new InMemoryMessageMap<>();
        MessageMap<String, TokenEntity> tokenMap = new InMemoryMessageMap<>();
        
        // 创建设备仓库管理器
        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(deviceMap, tokenMap);
        
        // 添加项目设备令牌映射
        // deviceRepositoryManager.addProjectDeviceTokenMap(Project.DEMO_APP.getCode(), new InMemoryMessageMap<>()); // Removed
        
        System.out.println("DeviceRepositoryManager initialized successfully");
    }
}
