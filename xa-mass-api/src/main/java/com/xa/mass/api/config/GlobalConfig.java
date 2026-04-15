package com.xa.mass.api.config;

import com.xa.mass.base.enums.Project;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全局配置管理
 */
@Component
public class GlobalConfig {

    /**
     * 获取所有项目代码列表
     */
    public List<String> getAllProjects() {
        return Project.getAllCodes();
    }
} 