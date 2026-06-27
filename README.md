# Shop API

A Spring Boot REST API for managing products, customers, and orders, backed by MongoDB and Redis.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Database | MongoDB 7 |
| Cache | Redis 7 |
| Build | Gradle |
| Containerization | Docker / Docker Compose |
| CI/CD | Jenkins |
| Code Quality | Checkstyle, PMD, SpotBugs, ErrorProne, SonarCloud |
| Testing | JUnit 5, Mockito, Testcontainers |
| API Docs | Swagger UI (SpringDoc OpenAPI) |

---

## Running Locally

### Prerequisites

- Docker and Docker Compose

### Start

```bash
docker-compose up -d
```

The API will be available at `http://localhost:8081`.

### Stop

```bash
docker-compose down
```

---

## API Endpoints

### Products

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/products` | List all products (optional `?search=keyword`) |
| GET | `/api/products/page` | List with pagination (`?page=0&size=10&sortBy=name&sortDir=asc`) |
| GET | `/api/products/{id}` | Get product by ID |
| POST | `/api/products` | Create a product |
| PUT | `/api/products/{id}` | Update a product |
| DELETE | `/api/products/{id}` | Delete a product |

### Customers

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/customers` | List all customers |
| GET | `/api/customers/{id}` | Get customer by ID |
| GET | `/api/customers/search/name?keyword=` | Search by name |
| GET | `/api/customers/search/address?keyword=` | Search by address |
| POST | `/api/customers` | Create a customer |
| PUT | `/api/customers/{id}` | Update a customer |
| DELETE | `/api/customers/{id}` | Delete a customer |

### Orders

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/orders` | List all orders |
| GET | `/api/orders/{id}` | Get order by ID |
| GET | `/api/orders/{id}/detail` | Get order with full customer and product details |
| GET | `/api/orders/customer/{customerId}` | List orders by customer |
| GET | `/api/orders/product/{productId}` | List orders containing a product |
| POST | `/api/orders` | Create an order |
| POST | `/api/orders/place` | Place an order with atomic stock deduction (MongoDB transaction) |
| PATCH | `/api/orders/{id}/status` | Update order status |
| DELETE | `/api/orders/{id}` | Delete an order |

Full interactive documentation is available at `http://localhost:8081/swagger-ui/index.html`.

---

## Error Responses

All errors return a consistent JSON body:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Product not found: abc123",
  "timestamp": "2026-06-26T15:30:00Z"
}
```

| Status | Meaning |
|---|---|
| 404 | Resource not found |
| 409 | Conflict (e.g. insufficient stock) |
| 500 | Unexpected server error |

---

## CI/CD Pipeline

Every `git push` triggers a Jenkins pipeline:

```
Checkout → Lint → Tests (parallel) → SonarCloud → Quality Gate → Build → Docker Build & Push → Deploy
```

- Lint: Checkstyle, PMD, SpotBugs, ErrorProne
- Tests: unit tests and integration tests run in parallel
- Quality Gate: SonarCloud enforces ≥ 80% coverage on new code
- Docker: multi-platform image built for `linux/amd64` and `linux/arm64`
- Credentials: fetched from AWS Secrets Manager at deploy time

---

## Documentation

All documentation is in the [`docs/`](docs/) directory.

| File | Description |
|---|---|
| [CICD.md](docs/CICD.md) | CI/CD pipeline stages explained |
| [JENKINSFILE_EXPLAINED.md](docs/JENKINSFILE_EXPLAINED.md) | Jenkinsfile line-by-line explanation |
| [BUILD_GRADLE_EXPLAINED.md](docs/BUILD_GRADLE_EXPLAINED.md) | build.gradle explained |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Local and EC2 deployment guide |
| [AWS_DEPLOYMENT.md](docs/AWS_DEPLOYMENT.md) | AWS EC2 step-by-step guide |
| [AWS_ECS_DEPLOYMENT.md](docs/AWS_ECS_DEPLOYMENT.md) | Production deployment with ECS + Atlas + ElastiCache |
| [TESTING.md](docs/TESTING.md) | Testing strategy and how to run tests |
| [DOCKER_CONCEPTS.md](docs/DOCKER_CONCEPTS.md) | Docker concepts used in this project |
| [TESTCONTAINERS.md](docs/TESTCONTAINERS.md) | Testcontainers setup explained |
| [CHECK_EXPLAINED.md](docs/CHECK_EXPLAINED.md) | Local pipeline script (check.sh) explained |
.