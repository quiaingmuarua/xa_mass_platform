package com.xa.mass.api.config;

import com.xa.mass.base.enums.Project;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 全局配置管理
 * 使用枚举管理项目
 */
@Component
public class GlobalConfig {

    /**
     * 获取所有项目（枚举值）
     */
    public List<Project> getAllProjects() {
        return Arrays.asList(Project.values());
    }
} 