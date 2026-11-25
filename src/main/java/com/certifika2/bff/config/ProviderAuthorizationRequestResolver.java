package com.certifika2.bff.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Google by default; explicit Cognito fallback with the same OIDC/PKCE flow. */
public class ProviderAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final ClientRegistrationRepository registrations;

    public ProviderAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    private DefaultOAuth2AuthorizationRequestResolver resolver(HttpServletRequest request) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/api/oauth2/authorization");
        String provider = "COGNITO".equals(request.getParameter("provider")) ? "COGNITO" : "Google";
        resolver.setAuthorizationRequestCustomizer(builder -> {
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
            builder.additionalParameters(parameters -> parameters.put("identity_provider", provider));
        });
        return resolver;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return resolver(request).resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return resolver(request).resolve(request, registrationId);
    }
}
