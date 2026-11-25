package com.certifika2.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bff")
public record BffProperties(
        String frontendUrl,
        String apiManagerUrl,
        String cognitoDomain,
        String cognitoClientId
) {
    public BffProperties {
        frontendUrl = stripTrailingSlash(frontendUrl);
        apiManagerUrl = stripTrailingSlash(apiManagerUrl);
        cognitoDomain = stripTrailingSlash(cognitoDomain);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
