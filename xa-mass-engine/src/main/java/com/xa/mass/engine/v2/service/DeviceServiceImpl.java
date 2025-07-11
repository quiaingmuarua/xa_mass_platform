package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DeviceServiceImpl implements DeviceService {
    private final DeviceRepositoryManager repository;

    public DeviceServiceImpl(DeviceRepositoryManager repository) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
    }

    @Override
    public void registerDevice(DeviceEntity deviceEntity) {
        Objects.requireNonNull(deviceEntity, "Device entity cannot be null");
        repository.addDevice(deviceEntity);
    }

    @Override
    public void registerDevices(List<DeviceEntity> deviceEntities) {
        Objects.requireNonNull(deviceEntities, "Device entities cannot be null");
        for (DeviceEntity device : deviceEntities) {
            repository.addDevice(device);
        }
    }

    @Override
    public void bindDeviceToken(TokenEntity tokenEntity) {
        Objects.requireNonNull(tokenEntity, "Token entity cannot be null");
        repository.addDeviceBindToken(tokenEntity);
    }

    @Override
    public void registerProject(Project project) {
        repository.registerProject(project, new InMemoryMessageMap<>());
    }

    @Override
    public void registerAllProjects() {
        repository.registerAllProjects(p -> new InMemoryMessageMap<>());
    }

    @Override
    public DeviceEntity getDevice(String deviceId) {
        return repository.getDeviceEntity(deviceId);
    }

    @Override
    public TokenEntity getDeviceToken(String deviceId, Project project) {
        return repository.getProjectTokens(project.getCode()).stream()
            .filter(token -> token.getDeviceId().equals(deviceId))
            .findFirst().orElse(null);
    }

    @Override
    public List<TokenEntity> getProjectTokens(Project project) {
        return repository.getProjectTokens(project.getCode());
    }

    @Override
    public int getDeviceCount() {
        return repository.getDeviceCount();
    }

    @Override
    public int getTokenCount() {
        return repository.getTokenCount();
    }

    @Override
    public int getProjectCount() {
        return repository.getProjectCount();
    }

    @Override
    public boolean containsDevice(String deviceId) {
        return repository.containsDevice(deviceId);
    }

    @Override
    public boolean containsToken(String deviceId) {
        return repository.containsToken(deviceId);
    }

    @Override
    public DeviceEntity removeDevice(String deviceId) {
        return repository.removeDevice(deviceId);
    }

    @Override
    public TokenEntity removeToken(String deviceId) {
        return repository.removeToken(deviceId);
    }
} 