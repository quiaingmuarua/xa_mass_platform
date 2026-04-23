package com.xa.mass.api.config;

import com.xa.mass.api.aop.ApiLogInterceptor;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.console.FrontendConsoleRoutingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired
    private ApiAuthInterceptor apiAuthInterceptor;

    @Autowired
    private ApiLogInterceptor apiLogInterceptor;

    @Autowired
    private FrontendConsoleRoutingService frontendConsoleRoutingService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuthInterceptor)
                .addPathPatterns("/api/**", "/status/api/**",
                        "/status/workers/message-history",
                        "/status/workers/send-event");
        registry.addInterceptor(apiLogInterceptor)
                .addPathPatterns("/api/**", "/status/api/**",
                        "/status/workers/message-history",
                        "/status/workers/send-event");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String distRoot = frontendConsoleRoutingService.getLocalDistRootResourceLocation();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(distRoot + "assets/");
        registry.addResourceHandler("/favicon.svg")
                .addResourceLocations(distRoot);
    }
} 
