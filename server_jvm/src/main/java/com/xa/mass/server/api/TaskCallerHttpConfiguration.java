package com.xa.mass.server.api;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private final UserDirectory users;
    public TaskCallerHttpConfiguration(UserDirectory users) { this.users = users; }
    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OperationCustomizer taskCallerDocumentation() {
        return (operation, handler) -> {
            if (java.util.Arrays.stream(handler.getMethodParameters()).noneMatch(p -> p.getParameterType() == TaskCaller.class)) return operation;
            operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().in("header")
                    .name("X-XA-Dev-User").required(true).description("UTF-8 Base64URL user ID resolved against the Server development directory; not authentication")
                    .schema(new io.swagger.v3.oas.models.media.StringSchema()));
            operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().in("header")
                    .name("X-XA-Dev-Role").required(true).description("Declared role must match the current Server user directory")
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
        resolvers.add(resolver(users));
    }
    public static HandlerMethodArgumentResolver resolver(UserDirectory users) {
        return new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == TaskCaller.class; }
            @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest request, WebDataBinderFactory binder) {
                return resolveCaller(users, request.getHeaderValues("X-XA-Dev-User"), request.getHeaderValues("X-XA-Dev-Role"));
            }
        };
    }
    public static TaskCaller resolveCaller(UserDirectory directory, String[] users, String[] roles) {
        if (users == null || roles == null || users.length != 1 || roles.length != 1
                || users[0] == null || roles[0] == null)
            throw new ServerException(ServerErrorCode.INVALID_TASK_CALLER, "taskCaller.parse", null, null);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return directory.resolveCaller(decoder.decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(users[0]))).toString(), roles[0]);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_CALLER, "taskCaller.parse", null, invalid);
        }
    }
}
