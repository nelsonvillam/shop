# Observability

This document explains the observability features added to the shop application: structured logging and Prometheus metrics.

For how these metrics are collected and visualized across the whole system — `gateway` and `ping-service` exposing their own `/actuator/prometheus`, Prometheus scraping all three services on Kubernetes, and Grafana dashboards on top — see [PROMETHEUS_METRICS_COLLECTION.md](PROMETHEUS_METRICS_COLLECTION.md).

---

## Structured Logging

Logging is added to all three service classes (`ProductService`, `CustomerService`, `OrderService`) using the SLF4J API via Lombok's `@Slf4j` annotation.

### Log levels used

| Level | When |
|---|---|
| `INFO` | A resource was successfully created, updated, or deleted |
| `WARN` | A resource was not found, or a business rule was violated |

### Examples

```
INFO  Product created: id=abc123 name=Laptop Pro
INFO  Order placed: id=xyz789 customerId=abc123 total=1299.99
INFO  Order status updated: id=xyz789 status=CONFIRMED
WARN  Product not found: bad-id
WARN  Place order failed — insufficient stock for product: Laptop
WARN  Order creation failed — customer not found: 000000000000000000000000
```

### Why structured logging matters

Without logs, debugging a production failure means guessing. With logs you can:
- See exactly which resource caused a 404
- Track the full lifecycle of an order (created → confirmed → shipped)
- Identify which products run out of stock most frequently
- Correlate errors with specific customer or order IDs

### How to view logs

**Locally (Docker Compose):**
```bash
docker-compose logs -f shop
```

**On EC2:**
```bash
ssh -i shop-key.pem ubuntu@<ec2-ip> 'cd ~/shop && docker-compose logs -f shop'
```

---

## Prometheus Metrics

