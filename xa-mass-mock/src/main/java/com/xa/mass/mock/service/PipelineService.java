package com.xa.mass.mock.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.mock.event.TaskAssignedEvent;
import com.xa.mass.engine.monkey.report.AssignmentPipelineStep;
import com.xa.mass.engine.monkey.report.AssignmentReportStep;
import com.xa.mass.engine.monkey.report.ConflictReportStep;
import com.xa.mass.engine.monkey.report.RuleEvaluationStep;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PipelineService {
    private final AssignmentRecordService recordService;
    public PipelineService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    @Subscribe
    public void onTaskAssigned(TaskAssignedEvent event) {
        System.out.println("[PipelineService] Pipeline处理: " + event.getTask().getTid());
        CompletableFuture.runAsync(() -> {
            List<AssignmentPipelineStep> pipeline = List.of(
                new AssignmentReportStep(true),
                new ConflictReportStep(true),
                new RuleEvaluationStep(true)
            );
            for (AssignmentPipelineStep step : pipeline) {
                if (step.isEnabled()) step.process(recordService);
            }
        });
    }
} 