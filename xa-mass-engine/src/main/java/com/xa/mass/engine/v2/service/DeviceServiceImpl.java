package com.xa.mass.engine.v2.service;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;

import java.util.Objects;
import java.util.function.Function;

/**
 * 设备服务实现 v2
 */
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepositoryManager repository;

    public DeviceServiceImpl(DeviceRepositoryManager repository) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
    }

    @Override
    public void createDevice(DeviceEntity device) {
        Objects.requireNonNull(device, "Device cannot be null");
        repository.saveDevice(device);
    }

    @Override
    public void updateDevice(DeviceEntity device) {
        Objects.requireNonNull(device, "Device cannot be null");
        if (!repository.containsDevice(device.getDeviceId())) {
            throw new IllegalArgumentException("Device not found: " + device.getDeviceId());
        }
        repository.saveDevice(device);
    }

    @Override
    public void createToken(TokenEntity token) {
        Objects.requireNonNull(token, "Token cannot be null");
        Objects.requireNonNull(token.getProject(), "Token project cannot be null");
        repository.saveToken(token.getProject(), token);
    }


    @Override
    public DeviceEntity getDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return repository.getDevice(deviceId);
    }

    @Override
    public boolean existsDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        return repository.containsDevice(deviceId);
    }

    @Override
    public TokenEntity getToken(String project, String tokenId) {
        Objects.requireNonNull(project, "Project cannot be null");
        Objects.requireNonNull(tokenId, "Token ID cannot be null");
        return repository.getToken(project, tokenId);
    }

    @Override
    public int getDeviceCount() {
        return repository.getDeviceCount();
    }

    @Override
    public int getTokenCount(String project) {
        Objects.requireNonNull(project, "Project cannot be null");
        return repository.getTokenCount(project);
    }

    @Override
    public void registerAllProjects(Function<Project, MessageMap<String, TokenEntity>> mapSupplier) {
        repository.registerAllProjects(mapSupplier);
    }

    @Override
    public void registerProject(String project, MessageMap<String, TokenEntity> tokenMap) {
        repository.registerProject(project,tokenMap);
    }

    @Override
    public int getProjectCount() {
        return repository.getTotalProjects();
    }

    @Override
    public boolean removeDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "Device ID cannot be null");
        DeviceEntity removed = repository.removeDevice(deviceId);
        return removed != null;
    }

    @Override
    public boolean existsToken(String project, String tokenId) {
        Objects.requireNonNull(project, "Project cannot be null");
        Objects.requireNonNull(tokenId, "Token ID cannot be null");
        return repository.containsToken(project, tokenId);
    }

    @Override
    public boolean removeToken(String project, String tokenId) {
        Objects.requireNonNull(project, "Project cannot be null");
        Objects.requireNonNull(tokenId, "Token ID cannot be null");
        TokenEntity removed = repository.removeToken(project, tokenId);
        return removed != null;
    }
} 