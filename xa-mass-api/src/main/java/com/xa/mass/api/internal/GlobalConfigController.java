package com.xa.mass.api.internal;

import com.xa.mass.api.config.GlobalConfig;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.base.enums.Project;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
} 