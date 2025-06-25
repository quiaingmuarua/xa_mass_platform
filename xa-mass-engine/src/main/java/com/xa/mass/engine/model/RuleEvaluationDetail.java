package com.xa.mass.engine.model;

/**
 * 规则评估详情
 */
public class RuleEvaluationDetail {
    private String ruleId;
    private String ruleContent;
    private String ruleDesc;
    private boolean passed;
    private String evaluationResult;
    private long evaluationTimeMs;

    public RuleEvaluationDetail() {
    }

    public RuleEvaluationDetail(String ruleId, String ruleContent, String ruleDesc,
                                boolean passed, String evaluationResult, long evaluationTimeMs) {
        this.ruleId = ruleId;
        this.ruleContent = ruleContent;
        this.ruleDesc = ruleDesc;
        this.passed = passed;
        this.evaluationResult = evaluationResult;
        this.evaluationTimeMs = evaluationTimeMs;
    }

    // Getters and Setters
    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleContent() {
        return ruleContent;
    }

    public void setRuleContent(String ruleContent) {
        this.ruleContent = ruleContent;
    }

    public String getRuleDesc() {
        return ruleDesc;
    }

    public void setRuleDesc(String ruleDesc) {
        this.ruleDesc = ruleDesc;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getEvaluationResult() {
        return evaluationResult;
    }

    public void setEvaluationResult(String evaluationResult) {
        this.evaluationResult = evaluationResult;
    }

    public long getEvaluationTimeMs() {
        return evaluationTimeMs;
    }

    public void setEvaluationTimeMs(long evaluationTimeMs) {
        this.evaluationTimeMs = evaluationTimeMs;
    }

    @Override
    public String toString() {
        return String.format("RuleEvaluationDetail{ruleId='%s', passed=%s, result='%s', timeMs=%d}",
                ruleId, passed, evaluationResult, evaluationTimeMs);
    }
} 