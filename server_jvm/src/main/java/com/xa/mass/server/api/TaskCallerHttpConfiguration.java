package com.xa.mass.server.api;

import com.xa.mass.server.task.access.TaskCaller;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** HTTP parses a development identity once; application services own resource access. */
@Configuration(proxyBeanMethods = false)
public class TaskCallerHttpConfiguration implements WebMvcConfigurer {
    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OperationCustomizer taskCallerDocumentation() {
        return (operation, handler) -> {
            if (java.util.Arrays.stream(handler.getMethodParameters()).noneMatch(p -> p.getParameterType() == TaskCaller.class)) return operation;
            operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().in("header")
                    .name("X-XA-Dev-User").required(true).description("UTF-8 Base64URL development user ID; not authentication")
                    .schema(new io.swagger.v3.oas.models.media.StringSchema()));
            operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().in("header")
                    .name("X-XA-Dev-Role").required(true).description("Development caller role")
                    .schema(new io.swagger.v3.oas.models.media.StringSchema()._enum(List.of("admin", "operator"))));
            operation.getResponses().addApiResponse("403", new io.swagger.v3.oas.models.responses.ApiResponse()
                    .description("Task caller cannot access this resource or operation")
                    .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>()
                                    .$ref("#/components/schemas/ApiErrorResponse")))));
            return operation;
        };
    }
    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver());
    }
    public static HandlerMethodArgumentResolver resolver() {
        return new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == TaskCaller.class; }
            @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest request, WebDataBinderFactory binder) {
                return TaskCaller.fromHeaders(request.getHeaderValues("X-XA-Dev-User"), request.getHeaderValues("X-XA-Dev-Role"));
            }
        };
    }
}
