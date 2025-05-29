package com.xa.mass.model;

import java.util.List;

public class Task {

    private String tid;

    private String taskName;

    private  String taskCountry;

    private int taskInitNumber; //任务数

    private int taskValidNumber; //任务有效数

    private int taskExecutedNumber; //任务执行数


    private  int taskUnExecutedNumber; //任务未执行数

    private  int runTaskMinDeviceCnt; //最小设备数

    private int scheduleDeviceCnt; //

    private TextContent textContent;

    private User user;  //任务的用户

    private Token token;

    private Device deviceLimit;

}


