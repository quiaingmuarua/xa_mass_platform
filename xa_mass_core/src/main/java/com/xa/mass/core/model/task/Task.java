package com.xa.mass.core.model.task;

import com.xa.mass.core.model.device.Device;
import com.xa.mass.core.model.device.Token;

public class Task {

    private String tid;

    private String taskName;

    private String taskCountry;

    private int taskInitNumber; //任务

    private int taskValidNumber; //任务有效

    private int taskExecutedNumber; //任务执行


    private int taskUnExecutedNumber; //任务未执行数

    private int runTaskMinDeviceCnt; //最小设备数

    private int scheduleDeviceCnt; //

    private TextContent textContent;

    private User user;  //任务的用

    private Token token;

    private Device deviceLimit;

}


