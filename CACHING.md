# Caching in Spring Boot

## What is caching?

Caching stores the result of an expensive operation (like a database query) in fast memory so that subsequent calls can return the stored result without repeating the work.

```
First call:   Client → Service → MongoDB  (cache miss → stores result)
Second call:  Client → Service            (cache hit  → returns stored result)
```

The tradeoff is **speed vs. freshness**: cached data is fast but may be stale until the cache is invalidated.

---

## How Spring Cache works

Spring Cache is an abstraction layer. You annotate your methods; Spring intercepts calls and decides whether to hit the real method or return a cached value. The underlying store (in-memory, Redis, etc.) is swappable without changing your code.

The default store when no external provider is configured is a `ConcurrentHashMap` — zero infrastructure, useful for proof-of-concept and low-traffic scenarios.

---

## Step-by-step setup

### 1. Add the dependency

**Gradle:**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-cache'
```

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

---

### 2. Enable caching

Add `@EnableCaching` to your main application class:

```java
@SpringBootApplication
@EnableCaching
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
```

---

### 3. Annotate your service methods

#### `@Cacheable` — return cached value on subsequent calls

```java
@Cacheable("products")
public List<ProductResponseDTO> findAll() {
    // Use collect(toList()) — NOT .toList(). See "Serialization pitfalls" below.
    return productRepository.findAll().stream()
            .map(productMapper::toResponse)
            .collect(Collectors.toList());
}

@Cacheable(value = "products", key = "#id")
public ProductResponseDTO findById(String id) {
    return productRepository.findById(id)
            .map(productMapper::toResponse)
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));
}
```

- `value` is the cache name (a logical bucket).
- `key` is a Spring Expression Language (SpEL) expression. For no-arg methods the default key is `SimpleKey.EMPTY`; for methods with arguments you should always set an explicit key.

#### `@CacheEvict` — clear cached entries on mutation

```java
@CacheEvict(value = "products", allEntries = true)
public ProductResponseDTO create(ProductRequestDTO dto) { ... }

@CacheEvict(value = "products", allEntries = true)
public ProductResponseDTO update(String id, ProductRequestDTO dto) { ... }

@CacheEvict(value = "products", allEntries = true)
public void delete(String id) { ... }
```

`allEntries = true` clears every entry in the named cache. Use this when a mutation (create, delete) could invalidate the list-all result as well as individual entries.

---

## Cache key strategy

| Method signature | Recommended key | Why |
|---|---|---|
| `findAll()` | *(default)* `SimpleKey.EMPTY` | No arguments; Spring assigns the empty key automatically |
| `findById(String id)` | `key = "#id"` | One entry per product; each ID maps to its own slot |
| `findPaged(int page, int size, ...)` | *(skip caching)* | Too many key combinations; cache would fill up and rarely hit |
| `search(String name)` | *(skip caching)* | Dynamic input; same problem as paged |

---

## Enabling cache logs

Add to `application.properties` to see hits and misses in the console:

```properties
logging.level.org.springframework.cache=TRACE
```

Example output:
```
Cache entry for key [] found in cache 'products'          ← hit
No cache entry for key [abc123] in cache 'products'       ← miss
Clearing all cache entries in cache 'products'            ← evict
```

---

## Caching in Kubernetes (multi-pod deployments)

The in-memory cache (`ConcurrentHashMap`) **breaks in multi-pod environments**. Each pod has its own isolated cache, so eviction on one pod does not propagate to the others:

```
Pod A  [cache: Laptop $999]   ← evicts after update
Pod B  [cache: Laptop $999]   ← still stale
Pod C  [cache: Laptop $999]   ← still stale
```

The solution is a **shared external cache** that all pods connect to:

```
Pod A ──┐
Pod B ──┼──► Redis (shared cache) ──► MongoDB
Pod C ──┘
```

### Switching to Redis

Keep `spring-boot-starter-cache` (it activates `CacheAutoConfiguration`) and add the Redis starter alongside it:

**Gradle:**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-cache'
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**`application.properties`:**
```properties
spring.data.redis.host=redis-service   # Kubernetes Service name, or localhost for local dev
spring.data.redis.port=6379
spring.cache.type=redis
```

Spring Boot auto-configures a `RedisCacheManager` when both starters are on the classpath and `spring.cache.type=redis` is set. All `@Cacheable`/`@CacheEvict` annotations stay exactly as they are.

### Serialization configuration

The in-memory cache stores Java objects directly. Redis must serialize them to bytes. The correct approach is to copy the application's `ObjectMapper` and add default typing so that Jackson embeds `@class` type information in the stored JSON — without this, deserialization returns raw `LinkedHashMap` instances instead of your DTO classes.

```java
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheConfiguration redisCacheConfiguration(ObjectMapper objectMapper) {
        // Copy the app's ObjectMapper so its modules and customizations are inherited.
        // A copy is used to avoid adding default typing to the ObjectMapper that
        // Spring MVC uses — that would pollute REST responses with @class fields.
        ObjectMapper cacheMapper = objectMapper.copy()
                .activateDefaultTypingAsProperty(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType(Object.class)
                                .build(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        "@class"
                );

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(cacheMapper)
                        )
                );
    }
}
```

With this configuration, a cached `ProductResponseDTO` is stored in Redis as:
```json
{"@class":"com.example.shop.dto.ProductResponseDTO","id":"abc","name":"Laptop","price":999.99,"stock":10}
```

And a cached `List<ProductResponseDTO>` as:
```json
["java.util.ArrayList",[{"@class":"com.example.shop.dto.ProductResponseDTO","id":"abc",...}]]
```

### Serialization pitfalls

#### 1. `Stream.toList()` vs `Collectors.toList()`

`Stream.toList()` (Java 16+) returns `java.util.ImmutableCollections$ListN` — a package-private JDK class that Jackson cannot instantiate during deserialization. This causes a `SerializationException` on every cache read, which Spring re-throws and results in a 500 error.

Always use `collect(Collectors.toList())` for cached methods that return a list. It returns an `ArrayList`, which has a public no-arg constructor Jackson can use.

```java
// Breaks with Redis — ImmutableCollections$ListN cannot be deserialized
return stream.toList();

