package com.xa.mass.engine.report;

import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class RuleEvaluationStep implements AssignmentPipelineStep {
    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationStep.class);
    private final boolean enabled;
    public RuleEvaluationStep(boolean enabled) { this.enabled = enabled; }
    @Override public void process(AssignmentRecordService recordService) {
        log.info("\n=== 规则评估详情 ===");
        List<AssignmentRecord> ruleNotMatchRecords = recordService.getRuleNotMatchRecords();
        if (!ruleNotMatchRecords.isEmpty()) {
            log.info("规则不匹配详情 (前5条):");
            ruleNotMatchRecords.stream().limit(5).forEach(record -> {
                log.info("  设备: {}, 任务: {}, 原因: {}", record.getDeviceId(), record.getTaskId(), record.getReason());
            });
        }
    }
    @Override public String getName() { return "规则评估详情"; }
    @Override public boolean isEnabled() { return enabled; }
} 