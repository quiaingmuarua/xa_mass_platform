package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.util.QueueKeyUtil;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.MessageMapProviderRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 设备仓储管理器
 * 支持项目隔离的设备和Token管理
 */
public class DeviceRepositoryManager {

    // 简单设备映射 Map<deviceId, DeviceEntity>
    private final MessageMap<String, DeviceEntity> deviceMap;
    // 项目token映射
    private final ConcurrentMap<String, MessageMap<String, TokenEntity>> projectTokenEntityMap = new ConcurrentHashMap<>();

    public DeviceRepositoryManager(MessageMap<String, DeviceEntity> deviceMap) {
        this.deviceMap = deviceMap;
    }

    // 设备管理
    public void saveDevice(DeviceEntity device) {
        deviceMap.put(device.getDeviceId(), device);
    }

    public DeviceEntity getDevice(String deviceId) {
        return deviceMap.get(deviceId);
    }

    public boolean containsDevice(String deviceId) {
        return deviceMap.containsKey(deviceId);
    }

    public DeviceEntity removeDevice(String deviceId) {
        return deviceMap.remove(deviceId);
    }

    public int getDeviceCount() {
        return deviceMap.size();
    }

    /**
     * 注册项目
     */
    public void registerProject(String project, MessageMap<String, TokenEntity> tokenMap) {
        Objects.requireNonNull(project, "Project cannot be null");
        Objects.requireNonNull(tokenMap, "Token map cannot be null");
        projectTokenEntityMap.put(project, tokenMap);
    }

    /**
     * 注册所有项目分组
     */
    public void registerAllProjects(java.util.function.Function<Project, MessageMap<String, TokenEntity>> mapSupplier) {
        Objects.requireNonNull(mapSupplier, "Map supplier cannot be null");
        for (Project project : Project.values()) {
            registerProject(project.getCode(), mapSupplier.apply(project));
        }
    }

    // Token管理
    public void saveToken(String project, TokenEntity token) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        if (tokenMap != null) {
            tokenMap.put(token.getTokenId(), token);
        }
    }

    public TokenEntity getToken(String project, String tokenId) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        return tokenMap != null ? tokenMap.get(tokenId) : null;
    }

    public boolean containsToken(String project, String tokenId) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        return tokenMap != null && tokenMap.containsKey(tokenId);
    }

    public TokenEntity removeToken(String project, String tokenId) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        return tokenMap != null ? tokenMap.remove(tokenId) : null;
    }

    public int getTokenCount(String project) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        return tokenMap != null ? tokenMap.size() : 0;
    }

    public List<TokenEntity> getProjectTokens(String project) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        if (tokenMap != null) {
            return new ArrayList<>(tokenMap.values());
        }
        return java.util.Collections.emptyList();
    }

    // 根据设备ID查找对应的Token
    public TokenEntity findTokenByDeviceId(String project, String deviceId) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        if (tokenMap != null) {
            return tokenMap.values().stream()
                    .filter(token -> deviceId.equals(token.getDeviceId()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    // 批量获取设备
    public List<DeviceEntity> getAllDevices() {
        return new ArrayList<>(deviceMap.values());
    }

    // 按状态过滤设备
    public List<DeviceEntity> getDevicesByStatus(String status) {
        return deviceMap.values().stream()
                .filter(device -> status.equals(device.getDeviceStatus()))
                .collect(Collectors.toList());
    }

    // 根据项目获取设备（通过项目Token映射判断）
    public List<DeviceEntity> getDevicesByProject(String project) {
        return deviceMap.values().stream()
                .filter(device -> device.hasTokenForProject(project))
                .collect(Collectors.toList());
    }

    // 获取Token映射
    public MessageMap<String, TokenEntity> getProjectTokenMap(String project) {
        return projectTokenEntityMap.get(project);
    }

    // 清空项目Token
    public void clearProjectTokens(String project) {
        MessageMap<String, TokenEntity> tokenMap = projectTokenEntityMap.get(project);
        if (tokenMap != null) {
            tokenMap.values().clear();
        }
    }

    // 统计信息
    public int getTotalProjects() {
        return projectTokenEntityMap.size();
    }

    public int getTotalTokens() {
        return projectTokenEntityMap.values().stream()
                .mapToInt(MessageMap::size)
                .sum();
    }
}
