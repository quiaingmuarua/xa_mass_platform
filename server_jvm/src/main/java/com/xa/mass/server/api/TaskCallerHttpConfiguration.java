package com.xa.mass.server.api;

import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.access.TaskCaller;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Hands the caller admitted by {@link AccessKeyFilter} to operations; application services own resource access. */
@Configuration(proxyBeanMethods = false)
public class TaskCallerHttpConfiguration implements WebMvcConfigurer {
    public static final String SECURITY_SCHEME = "accessKey";

    @Bean
    OperationCustomizer taskCallerDocumentation() {
        return (operation, handler) -> {
            if (java.util.Arrays.stream(handler.getMethodParameters()).noneMatch(p -> p.getParameterType() == TaskCaller.class)) return operation;
            operation.getResponses().addApiResponse("403", errorResponse("Task caller cannot access this resource or operation"));
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer accessKeyDocumentation() {
        return document -> {
            document.getComponents().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP).scheme("bearer")
                    .description("Per-user access key issued by an administrator. Worker and internal Adapter "
                            + "machine routes are exempt; the gateway Worker realm and loopback binding own their exposure."));
            if (document.getPaths() == null) return;
            document.getPaths().forEach((template, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (AccessKeyFilter.exemptTemplate(method.name(), template)) return;
                operation.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
                operation.getResponses().addApiResponse("401", errorResponse("Access key is missing or invalid"));
            }));
        };
    }

    private static ApiResponse errorResponse(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<ApiErrorResponse>().$ref("#/components/schemas/ApiErrorResponse"))));
    }

    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver());
    }

    public static HandlerMethodArgumentResolver resolver() {
        return new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == TaskCaller.class; }
            @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest request, WebDataBinderFactory binder) {
                if (request.getAttribute(AccessKeyFilter.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof TaskCaller caller)
                    return caller;
                throw new ServerException(ServerErrorCode.UNAUTHENTICATED, "taskCaller.resolve", null, null);
            }
        };
    }
}
