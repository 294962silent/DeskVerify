package com.echomind.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

@Configuration(proxyBeanMethods = false)
class LlmCredentialFallbackConfiguration {
    static final String STARTUP_PLACEHOLDER_KEY = "echomind-local-placeholder";

    @Bean
    static BeanFactoryPostProcessor normalizeBlankModelCredentials(ConfigurableEnvironment environment) {
        return factory -> {
            Map<String, Object> placeholders = new HashMap<>();
            for (String name : List.of("spring.ai.deepseek.api-key", "spring.ai.anthropic.api-key")) {
                String credential = environment.getProperty(name);
                if (credential != null && credential.isBlank()) {
                    placeholders.put(name, STARTUP_PLACEHOLDER_KEY);
                }
            }
            if (!placeholders.isEmpty()) {
                // Spring AI requires nonblank SDK credentials at startup; the gateway never sends this sentinel.
                environment.getPropertySources().addFirst(
                        new MapPropertySource("deskverify-local-llm-fallback", placeholders));
            }
        };
    }
}
