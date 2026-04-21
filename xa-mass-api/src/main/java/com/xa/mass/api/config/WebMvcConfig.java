package com.xa.mass.api.config;

import com.xa.mass.api.aop.ApiLogInterceptor;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired
    private ApiAuthInterceptor apiAuthInterceptor;

    @Autowired
    private ApiLogInterceptor apiLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuthInterceptor)
                .addPathPatterns("/api/**", "/status/api/**", "/status/workers/**");
        registry.addInterceptor(apiLogInterceptor)
                .addPathPatterns("/api/**", "/status/api/**", "/status/workers/**");
    }
} 
