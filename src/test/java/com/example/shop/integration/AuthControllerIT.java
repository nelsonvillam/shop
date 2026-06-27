package com.example.shop.integration;

import com.example.shop.dto.AuthRequestDTO;
import com.example.shop.dto.AuthResponseDTO;
import com.example.shop.dto.RegisterRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void register_withNewUsername_returns201AndToken() {
        ResponseEntity<AuthResponseDTO> response = restTemplate.postForEntity("/auth/register",
                new RegisterRequestDTO("newuser_" + System.currentTimeMillis(), "password123"),
                AuthResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void register_withExistingUsername_returns409() {
        // "testuser" already registered by AbstractIntegrationTest.setUpAuth()
        ResponseEntity<Void> response = restTemplate.postForEntity("/auth/register",
                new RegisterRequestDTO("testuser", "password123"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_withValidCredentials_returns200AndToken() {
        ResponseEntity<AuthResponseDTO> response = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "password123"), AuthResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void login_withWrongPassword_returns401() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("testuser", "wrongpassword"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() {
        withInterceptors(List.of(), () -> {
            ResponseEntity<Void> response = restTemplate.getForEntity("/api/products", Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    @Test
    void protectedEndpoint_withMalformedToken_returns401() {
        withInterceptors(bearerHeader("not.a.valid.jwt"), () -> {
            ResponseEntity<Void> response = restTemplate.getForEntity("/api/products", Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
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
