# MongoDB Change Streams

This document explains MongoDB Change Streams and how the shop application uses them to stream real-time order events to connected clients via Server-Sent Events (SSE).

---

## What are Change Streams?

A **Change Stream** is a MongoDB feature that lets an application subscribe to a real-time feed of changes on a collection, database, or entire deployment. Instead of polling the database for new records, the application receives push notifications as soon as an insert, update, replace, or delete occurs.

Under the hood, Change Streams tail the **oplog** (operations log), which is the record MongoDB uses internally to replicate writes between replica set members. This is why Change Streams **require a replica set** — a standalone MongoDB instance has no oplog.

---

## Replica set requirement

Change Streams only work with a replica set. This affects two environments:

**Docker Compose (production-like):**

The `mongo` service is configured to start as a single-node replica set:

```yaml
mongo:
  image: mongo:7
  command: mongod --replSet rs0 --bind_ip_all
  healthcheck:
    test: >
      mongosh -u $$MONGO_INITDB_ROOT_USERNAME -p $$MONGO_INITDB_ROOT_PASSWORD
      --authenticationDatabase admin --quiet --eval
      "try { rs.status().ok } catch(e) { rs.initiate({_id:'rs0',members:[{_id:0,host:'mongo:27017'}]}).ok }"
    interval: 5s
    timeout: 10s
    retries: 5
    start_period: 10s
```

The healthcheck runs `rs.initiate()` on first startup and `rs.status()` on subsequent checks. The `shop` service waits for the healthcheck to pass (`condition: service_healthy`) before starting, so it never connects to an uninitialised replica set.

The MongoDB URI uses `directConnection=true` to connect directly to the single node without replica set discovery:

```
mongodb://<user>:<pass>@mongo:27017/shop?authSource=admin&directConnection=true
```

**Integration tests (Testcontainers):**

`MongoDBContainer` already starts a single-node replica set automatically. The URI is obtained via `mongoDBContainer.getReplicaSetUrl()`, which includes the replica set name. No changes to test infrastructure are needed.

---

## Implementation

### Event DTO

Each change emitted to clients carries:

```java
public record OrderChangeEvent(
        String operationType,  // "insert", "update", or "replace"
        String orderId,
        String customerId,
        String status,
        Double total
) {}
```

### OrderChangeStreamService

The service starts a background thread that listens to the `orders` collection and fans out events to all connected SSE emitters:

```java
@EventListener(ApplicationReadyEvent.class)
public void startListening() {
    executor.submit(this::listen);
}

private void listen() {
    List<Bson> pipeline = List.of(
            Aggregates.match(Filters.in("operationType", "insert", "update", "replace")));

    cursor = mongoClient
            .getDatabase(databaseName)
            .getCollection("orders")
            .watch(pipeline)
            .fullDocument(FullDocument.UPDATE_LOOKUP)
            .iterator();

    while (!Thread.currentThread().isInterrupted()) {
        ChangeStreamDocument<Document> change = cursor.next();
        OrderChangeEvent event = toEvent(change);
        broadcast(event);
    }
}
```

Key decisions:

| Decision | Reason |
|---|---|
| `@EventListener(ApplicationReadyEvent.class)` | Fires after the full Spring context is initialised, ensuring MongoDB is connected before the listener starts |
| `Aggregates.match(...)` pipeline | Filters to only the three meaningful write operations — `delete` is excluded |
| `FullDocument.UPDATE_LOOKUP` | For `update` events, MongoDB normally only includes the changed fields. `UPDATE_LOOKUP` fetches the full current state of the document so the SSE client receives the complete order |
| `CopyOnWriteArrayList` for emitters | Thread-safe iteration when broadcasting; slow writers are removed without `ConcurrentModificationException` |
| `@PreDestroy` close | Closes the cursor and shuts down the executor when the application stops, preventing resource leaks |

### SSE endpoint

```
GET /api/orders/stream
Content-Type: text/event-stream
Authorization: Bearer <token>
```

Each emitter is a persistent HTTP connection. When an order changes, all connected emitters receive an event:

```
event: order-change
data: {"operationType":"insert","orderId":"abc123","customerId":"def456","status":"PENDING","total":999.99}

event: order-change
data: {"operationType":"update","orderId":"abc123","customerId":"def456","status":"CONFIRMED","total":999.99}
```

---

## How to test manually

**1. Open a terminal and subscribe to the stream:**

```bash
curl -N http://localhost:8081/api/orders/stream \
  -H "Authorization: Bearer <your-token>"
```

The `-N` flag disables buffering so events appear immediately.

**2. In another terminal, place an order:**

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"customerId": "<id>", "productIds": ["<id>"]}'
```

You will immediately see the `insert` event in the first terminal.

**3. Update the order status:**

```bash
curl -X PATCH "http://localhost:8081/api/orders/<orderId>/status?status=CONFIRMED" \
  -H "Authorization: Bearer <your-token>"
```

You will see the `update` event appear.

---

## How it is tested

`OrderChangeStreamIT` tests the service layer without an HTTP SSE client. It:

1. Waits for the background listener to connect (`isListening()`)
2. Drains any events left over from other integration test classes
3. Places an order via the REST API
4. Calls `pollEvent(5, TimeUnit.SECONDS)` — blocks up to 5 seconds for the change stream event to arrive
5. Asserts the event content

```java
@Test
void changeStream_emitsInsertEvent_whenOrderIsCreated() throws Exception {
    restTemplate.postForEntity("/api/orders", request, OrderResponseDTO.class);

    OrderChangeEvent event = orderChangeStreamService.pollEvent(5, TimeUnit.SECONDS);

    assertThat(event.operationType()).isEqualTo("insert");
    assertThat(event.status()).isEqualTo("PENDING");
    assertThat(event.total()).isEqualTo(999.99);
}
```

---

## Why SSE instead of WebSockets?

| | SSE | WebSocket |
|---|---|---|
| Direction | Server → client only | Bi-directional |
| Protocol | Plain HTTP | Separate WS upgrade |
| Browser support | Built-in `EventSource` API | Requires library or native API |
| Spring stack | Works with Spring MVC (no WebFlux needed) | Requires separate config |
| Use case | Push notifications, live feeds | Chat, collaborative editing |

Order change events are one-directional (server → client), so SSE is the right tool. WebSockets would add unnecessary complexity.

---

## Summary

| Concept | Detail |
|---|---|
| Trigger | MongoDB oplog via Change Stream |
| Filtered operations | `insert`, `update`, `replace` on `orders` collection |
| Full document on update | `FullDocument.UPDATE_LOOKUP` |
| Delivery mechanism | Server-Sent Events (`text/event-stream`) |
| Replica set (Docker) | `mongod --replSet rs0`, auto-initiated via healthcheck |
| Replica set (tests) | `MongoDBContainer.getReplicaSetUrl()` — already a replica set |
| Endpoint | `GET /api/orders/stream` |
| Shutdown | `@PreDestroy` closes cursor and executor |
