package com.xa.mass.engine.monkey.report;

import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ConflictReportStep implements AssignmentPipelineStep {
    private static final Logger log = LoggerFactory.getLogger(ConflictReportStep.class);
    private final boolean enabled;

    public ConflictReportStep(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void process(AssignmentRecordService recordService) {
        log.info("\n=== 冲突检测 ===");
        List<Map<String, Object>> conflicts = recordService.detectConflicts();
        if (conflicts.isEmpty()) {
            log.info("未检测到冲突");
        } else {
            log.info("检测到 {} 个潜在冲突:", conflicts.size());
            for (Map<String, Object> conflict : conflicts) {
                log.info("  设备: {}, 冲突类型: {}, 时间间隔: {} 分钟", conflict.get("deviceId"), conflict.get("conflictType"), conflict.get("timeDiffMinutes"));
            }
        }
    }

    @Override
    public String getName() {
        return "冲突检测";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
} 