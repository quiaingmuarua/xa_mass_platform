package com.xa.mass.model.message;

import lombok.Data;

import java.util.List;
import java.util.Map;

// TaskStep.java
@Data
public class TaskStep {
    private String stepId;
    private String action;
    private List<String> dependsOn;
    private Map<String, Object> params;
}
