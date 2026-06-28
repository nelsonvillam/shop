# Distributed Tracing

This document explains the distributed tracing layer added to the shop application using Micrometer Tracing, Brave, and Zipkin.

---

## What is distributed tracing?

Structured logs tell you what happened. Metrics tell you aggregated counts. Distributed tracing tells you **how a single request flowed through the system** — which methods were called, in what order, and how long each one took.

Every request is assigned a **trace ID**. Every operation within that request (a controller call, a service method, a database query) is a **span**. Spans form a tree; the trace ID links them all together.

---

## How it is implemented

Three libraries work together:

| Library | Role |
|---|---|
| `micrometer-tracing-bridge-brave` | Spring Boot's tracing abstraction backed by the Brave tracer |
| `zipkin-reporter-brave` | Ships completed spans to a Zipkin server over HTTP |
| Spring Boot Actuator | Exposes tracing configuration and automatically instruments Spring MVC, MongoDB, and Redis |

---

## Automatic instrumentation

Spring Boot auto-configures tracing for the entire request lifecycle without any code changes:

- **HTTP layer** — every inbound HTTP request starts a new trace span. The trace ID and span ID are added to every log line automatically.
- **MongoDB** — every query issued through `MongoTemplate` or a `MongoRepository` is wrapped in a child span.
- **Redis** — every cache operation (get, put, evict) is wrapped in a child span.

This means a single `GET /api/orders/{id}/detail` request automatically produces spans for the HTTP handler, the MongoDB aggregation, and any Redis cache lookups — with no annotation required.

---

## Trace IDs in logs

The log pattern includes `traceId` and `spanId` from the MDC (Mapped Diagnostic Context):

```properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%X{traceId:-},%X{spanId:-}] %-40logger{39} : %msg%n
```

Example output for a single request:

```
20:11:03.142 INFO  [abc123def456,abc123def456] c.e.shop.service.OrderService  : Order placed: id=xyz789 ...
20:11:03.143 INFO  [abc123def456,11223344aabb] c.e.shop.service.ProductService: Product updated: id=...
```

All lines with the same `traceId` (`abc123def456`) belong to the same incoming request, even if they come from different services or threads.

---

## Custom span with `@Observed`

The `placeOrder` method is instrumented with an explicit span name using `@Observed`:

```java
@Observed(name = "order.place", contextualName = "place-order")
@Transactional
public OrderResponseDTO placeOrder(OrderRequestDTO dto, boolean simulateFail) { ... }
```

This creates a child span named `place-order` every time `placeOrder` is called. In Zipkin you can see exactly how long the transactional portion of placing an order takes, separate from HTTP overhead.

`@Observed` requires AOP, which is already on the classpath (`spring-boot-starter-aop`). No additional configuration is needed.

---

## Viewing traces in Zipkin

Zipkin is included in `docker-compose.yml`:

```yaml
zipkin:
  image: openzipkin/zipkin
  ports:
    - "9411:9411"
```

The application is configured to send spans to Zipkin:

```properties
management.tracing.sampling.probability=1.0
management.zipkin.tracing.endpoint=${ZIPKIN_URL:http://localhost:9411}/api/v2/spans
```

`sampling.probability=1.0` means **every request** is traced. In a high-traffic production system you would lower this (e.g. `0.1` = 10 %) to reduce overhead.

**Open the Zipkin UI:**

```
http://localhost:9411
```

1. Click **Find Traces**
2. Select the `shop` service
3. Click a trace to expand the span tree

You will see spans like:

```
► GET /api/orders/{id}/detail          (root span, HTTP layer)
  ► place-order                        (@Observed span, OrderService)
    ► mongodb.aggregate                (auto-instrumented, MongoTemplate)
    ► redis.get                        (auto-instrumented, cache lookup)
```

---

## Sampling configuration

| `sampling.probability` | Effect |
|---|---|
| `1.0` | Every request is traced (dev / testing) |
| `0.1` | 1 in 10 requests is traced (low-traffic production) |
| `0.01` | 1 in 100 requests is traced (high-traffic production) |

For local development and the course environment, `1.0` is used so every request appears in Zipkin.

---

## Summary

| Concept | Detail |
|---|---|
| Tracer | Brave (via `micrometer-tracing-bridge-brave`) |
| Exporter | Zipkin (via `zipkin-reporter-brave`) |
| Sampling rate | 100 % (`management.tracing.sampling.probability=1.0`) |
| Automatic spans | HTTP requests, MongoDB queries, Redis operations |
| Custom span | `@Observed(name = "order.place")` on `OrderService.placeOrder` |
| Log correlation | `traceId` and `spanId` injected into every log line via MDC |
| Zipkin UI | `http://localhost:9411` |
