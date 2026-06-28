# Idempotency Keys

This document explains what idempotency means in the context of a REST API, why it matters for order placement, and how the shop application implements it.

---

## The problem

HTTP is unreliable. A client can send a `POST /orders/place` request and then:

- Time out before receiving a response
- Lose the connection mid-flight
- Retry automatically via a load balancer or SDK

If the server already processed the request, a naive retry creates a second order and deducts stock a second time. The client has no way to know whether the first attempt succeeded.

---

## What is an idempotency key?

An **idempotency key** is a client-generated UUID that the client attaches to a request. The server uses it to detect and deduplicate retries:

1. **First request** — server processes the operation and stores the response under the key.
2. **Retry with the same key** — server finds the stored response and returns it without re-running the business logic.
3. **Different key** — treated as a new, independent request.

The client is responsible for generating the key and reusing it on retries. A fresh UUID per *intent* (one UUID per "I want to place this order") is the correct pattern.

---

## Which endpoint is protected

| Endpoint | Protected | Reason |
|---|---|---|
| `POST /api/orders/place` | Yes | Deducts stock + creates order — dangerous to run twice |
| `POST /api/orders` | No | Simple insert, no stock side-effect |
| `POST /api/customers` | No | Duplicate customers are harmless in this context |
| `PUT` / `PATCH` | No | Already idempotent by nature |
| `DELETE` | No | Already idempotent — `deleteById` is a no-op when the document is gone |

---

## How to use it

Add the `Idempotency-Key` header with a UUID v4:

```bash
curl -X POST http://localhost:8081/api/orders/place \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{"customerId": "<id>", "productIds": ["<id>"]}'
```

Retry with the **same key** if the request fails or times out:

```bash
# exact same command — safe to run multiple times
curl -X POST http://localhost:8081/api/orders/place \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{"customerId": "<id>", "productIds": ["<id>"]}'
```

The second call returns the same order response and **does not** deduct stock again.

If you omit the header, the endpoint behaves as before — every request creates a new order.

---

## Implementation

### @Idempotent annotation

A marker annotation placed on controller methods that need protection:

```java
@Idempotent
@PostMapping("/place")
@ResponseStatus(HttpStatus.CREATED)
public OrderResponseDTO place(@Valid @RequestBody OrderRequestDTO dto, ...) {
    return orderService.placeOrder(dto, simulateFail);
}
```

### IdempotencyAspect

An `@Around` AOP aspect intercepts every method annotated with `@Idempotent`:

```
Request arrives with Idempotency-Key: K
         │
         ▼
  findById(K) in MongoDB
         │
    ┌────┴─────┐
    │ found    │ not found
    ▼          ▼
 PROCESSING?  insert PROCESSING record
    │  yes       │ (DuplicateKeyException = race → 409)
    │  → 409     │
    │            ▼
    │        proceed() — run placeOrder
    │            │
 COMPLETED?      ├── success → update to COMPLETED, store response
    │  yes       └── failure → delete record (client may retry same key)
    └──────────────────────────────────────────────────────────────────▶ return
```

**Race condition handling**: two simultaneous requests with the same key both try `mongoTemplate.insert()`. MongoDB's `_id` unique constraint rejects the second insert with `DuplicateKeyException`, which the aspect converts to a 409 Conflict. Only one request ever runs the business logic.

**Failure handling**: if `placeOrder` throws (e.g. insufficient stock, validation error), the `PROCESSING` record is deleted. The client can safely retry with the same key — idempotency protection is restored.

### IdempotencyRecord

```java
@Document(collection = "idempotency_records")
public class IdempotencyRecord {
    @Id
    private String idempotencyKey;  // UUID — also the MongoDB _id (naturally unique)

    private String status;          // PROCESSING | COMPLETED
    private String responseBody;    // JSON-serialised OrderResponseDTO
    private String responseType;    // fully-qualified class name for deserialisation

    private Instant createdAt;

    @Indexed(expireAfterSeconds = 86400)
    private Instant expiresAt;      // TTL — MongoDB auto-deletes after 24 h
}
```

Using the idempotency key as `@Id` provides uniqueness for free — no separate unique index needed.

### TTL

Records expire automatically after 24 hours via a MongoDB TTL index on `expiresAt`. After expiry, sending the same key starts a fresh operation. This window matches industry convention (Stripe uses 24 hours).

---

## Response codes

| Scenario | HTTP status |
|---|---|
| First request — success | 201 Created |
| Replay of a completed request | 201 Created (same stored response) |
| Replay while first request is still in-flight | 409 Conflict |
| Request without `Idempotency-Key` header | normal processing |

---

## How it is tested

`IdempotencyIT` covers three scenarios:

```java
// 1. Same key → same order, stock deducted only once
@Test void placeOrder_withSameIdempotencyKey_deductsStockOnlyOnce()

// 2. Different keys → two independent orders
@Test void placeOrder_withDifferentIdempotencyKeys_createsTwoOrders()

// 3. No key → every request creates a new order (no protection)
@Test void placeOrder_withoutIdempotencyKey_alwaysCreatesNewOrder()
```

---

## Summary

| Concept | Detail |
|---|---|
| Header | `Idempotency-Key: <UUID v4>` |
| Protected endpoint | `POST /api/orders/place` |
| Storage | `idempotency_records` MongoDB collection |
| Uniqueness guarantee | MongoDB `_id` constraint + `mongoTemplate.insert()` |
| Race condition | `DuplicateKeyException` → 409 Conflict |
| Failure recovery | Record deleted on exception; client retries with same key |
| Expiry | 24 h TTL index on `expiresAt` |
| AOP annotation | `@Idempotent` on any controller method |
