package com.xa.mass.model.message;

import lombok.Data;

import java.util.Map;

// TaskResult.java
@Data
public class TaskResult {
    private String msgId;
    private MsgType msgType;  // STEP 或 ALL
    private String code;      // "200" / "500"
    private String subCode;
    private String message;
    private Map<String, Object> result;
}