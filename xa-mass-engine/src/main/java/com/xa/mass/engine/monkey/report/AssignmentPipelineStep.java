package com.xa.mass.engine.monkey.report;

import com.xa.mass.engine.service.AssignmentDiagnosticView;

public interface AssignmentPipelineStep {
    void process(AssignmentDiagnosticView recordService);

    String getName();

    boolean isEnabled();
} 
