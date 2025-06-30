package com.xa.mass.eventbus.enums;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目枚举
 * 支持动态添加新项目
 */
public enum Project {
    DEMO_APP("demoApp", "演示应用"),
    // 可以在这里添加更多默认项目
    ;

    private final String code;
    private final String name;

    Project(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 根据代码获取项目
     */
    public static Project fromCode(String code) {
        return Arrays.stream(values())
                .filter(project -> project.code.equals(code))
                .findFirst()
                .orElse(DEMO_APP); // 默认返回 DEMO_APP
    }

    /**
     * 获取所有项目代码
     */
    public static List<String> getAllCodes() {
        return Arrays.stream(values())
                .map(Project::getCode)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有项目名称
     */
    public static List<String> getAllNames() {
        return Arrays.stream(values())
                .map(Project::getName)
                .collect(Collectors.toList());
    }

    /**
     * 检查项目代码是否存在
     */
    public static boolean isValidCode(String code) {
        return Arrays.stream(values())
                .anyMatch(project -> project.code.equals(code));
    }

    @Override
    public String toString() {
        return code;
    }
} 