package com.xa.mass.engine.v2.service;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.base.enums.Project;
import java.util.List;

public interface DeviceService {
    // 设备管理
    void createDevice(DeviceEntity device);
    void updateDevice(DeviceEntity device);
    DeviceEntity getDevice(String deviceId);
    boolean existsDevice(String deviceId);
    boolean removeDevice(String deviceId);
    int getDeviceCount();

    // Token 管理（支持项目隔离）
    void createToken(TokenEntity token);
    TokenEntity getToken(String project, String tokenId);
    boolean existsToken(String project, String tokenId);
    boolean removeToken(String project, String tokenId);
    int getTokenCount(String project);

    // 项目管理
    void registerAllProjects(java.util.function.Function<Project, MessageMap<String, TokenEntity>> mapSupplier);
    void registerProject(String project, MessageMap<String, TokenEntity> tokenMap);
    int getProjectCount();

    // 兼容旧接口的方法（标记为过时）
    @Deprecated
    default void registerDevice(DeviceEntity deviceEntity) {
        createDevice(deviceEntity);
    }

    @Deprecated
    default void registerDevices(List<DeviceEntity> deviceEntities) {
        deviceEntities.forEach(this::createDevice);
    }

    @Deprecated
    default void bindDeviceToken(TokenEntity tokenEntity) {
        createToken(tokenEntity);
    }

    @Deprecated
    default TokenEntity getDeviceToken(String deviceId, Project project) {
        // 通过 DeviceEntity 查找对应的 tokenId，然后获取 Token
        DeviceEntity device = getDevice(deviceId);
        if (device != null) {
            String tokenId = device.getTokenForProject(project.getCode());
            if (tokenId != null) {
                return getToken(project.getCode(), tokenId);
            }
        }
        return null;
    }

    @Deprecated
    default List<TokenEntity> getProjectTokens(Project project) {
        // 此方法需要在具体实现中提供
        throw new UnsupportedOperationException("Use repository.getProjectTokens() directly");
    }

    @Deprecated
    default int getTokenCount() {
        // 返回所有项目的 Token 总数，需要在具体实现中提供
        throw new UnsupportedOperationException("Use getTokenCount(project) for specific project");
    }

    @Deprecated
    default boolean containsDevice(String deviceId) {
        return existsDevice(deviceId);
    }

    @Deprecated
    default boolean containsToken(String deviceId) {
        // 此方法逻辑不清晰，需要指定 project，标记为不支持
        throw new UnsupportedOperationException("Use existsToken(project, tokenId) instead");
    }

    @Deprecated
    default TokenEntity removeToken(String deviceId) {
        // 此方法逻辑不清晰，需要指定 project，标记为不支持
        throw new UnsupportedOperationException("Use removeToken(project, tokenId) instead");
    }
} 