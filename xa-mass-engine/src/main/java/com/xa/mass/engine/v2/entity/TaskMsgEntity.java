package com.xa.mass.engine.v2.entity;

import java.util.List;

public class  TaskMsgEntity {
    private String msgId;
    private String taskId;
    private  String taskMsgStatus; //INIT BINDING RUNNING COMPLETE 完整的生命抓过你太
    private  String completeStatus; //INIT SUCCESS FAILED EXPIRED  最终状态明细
    List<String> deviceIds; //匹配到的设备
    List<String> deviceTokens; //匹配的到的token
    List<String> batchRawSeeds; //任务种子


    private int retryCount;
    private long sendTime;
    private long createTime;
    private long startTime;
    private long completeTime;


}