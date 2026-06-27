# TestContainers Setup Guide

This project uses [Testcontainers](https://testcontainers.com/) to run integration tests against a real MongoDB instance spun up in Docker — no local MongoDB installation required for testing.

---

## Prerequisites

- **Docker Desktop** must be installed and running before executing integration tests.
- Java 21+ and Maven 3.9+.

---

## Dependencies (pom.xml)

Three Testcontainers artifacts are required, plus Apache HttpClient 5 for `PATCH` support in `TestRestTemplate`:

```xml
<dependencies>
    <!-- JUnit 5 + Mockito + AssertJ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Spring Boot ↔ Testcontainers bridge -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- MongoDB container -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mongodb</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- @Testcontainers / @Container JUnit 5 annotations -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Required for TestRestTemplate to support PATCH requests -->
    <dependency>
        <groupId>org.apache.httpcomponents.client5</groupId>
        <artifactId>httpclient5</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Version management is handled by Spring Boot's BOM — no explicit versions needed for any of the above.

The Testcontainers version can be overridden if needed:

```xml
<properties>
    <testcontainers.version>1.21.3</testcontainers.version>
</properties>
```

---

## Maven Plugin Configuration

Integration tests (`*IT.java`) are separated from unit tests (`*Test.java`) using Surefire and Failsafe:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <!-- Exclude IT classes from unit test phase -->
        <excludes>
            <exclude>**/*IT.java</exclude>
        </excludes>
        <argLine>-Dnet.bytebuddy.experimental=true</argLine>
        <environmentVariables>
            <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
        </environmentVariables>
    </configuration>
</plugin>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <argLine>-Dnet.bytebuddy.experimental=true</argLine>
        <environmentVariables>
            <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
        </environmentVariables>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Why these settings:**

| Setting | Reason |
|---|---|
| `**/*IT.java` excluded from Surefire | Keeps unit tests fast; ITs only run via Failsafe |
| `-Dnet.bytebuddy.experimental=true` | Allows Mockito/Byte Buddy to instrument classes on Java 23+ |
| `TESTCONTAINERS_RYUK_DISABLED=true` | Prevents Ryuk from stopping the shared MongoDB container between test classes |

---

## Abstract Base Class

All integration test classes extend `AbstractIntegrationTest`, which uses the **singleton container pattern**:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    static {
        mongoDBContainer.start();  // started once, stopped by JVM shutdown hook
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }
}
```

**Why singleton pattern (not `@Container`)?**

The `@Testcontainers` + `@Container static` approach starts and stops the container once per test *class*. When three IT classes run in sequence, the container is stopped after the first class and subsequent classes fail to connect. The singleton pattern starts the container once for the entire JVM session and relies on the Testcontainers shutdown hook to clean up on exit.

**What `@DynamicPropertySource` does:**

Overrides `spring.data.mongodb.uri` at test startup to point to the container's randomly assigned port (e.g. `mongodb://localhost:54321/test`), so Spring's MongoDB client connects to the test container instead of the real database.

---

## Writing an Integration Test

```java
class CustomerControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();  // fresh state before each test
    }

    @Test
    void createCustomer_returns201AndBody() {
        var dto = new CustomerRequestDTO();
        dto.setName("Alice");
        dto.setEmail("alice@test.com");

        var response = restTemplate.postForEntity("/api/customers", dto, CustomerResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getId()).isNotBlank();
    }
}
```

Key points:
- Extend `AbstractIntegrationTest` — no other annotations needed.
- Use `@BeforeEach` + `repository.deleteAll()` to isolate each test from leftover data.
- Use `TestRestTemplate` for HTTP calls (auto-configured for the random port).
- For list responses, use `ParameterizedTypeReference`:

```java
ResponseEntity<List<CustomerResponseDTO>> response = restTemplate.exchange(
    "/api/customers", HttpMethod.GET, null,
    new ParameterizedTypeReference<>() {}
);
```

---

## Running the Tests

```bash
# Unit tests only (fast, no Docker required)
mvn test

# Integration tests only
mvn failsafe:integration-test failsafe:verify

# Everything (unit + integration)
mvn verify
```

The first integration test run pulls the `mongo:7.0` Docker image (~600 MB). Subsequent runs reuse the locally cached image and start in a few seconds.

---

## Known Issue: Docker Desktop 4.72+ Compatibility

Docker Desktop 4.72.0 raised its minimum API version to **1.40**, but Testcontainers 1.21.x hardcodes API version **1.32** in its HTTP client. This causes all integration tests to fail with:

```
Could not find a valid Docker environment
```

### Fix: Custom Strategy + API Version Proxy

The project includes `DockerApiVersionProxyStrategy` (in `src/test/java`) — a custom `DockerClientProviderStrategy` registered via Java SPI that starts an embedded proxy. The proxy transparently rewrites every outgoing `/v1.32/` URL to `/v1.40/` before forwarding to the real Docker socket.

**Required one-time setup** — add this to `~/.testcontainers.properties`:

```properties
docker.client.strategy=com.example.shop.integration.DockerApiVersionProxyStrategy
```

If the file doesn't exist, create it. This tells Testcontainers to use the custom strategy instead of its built-in detection, which would fail with the 400 error.

> This workaround is only needed on Docker Desktop 4.72+. Once Testcontainers officially supports the newer API version, the custom strategy and this property can be removed.
