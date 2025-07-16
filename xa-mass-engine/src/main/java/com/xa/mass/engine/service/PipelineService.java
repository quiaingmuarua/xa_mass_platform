package com.xa.mass.engine.service;

import com.xa.mass.base.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.engine.monkey.report.AssignmentPipelineStep;
import com.xa.mass.engine.monkey.report.AssignmentReportStep;
import com.xa.mass.engine.monkey.report.ConflictReportStep;
import com.xa.mass.engine.monkey.report.RuleEvaluationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PipelineService {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private final AssignmentRecordService recordService;

    public PipelineService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    public void onTaskAssigned(TaskAssignedEvent event) {
        log.info("[PipelineService] Pipeline处理: {}", event.getTask().getTid());
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