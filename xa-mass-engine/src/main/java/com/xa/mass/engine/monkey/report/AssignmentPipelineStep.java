package com.xa.mass.engine.monkey.report;

import com.xa.mass.engine.service.AssignmentRecordService;

public interface AssignmentPipelineStep {
    void process(AssignmentRecordService recordService);

    String getName();

    boolean isEnabled();
} 