package com.xa.mass.engine.v2.enums;

/**
 * 任务状态枚举。
 * <p>
 * NEW      —— 新建任务<br/>
 * BLOCKED  —— 审核完成<br/>
 * READY    —— 分配资源中<br/>
 * RUNNING  —— 运行中（可被调度）<br/>
 * PAUSED   —— 暂停<br/>
 * TERMINAL —— 结束 / 中止<br/>
 */
public enum TaskStatusEnums {

    /** 新建任务 */
    NEW("NEW", "新建任务"),

    /** 审核完成 */
    BLOCKED("BLOCKED", "审核完成"),

    /** 分配资源中 */
    READY("READY", "分配资源中"),

    /** 运行中（可被调度） */
    RUNNING("RUNNING", "运行中（可被调度的任务）"),

    /** 暂停 */
    PAUSED("PAUSED", "暂停"),

    /** 结束 / 中止 */
    TERMINAL("TERMINAL", "结束 / 中止");

    private final String code;
    private final String description;

    TaskStatusEnums(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据 code 获取枚举。
     *
     * @param code 状态编码（大小写不敏感）
     * @return 对应的枚举常量
     * @throws IllegalArgumentException 未匹配到任何状态
     */
    public static TaskStatusEnums of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Task status code cannot be null or blank");
        }
        for (TaskStatusEnums value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown task status code: " + code);
    }

    /**
     * 当前状态是否已结束 / 中止。
     * @return true 表示 TERMINAL
     */
    public boolean isTerminal() {
        return this == TERMINAL;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}