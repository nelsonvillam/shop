package com.example.shop.integration;

import com.example.shop.dto.AuthRequestDTO;
import com.example.shop.dto.AuthResponseDTO;
import com.example.shop.dto.RefreshRequestDTO;
import com.example.shop.dto.RegisterRequestDTO;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void register_withNewUsername_returns201AndTokenPair() {
        ResponseEntity<AuthResponseDTO> response = restTemplate.postForEntity("/auth/register",
                new RegisterRequestDTO("newuser_" + System.currentTimeMillis(), "password123"),
                AuthResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void register_withExistingUsername_returns409() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/auth/register",
                new RegisterRequestDTO("testuser", "password123"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_withValidCredentials_returns200AndTokenPair() {
        ResponseEntity<AuthResponseDTO> response = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "password123"), AuthResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void login_withWrongPassword_returns401() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "wrongpassword"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withValidToken_returnsNewTokenPair() {
        String oldRefresh = loginAndGetRefreshToken();

        ResponseEntity<AuthResponseDTO> response = restTemplate.postForEntity("/auth/refresh",
                new RefreshRequestDTO(oldRefresh), AuthResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotEqualTo(oldRefresh);
    }

    @Test
    void refresh_withInvalidToken_returns401() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/auth/refresh",
                new RefreshRequestDTO("not-a-real-token"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_revokesRefreshToken() {
        String refreshToken = loginAndGetRefreshToken();

        restTemplate.postForEntity("/auth/logout", new RefreshRequestDTO(refreshToken), Void.class);

        ResponseEntity<Void> retryRefresh = restTemplate.postForEntity("/auth/refresh",
                new RefreshRequestDTO(refreshToken), Void.class);
        assertThat(retryRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    void adminEndpoint_asUser_returns403() {
        String userToken = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "password123"), AuthResponseDTO.class)
                .getBody().token();

        withInterceptors(bearerHeader(userToken), () -> {
            ResponseEntity<Void> response = restTemplate.postForEntity("/api/products",
                    Map.of("name", "Test", "price", 10.0, "stock", 5), Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        });
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    void protectedEndpoint_withoutToken_returns401() {
        withInterceptors(List.of(), () -> {
            ResponseEntity<Void> response = restTemplate.getForEntity("/api/products", Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    void protectedEndpoint_withMalformedToken_returns401() {
        withInterceptors(bearerHeader("not.a.valid.jwt"), () -> {
            ResponseEntity<Void> response = restTemplate.getForEntity("/api/products", Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    private String loginAndGetRefreshToken() {
        return restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "password123"), AuthResponseDTO.class)
                .getBody().refreshToken();
    }

    private List<ClientHttpRequestInterceptor> bearerHeader(String token) {
        return List.of((req, body, exec) -> {
            req.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return exec.execute(req, body);
        });
    }

    private void withInterceptors(List<ClientHttpRequestInterceptor> interceptors, Runnable action) {
        List<ClientHttpRequestInterceptor> original = restTemplate.getRestTemplate().getInterceptors();
        restTemplate.getRestTemplate().setInterceptors(interceptors);
        try {
            action.run();
        } finally {
            restTemplate.getRestTemplate().setInterceptors(original);
        }
    }
}
