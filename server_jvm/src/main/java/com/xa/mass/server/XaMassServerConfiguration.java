package com.xa.mass.server;

import com.scalar.maven.webmvc.ScalarWebMvcController;
import com.scalar.maven.webmvc.SpringBootScalarProperties;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = XaMassServerConfiguration.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@ConfigurationPropertiesScan(basePackageClasses = XaMassServerConfiguration.class)
@EnableConfigurationProperties(SpringBootScalarProperties.class)
@Import(ScalarWebMvcController.class)
public class XaMassServerConfiguration {

}
