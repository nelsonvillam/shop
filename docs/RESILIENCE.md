# Resilience Patterns with Resilience4j

This document explains the circuit breaker and retry patterns added to the shop application using Resilience4j.

---

## Why resilience patterns?

A production service depends on infrastructure that can fail: the database can become slow, Redis can drop connections, the network can have transient blips. Without resilience patterns, a single slow dependency can cascade into a complete outage:

- **Without retry** — a transient network hiccup causes a request to fail permanently, even though a second attempt a moment later would have succeeded.
- **Without a circuit breaker** — every incoming request waits for the full database timeout before failing, quickly exhausting thread pools and bringing the whole application down.

Resilience4j addresses both problems.

---

## Dependencies

```groovy
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
implementation 'org.springframework.boot:spring-boot-starter-aop'   // already present
```

`resilience4j-spring-boot3` auto-configures circuit breakers, retries, and health indicators. AOP is required to apply the annotations.

---

## Circuit Breaker — product reads

### What it does

A circuit breaker monitors calls to a method and counts failures. Once the failure rate crosses a threshold, it "opens" the circuit: subsequent calls immediately invoke a **fallback method** instead of calling the failing dependency. After a wait period, it allows a small number of probe calls through to check if the dependency has recovered.

```
CLOSED (normal)  →  failure rate ≥ 50%  →  OPEN (fallback served)
     ↑                                          ↓
     └─── recovery probes succeed ─── HALF-OPEN ←─ wait 10 s
```

### Configuration (`application.properties`)

```properties
resilience4j.circuitbreaker.instances.products.sliding-window-size=10
resilience4j.circuitbreaker.instances.products.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.products.wait-duration-in-open-state=10s
resilience4j.circuitbreaker.instances.products.permitted-number-of-calls-in-half-open-state=3
resilience4j.circuitbreaker.instances.products.register-health-indicator=true
resilience4j.circuitbreaker.instances.products.ignore-exceptions=\
  com.example.shop.exception.ResourceNotFoundException
```

| Property | Value | Meaning |
|---|---|---|
| `sliding-window-size` | 10 | Monitor the last 10 calls |
| `failure-rate-threshold` | 50 | Open circuit if ≥ 50 % of the last 10 calls failed |
| `wait-duration-in-open-state` | 10 s | Stay open for 10 s before testing recovery |
| `permitted-number-of-calls-in-half-open-state` | 3 | Allow 3 probe calls through when half-open |
| `register-health-indicator` | true | Expose CB state via `/actuator/health` |
| `ignore-exceptions` | `ResourceNotFoundException` | A 404 is not an infrastructure failure — do not count it |

### Code (`ProductService`)

```java
@CircuitBreaker(name = "products", fallbackMethod = "findAllFallback")
@Cacheable("products")
public List<ProductResponseDTO> findAll() {
    return productRepository.findAll().stream()
            .map(productMapper::toResponse)
            .collect(Collectors.toList());
}

List<ProductResponseDTO> findAllFallback(Throwable t) {
    log.warn("Products circuit open — returning empty list: {}", t.getMessage());
    return Collections.emptyList();
}

@CircuitBreaker(name = "products", fallbackMethod = "findByIdFallback")
@Cacheable(value = "products", key = "#id")
public ProductResponseDTO findById(String id) {
    return productRepository.findById(id)
            .map(productMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
}

ProductResponseDTO findByIdFallback(String id, Throwable t) {
    log.warn("Products circuit open for id={}: {}", id, t.getMessage());
    throw new ServiceUnavailableException("Product service temporarily unavailable: " + id, t);
}
```

### Fallback behaviour

| Method | Circuit CLOSED | Circuit OPEN |
|---|---|---|
| `findAll` | Returns full product list | Returns empty list (`[]`) — HTTP 200 |
| `findById` | Returns the product (or 404) | Throws `ServiceUnavailableException` — HTTP 503 |

`findAll` degrades gracefully because an empty catalogue is better than an error page. `findById` returns 503 because returning "product not found" when the product exists and the database is simply unavailable would be misleading.

### Aspect ordering with `@Cacheable`

The circuit breaker aspect (order `Integer.MAX_VALUE - 3`) runs **before** the cache aspect (order `Integer.MAX_VALUE - 2`). This means:

