package com.certifika2.bff.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(BffProperties.class)
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            BffProperties properties
    ) throws Exception {
        var resolver = new ProviderAuthorizationRequestResolver(registrations);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/logout", "/api/bff/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/bff/certificados/verificar/*").permitAll()
                        .requestMatchers(
                                "/api/auth/cognito",
                                "/api/auth/cognito/callback",
                                "/api/auth/session",
                                "/api/auth/logout",
                                "/api/oauth2/authorization/**",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/bff/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestResolver(resolver))
                        .redirectionEndpoint(endpoint ->
                                endpoint.baseUri("/api/auth/cognito/callback"))
                        .successHandler((request, response, authentication) ->
                                response.sendRedirect(properties.frontendUrl() + "/perfil"))
                        .failureHandler((request, response, exception) -> {
                            log.warn("Falló el callback OIDC de Cognito: {} - {}",
                                    exception.getClass().getSimpleName(), exception.getMessage());
                            response.sendRedirect(properties.frontendUrl() + "/login?error=token_exchange");
                        })
                );

        return http.build();
    }

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients
    ) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build();
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrations,
                authorizedClients
        );
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(BffProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.frontendUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