[Micrometer](https://micrometer.io/) is Spring Boot's metrics facade. The `micrometer-registry-prometheus` dependency formats those metrics in the Prometheus text format, exposed at `/actuator/prometheus`.

### Endpoints

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Application health status (UP/DOWN) |
| `GET /actuator/metrics` | List of all available metric names |
| `GET /actuator/metrics/{name}` | Value of a specific metric |
| `GET /actuator/prometheus` | All metrics in Prometheus scrape format |

### What metrics are exposed

| Category | Examples |
|---|---|
| HTTP requests | Request count, latency per endpoint, error rate |
| JVM | Heap memory used, GC pause time, thread count |
| MongoDB | Connection pool size, active connections |
| Redis | Commands executed, hit/miss ratio |
| System | CPU usage, file descriptors |

### Example — query a specific metric

```bash
# Number of HTTP requests to /api/products
curl http://localhost:8081/actuator/metrics/http.server.requests

# JVM heap memory used
curl http://localhost:8081/actuator/metrics/jvm.memory.used
```

### Custom metrics

Beyond the built-in metrics, you can register your own using Micrometer's API. The shop application includes a custom counter that tracks how many orders have been successfully placed.

**Implementation in `OrderService`:**

```java
private final Counter ordersPlacedCounter;

public OrderService(..., MeterRegistry meterRegistry) {
    this.ordersPlacedCounter = Counter.builder("shop.orders.placed")
            .description("Total number of orders successfully placed")
            .register(meterRegistry);
}
```

The counter is incremented only when `placeOrder` fully succeeds — failed orders (insufficient stock, customer not found) do not count.

```java
ordersPlacedCounter.increment();
```

**Query it via Actuator:**

```bash
curl http://localhost:8081/actuator/metrics/shop.orders.placed
```

**In Prometheus format** (`/actuator/prometheus`):

```
shop_orders_placed_total{application="shop"} 3.0
```

**Micrometer metric types:**

| Type | Use case | Example |
|---|---|---|
| `Counter` | Things that only go up | Orders placed, errors thrown |
| `Gauge` | Current value that goes up and down | Active connections, queue size |
| `Timer` | Latency of an operation | External API call duration |
| `DistributionSummary` | Distribution of a value | Request payload size |

**Testing custom metrics:**

In unit tests, use `SimpleMeterRegistry` — an in-memory registry that works without any infrastructure:

```java
orderService = new OrderService(..., new SimpleMeterRegistry());
```

---

### Declarative metrics with `@TrackCall`

The shop application also uses a custom annotation backed by Spring AOP to count endpoint calls without touching controller logic.

**How it works:**

1. `@TrackCall` is a marker annotation (`@Retention(RUNTIME)`) placed on controller methods.
2. `MetricsAspect` is an `@Aspect` component that intercepts every method annotated with `@TrackCall`.
3. Before delegating to the real method, the aspect increments a counter tagged with the controller class and method name.

**The annotation** (`metrics/TrackCall.java`):

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackCall {
}
```

**The aspect** (`metrics/MetricsAspect.java`):

```java
@Aspect
@Component
@RequiredArgsConstructor
public class MetricsAspect {

    private final MeterRegistry meterRegistry;

    @Around("@annotation(TrackCall)")
    public Object track(ProceedingJoinPoint pjp) throws Throwable {
        String className  = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();

        meterRegistry.counter("shop.api.calls",
                "class",  className,
                "method", methodName)
                .increment();

        return pjp.proceed();
    }
}
```

**Usage on a controller method:**

```java
@TrackCall
@GetMapping
public List<ProductResponseDTO> findAll(...) { ... }
```

All controller methods across `ProductController`, `CustomerController`, and `OrderController` are annotated.

**In Prometheus format** (`/actuator/prometheus`):

```
shop_api_calls_total{application="shop",class="ProductController",method="findAll"} 12.0
shop_api_calls_total{application="shop",class="OrderController",method="place"} 3.0
```

**Useful PromQL queries:**

```promql
# total calls across all endpoints
sum(shop_api_calls_total)

# calls broken down per method, sorted by most called
sort_desc(sum by (method) (shop_api_calls_total))

# calls for a specific controller
sum by (method) (shop_api_calls_total{class="OrderController"})
```

**`@TrackCall` vs manual counter — when to use each:**

| Approach | Use when |
|---|---|
| `@TrackCall` (AOP) | You want to count every invocation, regardless of outcome. Zero coupling to the controller. |
| Manual `Counter` in service | You want to count only successful outcomes (e.g. only orders that actually complete). |

---

### Configuration

Metrics are configured in `application.properties`:

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.health.show-details=when-authorized
management.metrics.tags.application=shop
```

- `management.endpoints.web.exposure.include` — controls which Actuator endpoints are publicly accessible
- `management.metrics.tags.application=shop` — adds an `application="shop"` label to every metric, useful when multiple services report to the same Prometheus instance

---

## Setting up Prometheus + Grafana (optional)

To visualize the metrics locally, add Prometheus and Grafana to `docker-compose.yml`. For the Kubernetes deployment — a real Prometheus + Grafana pair, with Prometheus scraping `shop`, `gateway`, and `ping-service` individually per pod and Grafana's datasource pre-provisioned — see [PROMETHEUS_METRICS_COLLECTION.md](PROMETHEUS_METRICS_COLLECTION.md) instead.

### 1. Create a Prometheus config file

```bash
nano ~/shop/prometheus.yml
```

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: shop
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['shop:8080']
```

### 2. Add Prometheus and Grafana to docker-compose.yml

```yaml
  prometheus:
    image: prom/prometheus:latest
    restart: unless-stopped
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    restart: unless-stopped
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
```

### 3. Start and open Grafana

```bash
docker-compose up -d
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (login: `admin` / `admin`)

In Grafana, add Prometheus as a data source (`http://prometheus:9090`) and import dashboard ID **4701** (JVM Micrometer dashboard) for an instant overview of the application.

---

## Summary

| Feature | Technology | Endpoint / Output |
|---|---|---|
| Structured logging | SLF4J + Logback | `docker-compose logs -f shop` |
| Health check | Spring Boot Actuator | `GET /actuator/health` |
| Metrics browser | Spring Boot Actuator | `GET /actuator/metrics` |
| Prometheus scrape | Micrometer + Prometheus registry | `GET /actuator/prometheus` |
| Custom metric | Micrometer Counter | `GET /actuator/metrics/shop.orders.placed` |
| AOP endpoint counter | `@TrackCall` + Spring AOP | `GET /actuator/metrics/shop.api.calls` |
