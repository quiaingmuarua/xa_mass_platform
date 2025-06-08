package com.xa.mass.model.message;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskMessage {
    private String msgId;
    private MsgType msgType;
    private String subMsgType;
    private Long timestamp;
    private Map<String, Object> context;
    private List<TaskStep> steps;
}