- **Circuit CLOSED + cache hit** — cache returns the value; the circuit breaker never sees a call.
- **Circuit CLOSED + cache miss** — the method runs; failures are counted by the circuit breaker.
- **Circuit OPEN** — the fallback is invoked immediately; the cache is never consulted.

---

## Retry — `placeOrder`

### What it does

A retry automatically re-invokes a method when it throws a matching exception. Unlike the circuit breaker, it does not track failure rates — it simply tries again up to a configured maximum.

### Why `placeOrder` specifically?

`placeOrder` performs a MongoDB transaction: it decrements stock on one or more products and saves a new order document. A transient network hiccup during the commit could fail the request even though the database is healthy. Retrying gives the operation a second chance before surfacing the error to the caller.

### Configuration

```properties
resilience4j.retry.instances.placeOrder.max-attempts=3
resilience4j.retry.instances.placeOrder.wait-duration=200ms
resilience4j.retry.instances.placeOrder.retry-exceptions=\
  com.mongodb.MongoException,\
  org.springframework.dao.TransientDataAccessException
```

| Property | Value | Meaning |
|---|---|---|
| `max-attempts` | 3 | Try up to 3 times in total |
| `wait-duration` | 200 ms | Wait 200 ms between attempts |
| `retry-exceptions` | MongoDB exceptions only | Only retry on transient infrastructure errors |

`retry-exceptions` is a **whitelist** — only `MongoException` and `TransientDataAccessException` trigger a retry. Domain exceptions such as `ResourceNotFoundException`, `InsufficientStockException`, and the simulated `RuntimeException` are **never retried**.

### Code (`OrderService`)

```java
@Retry(name = "placeOrder")
@Observed(name = "order.place", contextualName = "place-order")
@Transactional
public OrderResponseDTO placeOrder(OrderRequestDTO dto, boolean simulateFail) { ... }
```

### Interaction with `@Transactional`

The `@Retry` aspect (order `Integer.MAX_VALUE - 4`) runs **outside** the `@Transactional` aspect (order `Integer.MAX_VALUE`). The call chain is:

```
Retry → Observed → Transactional → method body
```

Each retry attempt starts a **fresh transaction**. If the first attempt's transaction rolled back due to a transient error, the retry begins with a clean database state. Stock that was decremented in a rolled-back transaction is automatically restored.

---

## Health indicator

Because `register-health-indicator=true` is set for the `products` circuit breaker, its state is visible in the Actuator health endpoint:

```bash
curl http://localhost:8081/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "products": {
          "status": "UP",
          "details": {
            "failureRate": "0.0%",
            "slowCallRate": "0.0%",
            "state": "CLOSED"
          }
        }
      }
    }
  }
}
```

If the circuit opens, `status` changes to `DOWN` or `CIRCUIT_OPEN`, which is useful for alerting in a monitoring stack.

---

## Testing

`ProductResilienceIT` tests fallback behaviour without needing to actually bring MongoDB down. It uses `CircuitBreakerRegistry` to force the circuit into the OPEN state programmatically:

```java
@Autowired private ProductService productService;
@Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

@AfterEach
void resetCircuitBreaker() {
    circuitBreakerRegistry.circuitBreaker("products").reset();
}

@Test
void findAll_whenCircuitOpen_returnsFallbackEmptyList() {
    circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

    List<ProductResponseDTO> result = productService.findAll();

    assertThat(result).isEmpty();
}

@Test
void findById_whenCircuitOpen_viaHttp_returns503() {
    ProductResponseDTO product = createProduct("Tablet", 499.99);
    circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

    ResponseEntity<Void> response = restTemplate.getForEntity(
            "/api/products/" + product.getId(), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
}
```

`reset()` in `@AfterEach` restores the circuit to CLOSED so subsequent integration tests are not affected.

---

## Summary

| Pattern | Applied to | Behaviour when triggered |
|---|---|---|
| Circuit Breaker | `ProductService.findAll` | Returns empty list |
| Circuit Breaker | `ProductService.findById` | Returns HTTP 503 |
| Retry | `OrderService.placeOrder` | Up to 3 attempts, 200 ms apart |

| Setting | Value |
|---|---|
| CB sliding window | 10 calls |
| CB failure threshold | 50 % |
| CB open duration | 10 s |
| Retry max attempts | 3 |
| Retry wait | 200 ms |
| Retry triggers | `MongoException`, `TransientDataAccessException` only |
