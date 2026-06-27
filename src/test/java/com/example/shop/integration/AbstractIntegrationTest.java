package com.example.shop.integration;

import com.example.shop.dto.AuthRequestDTO;
import com.example.shop.dto.AuthResponseDTO;
import com.example.shop.dto.RegisterRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;

import java.util.ArrayList;

@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // Singleton containers: started once for the entire test run, stopped by JVM shutdown hook.
    // Using @Container would stop containers after each test class, breaking
    // subsequent classes that share the same Spring context.
    static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        mongoDBContainer.start();
        redisContainer.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @BeforeEach
    void setUpAuth() {
        // Register test user — 409 if already exists, which is fine (used in AuthControllerIT tests)
        restTemplate.postForEntity("/auth/register",
                new RegisterRequestDTO("testuser", "password123"), AuthResponseDTO.class);

        // Admin user is seeded by DataLoader — authenticate as admin so all CRUD tests pass
        AuthResponseDTO auth = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO("admin", "admin123"), AuthResponseDTO.class).getBody();

        String token = auth.token();

        // Replace interceptors so the JWT header is sent on every subsequent request
        ArrayList<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add((req, body, exec) -> {
            req.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return exec.execute(req, body);
        });
        restTemplate.getRestTemplate().setInterceptors(interceptors);
    }
}
