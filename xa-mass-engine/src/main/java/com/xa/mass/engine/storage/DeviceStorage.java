package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;

import java.util.List;
import java.util.Optional;

/**
 * 璁惧瀛樺偍鎺ュ彛
 * 鎻愪緵璁惧鍜孴oken鐨勫瓨鍌ㄦ娊璞¤兘鍔?
 */
public interface DeviceStorage {

    /**
     * 娣诲姞璁惧
     */
    void addDevice(Device device);

    /**
     * 鏍规嵁璁惧ID鑾峰彇璁惧
     */
    Optional<Device> getDevice(String deviceId);

    /**
     * 鏇存柊璁惧
     */
    boolean updateDevice(Device device);

    /**
     * 鍒犻櫎璁惧
     */
    boolean deleteDevice(String deviceId);

    /**
     * 鏍规嵁鍥藉鑾峰彇璁惧鍒楄〃
     */
    List<Device> getDevicesByGroupId(String deviceGroupId);

    /**
     * 鑾峰彇鎵€鏈夎澶?
     */
    List<Device> getAllDevices();

    /**
     * 娣诲姞Token
     */
    void addToken(String deviceId, Token token);

    /**
     * 鏍规嵁璁惧ID鑾峰彇Token
     */
    Optional<Token> getToken(String deviceId);

    /**
     * 鏇存柊Token
     */
    boolean updateToken(String deviceId, Token token);

    /**
     * 鍒犻櫎Token
     */
    boolean deleteToken(String deviceId);

    /**
     * 鑾峰彇鎵€鏈塗oken
     */
    List<Token> getAllTokens();

    /**
     * 灏濊瘯閿佸畾璁惧
     */
    boolean tryLockDevice(String deviceId);

    /**
     * 瑙ｉ攣璁惧
     */
    void unlockDevice(String deviceId);

    /**
     * 妫€鏌ヨ澶囨槸鍚﹁閿佸畾
     */
    boolean isLocked(String deviceId);

    /**
     * 鑾峰彇鎵€鏈夐攣瀹氱殑璁惧ID
     */
    List<String> getLockedDevices();
} 
