package com.xa.mass.api.internal;

import com.xa.mass.api.config.GlobalConfig;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.eventbus.enums.Project;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全局配置控制器
 * 提供项目管理等全局配置功能
 */
@RestController
@RequestMapping("/api/config")
public class GlobalConfigController {
    
    @Autowired
    private GlobalConfig globalConfig;
    
    /**
     * 获取所有项目列表
     */
    @GetMapping("/projects")
    public ApiResponse<List<Project>> getProjects() {
        try {
            List<Project> projects = globalConfig.getAllProjects();
            return ApiResponse.success(projects);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取项目列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 添加新项目（枚举不可动态添加，接口保留）
     */
    @PostMapping("/projects")
    public ApiResponse<Boolean> addProject(@RequestBody Project request) {
        return ApiResponse.error(400, "当前不支持动态添加项目（如需支持请用配置/数据库）");
    }
    
    /**
     * 删除项目（枚举不可动态删除，接口保留）
     */
    @DeleteMapping("/projects/{code}")
    public ApiResponse<Boolean> removeProject(@PathVariable String code) {
        return ApiResponse.error(400, "当前不支持删除项目（如需支持请用配置/数据库）");
    }
    
    /**
     * 获取项目代码列表
     */
    @GetMapping("/projects/codes")
    public ApiResponse<List<String>> getProjectCodes() {
        try {
            List<String> codes = globalConfig.getProjectCodes();
            return ApiResponse.success(codes);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取项目代码列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查项目代码是否有效
     */
    @GetMapping("/projects/validate/{code}")
    public ApiResponse<Boolean> validateProjectCode(@PathVariable String code) {
        try {
            boolean isValid = globalConfig.isValidProjectCode(code);
            return ApiResponse.success(isValid);
        } catch (Exception e) {
            return ApiResponse.error(500, "验证项目代码失败: " + e.getMessage());
        }
    }
} 