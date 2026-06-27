# Observability

This document explains the observability features added to the shop application: structured logging and Prometheus metrics.

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

To visualize the metrics locally, add Prometheus and Grafana to `docker-compose.yml`:

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
