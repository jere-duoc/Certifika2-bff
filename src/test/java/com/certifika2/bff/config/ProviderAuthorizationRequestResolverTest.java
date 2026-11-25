package com.certifika2.bff.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderAuthorizationRequestResolverTest {
    private final ProviderAuthorizationRequestResolver resolver = new ProviderAuthorizationRequestResolver(
        new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("cognito")
            .clientId("test-client").clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://example.com/api/auth/cognito/callback").scope("openid", "email")
            .authorizationUri("https://example.com/oauth2/authorize")
            .tokenUri("https://example.com/oauth2/token").userInfoUri("https://example.com/userInfo")
            .userNameAttributeName("sub").build()));

    @Test void googleIsDefaultAndPkceIsEnabled() {
        var result = resolver.resolve(new MockHttpServletRequest(), "cognito");
        assertThat(result.getAdditionalParameters()).containsEntry("identity_provider", "Google")
            .containsEntry("code_challenge_method", "S256").containsKey("code_challenge");
        assertThat(result.getState()).isNotBlank();
    }

    @Test void cognitoFallbackRetainsPkceAndState() {
        var request = new MockHttpServletRequest();
        request.setParameter("provider", "COGNITO");
        var result = resolver.resolve(request, "cognito");
        assertThat(result.getAdditionalParameters()).containsEntry("identity_provider", "COGNITO")
            .containsKey("code_challenge");
        assertThat(result.getState()).isNotBlank();
    }

    @Test void arbitraryProvidersAreNotForwarded() {
        var request = new MockHttpServletRequest();
        request.setParameter("provider", "https://untrusted.example");
        assertThat(resolver.resolve(request, "cognito").getAdditionalParameters())
            .containsEntry("identity_provider", "Google");
    }
}
