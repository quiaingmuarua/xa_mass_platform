package com.xa.mass.api.internal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 配置页面控制器
 */
@Controller
public class ConfigController {
    
    /**
     * 全局配置页面
     */
    @GetMapping("/config")
    public String configPage() {
        return "config";
    }
} 