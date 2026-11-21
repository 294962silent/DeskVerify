package com.echomind.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";
    // Keep documentation exceptions aligned with SaasSecurityConfiguration's public paths.
    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health", "/actuator/info", "/docs/**",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**");

    @Bean
    public OpenAPI echoMindOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EchoMind Java API")
                        .version("0.1.3")
                        .description("EchoMind Java 智能客服系统接口文档，支持在线调试 /chat、/search、知识库、监控和评测接口。")
                        .license(new License().name("Internal Project")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Enter the DeskVerify JWT without the Bearer prefix. Endpoint permissions still apply.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
                // The specification is served one path segment below the API root.
                .servers(List.of(new Server().url("../").description("Current API origin")));
    }

    @Bean
    OpenApiCustomizer documentPublicOperations() {
        var matcher = new AntPathMatcher();
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (PUBLIC_PATHS.stream().anyMatch(pattern -> matcher.match(pattern, path))) {
                    item.readOperations().forEach(operation -> operation.setSecurity(List.of()));
                }
            });
        };
    }
}
