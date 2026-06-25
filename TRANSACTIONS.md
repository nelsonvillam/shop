# MongoDB Transactions in Spring Boot

## What is a Transaction?

A transaction groups multiple database operations into a single atomic unit. Either **all** operations succeed and are committed, or **none** of them are applied — the database rolls back to the state it was in before the transaction started.

This is critical whenever two or more writes must stay consistent with each other. A classic example: deducting stock from a product and creating an order must happen together. If the order write fails after the stock was already deducted, you end up with corrupted data.

---

## MongoDB and Transactions

MongoDB supports **multi-document ACID transactions** since version 4.0. Before that, atomicity was only guaranteed for operations on a single document.

### The replica set requirement

Transactions in MongoDB require the server to be running as a **replica set** (or sharded cluster). A standalone `mongod` instance does not support them.

| Setup | Transactions supported? |
|---|---|
| Standalone `mongod` | No |
| Replica set (1+ nodes) | Yes |
| MongoDB Atlas | Yes (always a replica set) |
| Testcontainers `MongoDBContainer` | Yes — `getReplicaSetUrl()` starts one automatically |

### Checking if your local server is a replica set

Connect with `mongosh` and run:

```js
rs.status()
```

**Replica set** — returns a document with `set`, `members`, and `myState`:

```json
{
  "set": "rs0",
  "myState": 1,
  "members": [ { "name": "localhost:27017", "stateStr": "PRIMARY" } ]
}
```

**Standalone** — returns an error:

```
MongoServerError: not running with --replSet
```

A quicker alternative that avoids the error:

```js
db.adminCommand({ isMaster: 1 })
```

If the response includes a `setName` field, it is a replica set. If `setName` is absent, it is standalone.

### Starting a local replica set with Docker

```bash
docker run -d -p 27017:27017 --name mongo-rs mongo:7.0 --replSet rs0

# initialise once after the container is up
docker exec mongo-rs mongosh --eval "rs.initiate()"
```

After that, `rs.status()` will show `PRIMARY` and transactions will work.

---

## Setting Up Transactions in Spring Boot

### 1. Register a `MongoTransactionManager` bean

Spring's `@Transactional` support requires a `PlatformTransactionManager`. For MongoDB, that is `MongoTransactionManager`.

```java
@Configuration
@EnableTransactionManagement
public class TransactionConfig {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
```

- `@EnableTransactionManagement` activates Spring's annotation-driven transaction proxying.
- `MongoDatabaseFactory` is auto-configured by Spring Boot — you just inject it.

### 2. Annotate your service method with `@Transactional`

```java
@Transactional
public OrderResponseDTO placeOrder(OrderRequestDTO dto) {
    // every repository call here runs inside the same MongoDB session/transaction
}
```

`@Transactional` must be on a **Spring-managed bean** (a `@Service`, `@Component`, etc.) and called from **outside** that bean. Calling a `@Transactional` method from within the same class bypasses the proxy and the transaction will not start.

### 3. That's it

No `MongoClient` session management, no `ClientSession` passing — Spring handles all of that transparently through its AOP proxy.

---

## How It Works Under the Hood

```
HTTP request
    │
    ▼
OrderController.place()          ← no transaction yet
    │
    ▼
[Spring AOP Proxy]               ← intercepts the call
    │  starts MongoDB session
    │  calls session.startTransaction()
    ▼
OrderService.placeOrder()        ← your code runs here
    │   productRepository.save() ─┐
    │   orderRepository.save()   ─┤  all use the same session
    │                             ┘
    ▼
[Spring AOP Proxy]
    │  success → session.commitTransaction()
    │  exception → session.abortTransaction()  ← rollback
    ▼
HTTP response
```

Spring binds the MongoDB `ClientSession` to the current thread. Every Spring Data repository operation automatically participates in the active session without any code changes on your part.

---

## Proof of Concept in This Project

This project includes a `POST /api/orders/place` endpoint that demonstrates the rollback in action.

### The flow

1. Validates the customer exists.
2. Validates all products exist and have stock ≥ 1.
3. Deducts 1 unit of stock from each product (`productRepository.save()`).
4. *(optional)* Throws a simulated exception to trigger rollback.
5. Creates and saves the order.

Steps 3–5 all run inside one `@Transactional` method.

### Service method

