package com.xa.mass.engine.storage;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;

import java.util.List;
import java.util.Optional;

/**
 * 设备存储接口
 * 提供设备和Token的存储抽象能力
 */
public interface DeviceStorage {
    
    /**
     * 添加设备
     */
    void addDevice(Device device);
    
    /**
     * 根据设备ID获取设备
     */
    Optional<Device> getDevice(String deviceId);
    
    /**
     * 更新设备
     */
    boolean updateDevice(Device device);
    
    /**
     * 删除设备
     */
    boolean deleteDevice(String deviceId);
    
    /**
     * 根据国家获取设备列表
     */
    List<Device> getDevicesByCountry(String country);
    
    /**
     * 获取所有设备
     */
    List<Device> getAllDevices();
    
    /**
     * 添加Token
     */
    void addToken(String deviceId, Token token);
    
    /**
     * 根据设备ID获取Token
     */
    Optional<Token> getToken(String deviceId);
    
    /**
     * 更新Token
     */
    boolean updateToken(String deviceId, Token token);
    
    /**
     * 删除Token
     */
    boolean deleteToken(String deviceId);
    
    /**
     * 获取所有Token
     */
    List<Token> getAllTokens();
    
    /**
     * 尝试锁定设备
     */
    boolean tryLockDevice(String deviceId);
    
    /**
     * 解锁设备
     */
    void unlockDevice(String deviceId);
    
    /**
     * 检查设备是否被锁定
     */
    boolean isLocked(String deviceId);
    
    /**
     * 获取所有锁定的设备ID
     */
    List<String> getLockedDevices();
} 