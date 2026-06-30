# API Gateway

This document explains what an API gateway is, why this project uses one, and how Spring Cloud Gateway routes traffic and validates JWTs in practice.

---

## What is an API Gateway?

An **API gateway** is a single entry point that sits in front of all your backend services. Every external request hits the gateway first — the gateway then decides where to send it.

```
Client
  │
  ▼
┌─────────────────────────────────┐
│           API Gateway           │  ← single public entry point
│   - authentication              │
│   - routing                     │
│   - header manipulation         │
└─────┬───────────────┬───────────┘
      │               │
      ▼               ▼
 ┌─────────┐    ┌──────────────┐
 │  shop   │    │ ping-service │
 └─────────┘    └──────────────┘
```

Without a gateway, every service would have to implement authentication, CORS, rate limiting, and logging individually. The gateway centralises those concerns.

---

## Why Spring Cloud Gateway?

Spring Cloud Gateway is a reactive API gateway built on top of **Spring WebFlux** (non-blocking, event-loop based). Key features used in this project:

| Feature | What it does |
|---|---|
| Route predicates | Match incoming requests by path, method, header, or host |
| Global filters | Run code on every request before it reaches any service |
| `GlobalFilter` interface | Hook into the filter chain to add custom logic (JWT validation here) |

Other popular alternatives: Kong, Nginx, AWS API Gateway, Traefik.

---

## How Routing Works