```java
@Transactional
public OrderResponseDTO placeOrder(OrderRequestDTO dto, boolean simulateFail) {
    // validate customer and products...

    for (Product product : products) {
        product.setStock(product.getStock() - 1);
        productRepository.save(product);   // written to the session, not yet committed
    }

    if (simulateFail) {
        throw new RuntimeException("Simulated failure — rolls back stock changes");
    }

    Order order = orderMapper.toEntity(dto);
    order.setTotal(products.stream().mapToDouble(Product::getPrice).sum());
    return orderMapper.toResponse(orderRepository.save(order));
}
```

### Testing the rollback

**Happy path** — stock is decremented and the order is created:

```bash
POST /api/orders/place?simulateFail=false
```

**Simulated failure** — stock deduction happened inside the transaction, but the exception causes a full rollback. The stock is unchanged and no order document exists in the database:

```bash
POST /api/orders/place?simulateFail=true
```

---

## Integration Tests

The tests in `OrderControllerIT` verify all three scenarios against a real MongoDB instance (via Testcontainers):

| Test | What it proves |
|---|---|
| `placeOrder_success_decrementsStockAndCreatesOrder` | Happy path: stock goes from N to N-1 and the order is persisted |
| `placeOrder_withSimulatedFail_rollsBackStockAndDoesNotCreateOrder` | Core rollback: stock is unchanged and no order exists after the exception |
| `placeOrder_withZeroStock_returns500AndDoesNotCreateOrder` | Business guard: exception before the order write, nothing is persisted |

Run them with:

```bash
./gradlew integrationTest --tests "com.example.shop.integration.OrderControllerIT"
```

---

## Does `@Transactional` work on a standalone instance?

**No.** If MongoDB is running as a standalone instance (not a replica set), any attempt to open a transaction will fail at runtime with:

```
MongoServerError: Transaction numbers are only allowed on a replica set member or mongos
```

Only the endpoints that use `@Transactional` are affected. Every other endpoint in the application continues to work normally.

### You do not need multiple servers

A **single-node replica set** runs on one machine and is sufficient. It behaves identically to a multi-node replica set from the application's perspective — MongoDB just needs the replication infrastructure in place to track transaction state.

Reconfigure your local Docker container once:

```bash
# 1. stop your current container

# 2. restart with --replSet
docker run -d -p 27017:27017 --name mongo-rs mongo:7.0 --replSet rs0

# 3. initialise (one-time only)
docker exec mongo-rs mongosh --eval "rs.initiate()"

# 4. verify
docker exec mongo-rs mongosh --eval "rs.status()"
```

Your `spring.data.mongodb.uri` stays the same — no changes to the Spring Boot application or configuration are needed.

### Setup summary

| Setup | `@Transactional` works? | Action needed |
|---|---|---|
| Standalone `mongod` | No | Restart with `--replSet` + `rs.initiate()` |
| Single-node replica set | Yes | Nothing |
| MongoDB Atlas | Yes | Nothing |
| Testcontainers (`MongoDBContainer`) | Yes | Already uses `getReplicaSetUrl()` — see below |

### Why do the integration tests work even without a local replica set?

`MongoDBContainer` from Testcontainers handles this automatically — two things happen behind the scenes:

**1. The container always starts as a replica set.**
Testcontainers internally launches the container with `--replSet docker-rs` and calls `rs.initiate()` for you. It is never a standalone instance.

**2. `getReplicaSetUrl()` includes the replica set name in the URI.**
In `AbstractIntegrationTest`, the URI is wired with:

```java
registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
```

`getReplicaSetUrl()` returns a URI that includes `replicaSet=docker-rs`:

```
mongodb://localhost:49158/?replicaSet=docker-rs
```

This tells the MongoDB driver to treat the connection as a replica set member, which is what enables transactions.

If `getConnectionString()` had been used instead, the URI would have no `replicaSet=` parameter, Spring would treat the connection as standalone, and `@Transactional` would fail — even though the container itself is a replica set.

---

## Common Pitfalls

| Pitfall | Cause | Fix |
|---|---|---|
| `Transaction numbers are only allowed on a replica set member` | MongoDB is running as standalone | Restart with `--replSet` and run `rs.initiate()` |
| `@Transactional` has no effect | Method called from within the same class (self-invocation) | Move the call to a different bean |
| `@Transactional` has no effect | No `MongoTransactionManager` bean registered | Add the `TransactionConfig` class |
| `@Transactional` has no effect | `@EnableTransactionManagement` missing | Add it to your `@Configuration` class |
| Reads inside the transaction see stale data | Using `MongoTemplate` without session awareness | Use Spring Data repositories or inject `MongoOperations` — both respect the active session |
