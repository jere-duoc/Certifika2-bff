package com.certifika2.bff.controller;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import com.certifika2.bff.config.BffProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class BffProxyController {
    private static final String BFF_PREFIX = "/api/bff/";
    private static final Set<String> ALLOWED_SERVICES = Set.of("usuarios", "cursos", "chat", "admin");

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final BffProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BffProxyController(
            OAuth2AuthorizedClientManager authorizedClientManager,
            BffProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.authorizedClientManager = authorizedClientManager;
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @RequestMapping("/api/bff/**")
    ResponseEntity<byte[]> proxy(HttpServletRequest request, Authentication authentication)
            throws IOException {
        String requestUri = request.getRequestURI();
        String relativePath = requestUri.substring(BFF_PREFIX.length());
        String firstSegment = relativePath.split("/", 2)[0];
        if (relativePath.isBlank() || !ALLOWED_SERVICES.contains(firstSegment)) {
            return ResponseEntity.notFound().build();
        }

        OAuth2AuthorizedClient client = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId("cognito")
                        .principal(authentication)
                        .build()
        );
        if (client == null) {
            return ResponseEntity.status(401).build();
        }

        String upstreamPath = firstSegment.equals("admin")
                ? "/api/v1/" + relativePath.substring("admin".length()).replaceFirst("^/", "")
                : "/api/proxy/" + relativePath;
        URI target = UriComponentsBuilder
                .fromUriString(properties.apiManagerUrl())
                .path(upstreamPath)
                .query(request.getQueryString())
                .build(true)
                .toUri();

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body;
        String contentType = request.getContentType();
        if (method == HttpMethod.POST && relativePath.equals("usuarios/registro")) {
            if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                    || !(oauth.getPrincipal() instanceof OidcUser user)
                    || user.getEmail() == null
                    || user.getEmail().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            var profile = new LinkedHashMap<String, String>();
            profile.put("email", user.getEmail());
            profile.put("nombre", firstNonBlank(
                    user.getFullName(), user.getGivenName(), user.getEmail()));
            body = objectMapper.writeValueAsBytes(profile);
            contentType = MediaType.APPLICATION_JSON_VALUE;
        } else {
            body = method == HttpMethod.GET || method == HttpMethod.HEAD
                    ? null
                    : request.getInputStream().readAllBytes();
        }

        var requestSpec = restClient.method(method)
                .uri(target)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + client.getAccessToken().getTokenValue())
                .accept(MediaType.ALL);
        if (authentication instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal() instanceof OidcUser oidcUser) {
            requestSpec.header("X-OIDC-Display-Name", firstNonBlank(
                    oidcUser.getFullName(), oidcUser.getGivenName(), oidcUser.getEmail()));
        }
        if (contentType != null) {
            requestSpec.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (body != null) {
            requestSpec.body(body);
        }

        return requestSpec.exchange((upstreamRequest, upstreamResponse) -> {
            var response = ResponseEntity.status(upstreamResponse.getStatusCode());
            MediaType upstreamContentType = upstreamResponse.getHeaders().getContentType();
            if (upstreamContentType != null) {
                response.contentType(upstreamContentType);
            }
            response.header(HttpHeaders.CACHE_CONTROL, "no-store");
            return response.body(upstreamResponse.getBody().readAllBytes());
        });
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Usuario";
    }
}
