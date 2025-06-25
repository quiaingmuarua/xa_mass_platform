package com.xa.mass.engine.report;

import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class AssignmentReportStep implements AssignmentPipelineStep {
    private static final Logger log = LoggerFactory.getLogger(AssignmentReportStep.class);
    private final boolean enabled;
    public AssignmentReportStep(boolean enabled) { this.enabled = enabled; }
    @Override public void process(AssignmentRecordService recordService) {
        log.info("\n=== 分配统计报告 ===");
        Map<String, Object> report = recordService.generateAssignmentReport();
        log.info("总分配记录数: {}", report.get("totalRecords"));
        log.info("成功分配数: {}", report.get("successCount"));
        log.info("失败分配数: {}", report.get("failedCount"));
        log.info("规则不匹配数: {}", report.get("ruleNotMatchCount"));
        log.info("冲突数: {}", report.get("conflictCount"));
        log.info("成功率: {}%", String.format("%.2f", (Double) report.get("successRate") * 100));
    }
    @Override public String getName() { return "分配统计报告"; }
    @Override public boolean isEnabled() { return enabled; }
} 