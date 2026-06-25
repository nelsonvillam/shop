# OpenAPI Documentation with springdoc

This project uses [springdoc-openapi](https://springdoc.org/) to generate interactive API documentation via Swagger UI.

---

## URLs

| | URL |
|---|---|
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Raw OpenAPI JSON | `http://localhost:8080/v3/api-docs` |

---

## Step 1 — Add the dependency

In `build.gradle`:

```groovy
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
```

No other configuration is required to get a basic Swagger UI. The steps below improve the output.

---

## Step 2 — Set API metadata (optional but recommended)

Create `src/main/java/com/example/shop/config/OpenApiConfig.java`:

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Shop API")
                        .description("REST API for managing products, customers and orders")
                        .version("v1.0"));
    }
}
```

---

## Step 3 — Annotate controllers

### Group endpoints by resource — `@Tag` on the class

```java
@Tag(name = "Products", description = "Manage the product catalog")
public class ProductController { ... }
```

### Describe each endpoint — `@Operation` on the method

```java
@GetMapping
@Operation(summary = "List all products", description = "Supports optional ?search param")
public List<ProductResponseDTO> findAll(...) { ... }
```

### Document non-200 responses — `@ApiResponse` on the method

```java
@GetMapping("/{id}")
@Operation(summary = "Get product by ID")
@ApiResponse(responseCode = "404", description = "Product not found")
public ProductResponseDTO findById(@PathVariable String id) { ... }
```

### Describe query/path params — `@Parameter`

```java
@GetMapping
public List<ProductResponseDTO> findAll(
        @Parameter(description = "Keyword to filter by name or description")
        @RequestParam(required = false) String search) { ... }
```

---

## Step 4 — Annotate DTOs

### Describe the class — `@Schema` on the class

```java
@Schema(description = "Payload for creating or updating a product")
public class ProductRequestDTO { ... }
```

### Describe each field with an example — `@Schema` on fields

```java
@Schema(description = "Price in USD", example = "1299.99")
private double price;
```

The `example` value pre-fills the "Try it out" form in the Swagger UI.

---

## Annotation cheat sheet

| Annotation | Target | Purpose |
|---|---|---|
| `@Tag` | Controller class | Groups endpoints under a named section |
| `@Operation` | Controller method | Summary and description for an endpoint |
| `@ApiResponse` | Controller method | Documents a specific HTTP response code |
| `@Parameter` | Method parameter | Describes a query/path/header param |
| `@Schema` | DTO class or field | Describes the model and adds example values |

All annotations come from `io.swagger.v3.oas.annotations.*` — no extra import needed beyond the springdoc dependency.
