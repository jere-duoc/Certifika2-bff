package com.certifika2.bff.controller;
import com.certifika2.bff.config.BffProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BffCertificateProxyTest {
    OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    BffProxyController controller = new BffProxyController(manager,
            new BffProperties("http://localhost:3000", "http://gateway", "https://auth.test.invalid", "client"), builder, new ObjectMapper());
    @Test void downloadPreservesExactBinaryAndFilename() throws Exception {
        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(60)));
        when(manager.authorize(any())).thenReturn(client);
        byte[] bytes = {37, 80, 68, 70, 0, (byte)255};
        server.expect(requestTo("http://gateway/api/proxy/certificados/1/descarga"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(bytes, MediaType.APPLICATION_PDF).header("Content-Disposition", "attachment; filename=certificado-1.pdf"));
        var response = controller.proxy(new MockHttpServletRequest("GET", "/api/bff/certificados/1/descarga"), mock(OAuth2AuthenticationToken.class));
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=certificado-1.pdf");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store"); server.verify();
    }
    @Test void publicVerificationDoesNotRequireOrForwardAccessToken() throws Exception {
        server.expect(requestTo("http://gateway/api/proxy/certificados/verificar/unknown"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"valido\":false}", MediaType.APPLICATION_JSON));
        var response = controller.proxy(new MockHttpServletRequest("GET", "/api/bff/certificados/verificar/unknown"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200); verifyNoInteractions(manager); server.verify();
    }
    @Test void anonymousDownloadIsRejectedBeforeProxyCall() throws Exception {
        var response = controller.proxy(new MockHttpServletRequest("GET", "/api/bff/certificados/1/descarga"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401); verifyNoInteractions(manager); server.verify();
    }
}
