package com.example.shop.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;

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

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }
}
