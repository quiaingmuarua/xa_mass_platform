package com.xa.mass.model;

import java.util.List;

public class Device {


    private String deviceId; //设备id
    private String groupId;
    private String clientVersion;

    private int deviceState; //-1未知 1在线 2任务中 3掉线


    private  Token token;


    public int getDeviceState() {
        return deviceState;
    };


    public void setDeviceState(int deviceState) {
        this.deviceState = deviceState;
    }



}
