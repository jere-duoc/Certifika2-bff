package com.certifika2.bff.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.certifika2.bff.config.BffProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REGISTRATION_ID = "cognito";

    private final OAuth2AuthorizedClientService authorizedClients;
    private final BffProperties properties;

    public AuthController(
            OAuth2AuthorizedClientService authorizedClients,
            BffProperties properties
    ) {
        this.authorizedClients = authorizedClients;
        this.properties = properties;
    }

    @GetMapping("/cognito")
    ResponseEntity<Void> login() {
        return ResponseEntity.status(302)
                .location(URI.create("/api/oauth2/authorization/" + REGISTRATION_ID))
                .build();
    }

    @GetMapping("/session")
    SessionResponse session(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser user)) {
            return SessionResponse.signedOut();
        }

        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
                REGISTRATION_ID,
                oauth.getName()
        );
        Long expiresAt = client == null || client.getAccessToken().getExpiresAt() == null
                ? null
                : client.getAccessToken().getExpiresAt().getEpochSecond();
        List<String> groups = user.getClaimAsStringList("cognito:groups");

        return new SessionResponse(
                true,
                new SessionUser(
                        user.getSubject(),
                        firstNonBlank(user.getFullName(), user.getEmail(), user.getSubject()),
                        user.getGivenName(),
                        user.getEmail(),
                        Boolean.TRUE.equals(user.getEmailVerified()),
                        user.getPicture(),
                        groups == null ? List.of() : groups
                ),
                expiresAt,
                true
        );
    }

    @PostMapping("/logout")
    Map<String, String> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authentication instanceof OAuth2AuthenticationToken oauth) {
            authorizedClients.removeAuthorizedClient(REGISTRATION_ID, oauth.getName());
        }
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String logoutUrl = UriComponentsBuilder
                .fromUriString(properties.cognitoDomain() + "/logout")
                .queryParam("client_id", properties.cognitoClientId())
                .queryParam("logout_uri", properties.frontendUrl() + "/")
                .build()
                .encode()
                .toUriString();
        return Map.of("logoutUrl", logoutUrl);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "Usuario";
    }

    public record SessionResponse(
            boolean signedIn,
            SessionUser user,
            Long expiresAt,
            boolean configured
    ) {
        static SessionResponse signedOut() {
            return new SessionResponse(false, null, null, true);
        }
    }

    public record SessionUser(
            String id,
            String name,
            String givenName,
            String email,
            boolean emailVerified,
            String picture,
            List<String> groups
    ) {
    }
}
