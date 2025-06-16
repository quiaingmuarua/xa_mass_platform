package com.xa.mass.model.device;


import com.xa.mass.model.common.Region;

import java.util.List;

public class DeviceActiveModel {

    int type; //注册方式 1获取号码 2 复用token
    List<Region> regions; //注册地区

    String channelName;

    String number; //获取的号码

    String registerStatus; //注册或复用状态码

}
