package com.xa.mass.model.message;

import lombok.Data;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class TaskStep {
    private String stepId;
    private String action;
    private List<String> dependsOn;
    private Map<String, Object> params;
} 