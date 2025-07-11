package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.base.enums.Project;
import java.util.List;

public interface DeviceService {
    // 设备注册
    void registerDevice(DeviceEntity deviceEntity);
    // 设备批量注册
    void registerDevices(List<DeviceEntity> deviceEntities);
    // 设备绑定令牌
    void bindDeviceToken(TokenEntity tokenEntity);
    // 项目注册
    void registerProject(Project project);
    // 批量注册所有项目
    void registerAllProjects();
    // 查询设备
    DeviceEntity getDevice(String deviceId);
    // 查询设备令牌
    TokenEntity getDeviceToken(String deviceId, Project project);
    // 查询项目下所有令牌
    List<TokenEntity> getProjectTokens(Project project);
    // 设备数量
    int getDeviceCount();
    // 令牌数量
    int getTokenCount();
    // 项目数量
    int getProjectCount();
    // 设备是否存在
    boolean containsDevice(String deviceId);
    // 令牌是否存在
    boolean containsToken(String deviceId);
    // 移除设备
    DeviceEntity removeDevice(String deviceId);
    // 移除令牌
    TokenEntity removeToken(String deviceId);
} 