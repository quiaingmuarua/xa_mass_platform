package com.xa.mass.core.model.device;

import lombok.Data;

import java.io.Serializable;


@Data
public class Device implements Serializable {

    private String deviceId;        // 设备唯一标识
    private String groupId;         // 所属分
    private String clientVersion;   // 客户端版
    private int deviceState;        // -1未知 1在线 2任务3掉线


}