### The routes in `application.yml`

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: ping-service
          uri: http://ping-service:8080
          predicates:
            - Path=/ping/**

        - id: shop
          uri: http://shop:80
          predicates:
            - Path=/**
```

Spring Cloud Gateway evaluates routes **in order, top to bottom**. The first route whose predicate matches wins.

#### Route: `ping-service`

```yaml
- id: ping-service
  uri: http://ping-service:8080
  predicates:
    - Path=/ping/**
```

- **Predicate:** `Path=/ping/**` — matches any path that starts with `/ping/` or is exactly `/ping`
- **`**`** is an Ant-style wildcard that matches zero or more path segments
- **`uri`:** `http://ping-service:8080` — `ping-service` resolves via Kubernetes DNS to the `ping-service` ClusterIP Service, which forwards to port 8080 on the pod

#### Route: `shop` (catch-all)

```yaml
- id: shop
  uri: http://shop:80
  predicates:
    - Path=/**
```

- **Predicate:** `Path=/**` — matches everything
- **Must come last** — if it were first, it would match every request before the ping-service route could be evaluated
- **`uri`:** `http://shop:80` — the shop Service listens on port 80 and forwards to port 8080 on the pod

### Order matters

```
Request: GET /ping
  ├── Does /ping match /ping/**  ?  YES  →  forward to ping-service:8080
  └── (shop route never evaluated)

Request: GET /api/products
  ├── Does /api/products match /ping/**  ?  NO
  └── Does /api/products match /**       ?  YES  →  forward to shop:80
```

---

## How JWT Authentication Works

### The filter chain

Spring Cloud Gateway processes every request through a **filter chain**. Filters can:
- Inspect or modify the request before it reaches the backend
- Inspect or modify the response before it goes back to the client
- Short-circuit the chain and return a response directly (e.g. `401 Unauthorized`)

`JwtAuthenticationFilter` implements `GlobalFilter` — it runs on **every** request, for every route.

```
Incoming request
      │
      ▼
┌─────────────────────────────┐
│   JwtAuthenticationFilter   │  ← runs first, on every request
│                             │
│  1. Is this a public path?  │──── YES ──▶ pass through (no token check)
│                             │
│  2. Extract Bearer token    │
│     from Authorization      │
│     header                  │
│                             │
│  3. Validate token          │──── INVALID ──▶ return 401
│     (HMAC-SHA256, expiry)   │
│                             │
│  4. Add X-User-Name header  │
│     with the username from  │
│     the token claims        │
└─────────────┬───────────────┘
              │ VALID
              ▼
       Route to backend
       (shop or ping-service)
```

### Public paths (no token required)

The filter checks the request path against a set of public prefixes:

```java
private static final Set<String> PUBLIC_PREFIXES = Set.of(
    "/auth/",
    "/actuator",
    "/swagger-ui",
    "/v3/api-docs",
    "/swagger-ui.html",
    "/ping"
);
```

| Path | Reason it's public |
|---|---|
| `/auth/` | Login and register — can't require a token you don't have yet |
| `/actuator` | Health checks — needed by Kubernetes probes without credentials |
| `/swagger-ui`, `/v3/api-docs` | API documentation — convenient to browse without logging in |
| `/ping` | Test microservice — exists specifically to test routing without auth overhead |

If the request path starts with any of these prefixes, the filter calls `chain.filter(exchange)` immediately — skipping token validation entirely.

### Token validation

For protected paths:

1. **Extract** the `Authorization` header — expected format: `Bearer <token>`
2. **Verify signature** using `JWT_SECRET` (the same secret used by the shop service to sign tokens). Algorithm: HMAC-SHA256
3. **Check expiry** — tokens are valid for 15 minutes
4. **Extract username** from the `sub` (subject) claim
5. **Add `X-User-Name` header** — the backend (shop) reads this to know which user made the request without re-validating the token

If any step fails (missing header, bad signature, expired), the filter writes `401 Unauthorized` and the request never reaches the backend.

### The `X-User-Name` header

```
Client ──► Gateway ──[X-User-Name: alice]──► Shop
```

The shop service trusts this header completely — it never validates the JWT itself. This is the correct pattern: authentication is a gateway concern, the backend focuses on business logic.

> This only works because shop is not publicly reachable. In Kubernetes, shop's Service is `ClusterIP` — only reachable from inside the cluster. A client can't call shop directly and forge the `X-User-Name` header.

---

## Request lifecycle (full trace)

Here is what happens end-to-end for a protected request:

```
curl -H "Authorization: Bearer <token>" http://localhost:9090/api/products
```

```
1. curl → nginx Ingress Controller (port 9090 via port-forward)
   - Ingress rule: host=* → service/gateway port 80

2. nginx → gateway Service (ClusterIP, port 80)
   - Service selector: app=gateway → gateway pod port 8080

3. gateway pod receives request
   ├── JwtAuthenticationFilter runs
   │   ├── /api/products not in PUBLIC_PREFIXES
   │   ├── Extract "Bearer <token>" from Authorization header
   │   ├── Verify HMAC-SHA256 signature with JWT_SECRET
   │   ├── Check expiry (< 15 min)
   │   ├── Extract username "alice" from sub claim
   │   └── Add header: X-User-Name: alice
   │
   └── Route matching
       ├── /api/products matches /** → shop route
       └── Forward to http://shop:80

4. shop Service (ClusterIP, port 80)
   - Selector: app=shop → one of the two shop pods (round-robin)
   - Pod port: 8080

5. shop pod handles request
   - Reads X-User-Name: alice from headers
   - Queries MongoDB, returns response

6. Response travels back through the same chain
   gateway → nginx → curl
```

---

## Request lifecycle (public path)

```
curl http://localhost:9090/ping
```

```
1. curl → nginx → gateway pod

2. JwtAuthenticationFilter runs
   ├── /ping IS in PUBLIC_PREFIXES
   └── Skip validation → chain.filter(exchange)

3. Route matching
   ├── /ping matches /ping/** → ping-service route
   └── Forward to http://ping-service:8080

4. ping-service pod returns "pong - GET"

5. Response travels back: ping-service → gateway → nginx → curl
```

---

## Kubernetes DNS resolution

When the gateway config says `uri: http://ping-service:8080`, how does `ping-service` resolve to an IP?

Kubernetes runs a DNS server (CoreDNS) inside the cluster. Every Service gets an automatic DNS entry:

```
<service-name>.<namespace>.svc.cluster.local
```

Within the same namespace (`shop`), the short name `ping-service` is enough — CoreDNS expands it automatically. The DNS name resolves to the Service's `ClusterIP`, which load-balances across all healthy pods matching its selector.

```
gateway pod
  │
  │  http://ping-service:8080
  │
  ▼
CoreDNS lookup: ping-service.shop.svc.cluster.local → 10.96.100.115
  │
  ▼
ClusterIP 10.96.100.115:8080
  │
  ▼
ping-service pod (8080)
```

---

## How the gateway knows which port to use

```yaml
uri: http://ping-service:8080
```

The port `8080` in the `uri` is the **target port** — the port the application inside the pod is actually listening on.

This is distinct from:
- The Service's `port` (what other services use to reach it — `8080` in ping-service's case)
- The Service's `targetPort` (what the Service forwards to on the pod — also `8080`)
- `containerPort` in the Deployment (documentation only — has no functional effect on routing)

For the shop service, the route uses port `80`:
```yaml
uri: http://shop:80
```
Because shop's Service `spec.port` is `80` (mapped to `targetPort: 8080` inside the pod).

---

## JWT token structure

A JWT has three Base64-encoded parts separated by dots:

```
header.payload.signature
```

### Header

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

`HS256` = HMAC-SHA256. A symmetric algorithm — the same secret is used to sign and to verify.

### Payload (claims)

```json
{
  "sub": "alice",
  "iat": 1719000000,
  "exp": 1719000900
}
```

| Claim | Meaning |
|---|---|
| `sub` | Subject — the username |
| `iat` | Issued at — Unix timestamp |
| `exp` | Expiry — `iat + 900` seconds (15 minutes) |

### Signature

```
HMAC-SHA256(
  base64(header) + "." + base64(payload),
  JWT_SECRET
)
```

The gateway verifies the signature using the same `JWT_SECRET` stored in the `shop-secret` Kubernetes Secret (synced from AWS Secrets Manager by ESO). If even one character of the header or payload is tampered with, the signature won't match and the token is rejected.

---

## Why the gateway holds `JWT_SECRET`

Both shop (which **signs** tokens at login) and gateway (which **verifies** tokens on every request) need the same secret. They both read `JWT_SECRET` from the `shop-secret` Kubernetes Secret:

```
AWS Secrets Manager (shop/jwt-secret)
  │
  ▼
ESO ExternalSecret (shop-secret)
  │
  ▼
Kubernetes Secret (shop-secret)
  ├── JWT_SECRET → shop pod env var    (signs tokens)
  └── JWT_SECRET → gateway pod env var (verifies tokens)
```

Rotating the secret: update it in AWS Secrets Manager → ESO syncs within 1 hour → restart both gateway and shop pods to pick up the new value.

---

## Summary

| Concern | Where it's handled |
|---|---|
| TLS termination | nginx Ingress (not configured locally, would be here in prod) |
| Path-based routing | Spring Cloud Gateway `application.yml` routes |
| JWT authentication | `JwtAuthenticationFilter` (GlobalFilter) |
| Public vs protected paths | `PUBLIC_PREFIXES` set in the filter |
| User identity propagation | `X-User-Name` header added by gateway, read by shop |
| Service discovery | Kubernetes DNS (CoreDNS) resolves service names |
| Load balancing across pods | Kubernetes ClusterIP Service (kube-proxy) |
