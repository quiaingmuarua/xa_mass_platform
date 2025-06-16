package com.xa.mass.model.device;

import lombok.Data;

import java.io.Serializable;


@Data
public class Device implements Serializable {

    private String deviceId;        // 设备唯一标识
    private String groupId;         // 所属分组
    private String clientVersion;   // 客户端版本
    private int deviceState;        // -1未知 1在线 2任务中 3掉线


}