package com.echomind.saas.identity.security;

import com.echomind.saas.identity.application.ActorScopeResolver;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public final class SaasSecurityConfiguration {

    private static final int MINIMUM_HMAC_SECRET_BYTES = 32;

    @Bean
    JwtActorScopeConverter jwtActorScopeConverter(ActorScopeResolver actorScopeResolver) {
        return new JwtActorScopeConverter(actorScopeResolver);
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${echomind.security.jwt.hmac-secret}") String encodedSecret) {
        byte[] secret = Base64.getDecoder().decode(encodedSecret);
        if (secret.length < MINIMUM_HMAC_SECRET_BYTES) {
            throw new IllegalStateException("JWT HMAC secret must decode to at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtActorScopeConverter jwtActorScopeConverter,
            AuthenticationFailureResponder authenticationFailureResponder,
            AccessDeniedFailureResponder accessDeniedFailureResponder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationFailureResponder::commence)
                        .accessDeniedHandler(accessDeniedFailureResponder::handle)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtActorScopeConverter)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationFailureResponder::commence)
                        .accessDeniedHandler(accessDeniedFailureResponder::handle))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
