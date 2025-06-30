package com.xa.mass.api.config;

import com.xa.mass.eventbus.enums.Project;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 全局配置管理
 * 使用枚举管理项目，不支持动态添加
 */
@Component
public class GlobalConfig {
    
    /**
     * 获取所有项目（枚举值）
     */
    public List<Project> getAllProjects() {
        return Arrays.asList(Project.values());
    }
    
    /**
     * 添加新项目（枚举不可动态添加）
     */
    public boolean addProject(String code, String name) {
        // 枚举不能动态添加，实际生产建议用配置/数据库
        return false;
    }
    
    /**
     * 删除项目（枚举不可动态删除）
     */
    public boolean removeProject(String code) {
        // 枚举不能动态删除，实际生产建议用配置/数据库
        return false;
    }
    
    /**
     * 获取项目代码列表
     */
    public List<String> getProjectCodes() {
        return Project.getAllCodes();
    }
    
    /**
     * 获取项目名称列表
     */
    public List<String> getProjectNames() {
        return Project.getAllNames();
    }
    
    /**
     * 检查项目代码是否存在
     */
    public boolean isValidProjectCode(String code) {
        return Project.isValidCode(code);
    }
} 