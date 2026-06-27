package com.example.shop.integration;

import com.example.shop.dto.AuthRequestDTO;
import com.example.shop.dto.AuthResponseDTO;
import com.example.shop.dto.RegisterRequestDTO;
import com.example.shop.model.Role;
import com.example.shop.model.User;
import com.example.shop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;

import java.util.ArrayList;

@SuppressWarnings({"PMD.AbstractClassWithoutAbstractMethod", "java:S2068"})
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

    // Test-only admin credentials — DataLoader skips admin seeding when ADMIN_PASSWORD env var is blank
    private static final String ADMIN_TEST_USER = "admin";
    private static final String ADMIN_TEST_PASS = "admin-integration-test";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

        // DataLoader skips admin when ADMIN_PASSWORD env var is blank in test context.
        // Create admin directly via repository so CRUD tests can authenticate as ROLE_ADMIN.
        if (userRepository.findByUsername(ADMIN_TEST_USER).isEmpty()) {
            User admin = new User();
            admin.setUsername(ADMIN_TEST_USER);
            admin.setPassword(passwordEncoder.encode(ADMIN_TEST_PASS));
            admin.setRole(Role.ROLE_ADMIN);
            userRepository.save(admin);
        }

        AuthResponseDTO auth = restTemplate.postForEntity("/auth/login",
                new AuthRequestDTO(ADMIN_TEST_USER, ADMIN_TEST_PASS), AuthResponseDTO.class).getBody();

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