// Works — ArrayList has a public no-arg constructor
return stream.collect(Collectors.toList());
```

#### 2. Do not pass the plain app `ObjectMapper` without default typing

`GenericJackson2JsonRedisSerializer(objectMapper)` without `activateDefaultTypingAsProperty` stores plain JSON without `@class` type fields. On read, Jackson deserializes objects as `LinkedHashMap` instead of your DTO class, causing a `ClassCastException` at the call site.

Always use `objectMapper.copy()` with `activateDefaultTypingAsProperty` as shown in the configuration above.

### Why TTL is mandatory in Redis

With an in-memory cache, entries vanish when the process restarts — memory is naturally bounded. In Redis, entries persist indefinitely unless a TTL is set. Without it:

- Deleted records stay in Redis forever
- Redis memory grows unboundedly
- A pod crash that skips `@CacheEvict` leaves permanent stale entries

Always set a TTL as a safety net, even when eviction is also wired up.

### Running Redis in Kubernetes

A minimal Redis Deployment and ClusterIP Service:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports:
            - containerPort: 6379
---
apiVersion: v1
kind: Service
metadata:
  name: redis-service
spec:
  selector:
    app: redis
  ports:
    - port: 6379
```

`spring.data.redis.host=redis-service` resolves via Kubernetes internal DNS.

In production, prefer a **managed Redis** (AWS ElastiCache, GCP Memorystore, Azure Cache for Redis) over self-hosting in the cluster — you get replication, automatic failover, and backups out of the box.

### In-memory vs. Redis at a glance

| | In-memory (`ConcurrentMap`) | Redis |
|---|---|---|
| Multi-pod safe | No | Yes |
| Eviction propagates across pods | No | Yes |
| Survives pod restart | No | Yes |
| Infrastructure needed | None | Redis instance |
| Code changes to migrate | — | Dependency + config + `CacheConfig` |
| Best for | Local dev / single instance | Any production deployment |

---

## What NOT to cache

| Scenario | Why |
|---|---|
| Methods with many dynamic parameters (search, pagination) | Key space explodes; hit rate stays near zero |
| Mutable data with strict consistency requirements | Stale reads can cause correctness bugs |
| Cheap operations (simple lookups on small tables) | Cache overhead outweighs the gain |
| Methods that return `void` | Nothing to cache |

---

## Integration testing

### Testcontainers setup

With Redis as the cache backend, integration tests need a real Redis instance. Use Testcontainers' `GenericContainer` — no extra module required beyond the core Testcontainers dependency already pulled in by other containers.

Start it alongside the existing MongoDB container in the shared base class, and override the Redis connection properties with `@DynamicPropertySource`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

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
```

Clear both the database and the Redis cache in `@BeforeEach` to keep tests isolated:

```java
@Autowired private CacheManager cacheManager;

@BeforeEach
void setUp() {
    productRepository.deleteAll();
    Objects.requireNonNull(cacheManager.getCache("products")).clear();
}
```

### What to test

The most reliable way to test a cache is to **prove stale data is served** — bypass the service and write directly to the database, then assert the HTTP endpoint still returns the old value. This proves the cache is being read, not the database:

```java
// 1. Warm the cache via the HTTP endpoint
getAllProducts();

// 2. Write directly to MongoDB (bypasses the service → no @CacheEvict fires)
var product = productRepository.findAll().get(0);
product.setName("Modified Directly");
productRepository.save(product);

// 3. Cache hit — stale value is returned, not the DB value
assertThat(getAllProducts().get(0).getName()).isEqualTo("Original Name");
```

To verify eviction, call a mutating endpoint through HTTP and assert fresh data is returned:

```java
restTemplate.exchange("/api/products/" + id, HttpMethod.PUT, new HttpEntity<>(update), ...);
assertThat(getAllProducts().get(0).getName()).isEqualTo("New Name");
```

Inspect the `CacheManager` directly to assert that entries exist or were evicted:

```java
@Autowired CacheManager cacheManager;

// no-arg methods use SimpleKey.EMPTY as the cache key
assertThat(cacheManager.getCache("products").get(SimpleKey.EMPTY)).isNotNull();

// by-id entries are keyed by the ID string
assertThat(cacheManager.getCache("products").get(productId)).isNotNull();

// after eviction the entry is gone
assertThat(cacheManager.getCache("products").get(productId)).isNull();
```
