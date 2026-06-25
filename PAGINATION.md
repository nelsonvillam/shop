# Pagination and Sorting in Spring Boot with MongoDB

## Why Pagination?

Returning every document in a collection on every request does not scale. As data grows, it increases response time, memory usage, and network payload. Pagination lets the client request a slice of data at a time, while sorting controls the order in which documents are returned.

---

## Spring Data Support

Spring Data provides two abstractions that handle pagination and sorting transparently:

| Type | Purpose |
|---|---|
| `Pageable` | Carries page index, page size, and sort instructions |
| `Page<T>` | Wraps the result slice and includes metadata (total count, total pages, etc.) |

`MongoRepository` already extends `PagingAndSortingRepository`, so `findAll(Pageable)` is available with no extra code in the repository.

---

## Implementation

### 1. Service — build a `Pageable` and map the result

```java
public Page<ProductResponseDTO> findPaged(int page, int size, String sortBy, String sortDir) {
    Sort sort = sortDir.equalsIgnoreCase("desc")
            ? Sort.by(sortBy).descending()
            : Sort.by(sortBy).ascending();
    return productRepository.findAll(PageRequest.of(page, size, sort))
            .map(productMapper::toResponse);
}
```

- `PageRequest.of(page, size, sort)` creates the `Pageable` instance.
- `.map(productMapper::toResponse)` converts each entity in the page to a DTO without losing the pagination metadata.

### 2. Controller — expose the parameters

```java
@GetMapping("/page")
public Page<ProductResponseDTO> findPaged(
        @RequestParam(defaultValue = "0")    int page,
        @RequestParam(defaultValue = "10")   int size,
        @RequestParam(defaultValue = "name") String sortBy,
        @RequestParam(defaultValue = "asc")  String sortDir) {
    return productService.findPaged(page, size, sortBy, sortDir);
}
```

All parameters have sensible defaults so the endpoint works with no query string at all.

### 3. Repository — nothing to change

```java
public interface ProductRepository extends MongoRepository<Product, String> {
    // findAll(Pageable) is inherited — no extra method needed
}
```

---

## Endpoint

```
GET /api/products/page
```

| Parameter | Default | Description |
|---|---|---|
| `page` | `0` | Zero-based page index |
| `size` | `10` | Number of items per page |
| `sortBy` | `name` | Document field to sort by (`name`, `price`, `stock`) |
| `sortDir` | `asc` | Sort direction: `asc` or `desc` |

### Example requests

```bash
# first page, 5 items, cheapest first
GET /api/products/page?page=0&size=5&sortBy=price&sortDir=asc

# second page, default size, most expensive first
GET /api/products/page?page=1&size=10&sortBy=price&sortDir=desc

# alphabetical, default page and size
GET /api/products/page?sortBy=name&sortDir=asc
```

---

## Response Structure

Spring serialises `Page<T>` to JSON automatically. The response includes both the data slice and the pagination metadata:

```json
{
  "content": [
    { "id": "...", "name": "Laptop", "price": 999.99, "stock": 5 },
    { "id": "...", "name": "Mouse",  "price": 29.99,  "stock": 20 }
  ],
  "totalElements": 42,
  "totalPages": 5,
  "number": 0,
  "size": 10,
  "first": true,
  "last": false,
  "numberOfElements": 2,
  "empty": false
}
```

| Field | Description |
|---|---|
| `content` | The items on this page |
| `totalElements` | Total number of documents in the collection |
| `totalPages` | Total number of pages for the current page size |
| `number` | Current page index (zero-based) |
| `size` | Requested page size |
| `first` / `last` | Whether this is the first or last page |
| `numberOfElements` | Actual items returned (may be less than `size` on the last page) |

---

## Integration Tests

The tests in `ProductControllerIT` use `JsonNode` to assert on the raw JSON — `Page` is an interface and cannot be directly deserialised by Jackson without a custom mixin.

| Test | What it proves |
|---|---|
| `findPaged_returnsCorrectPageSizeAndMetadata` | `content`, `totalElements`, `totalPages`, `first`, `last` are correct for page 0 |
| `findPaged_secondPage_returnsRemainingItems` | Page 1 returns only the leftover item and `last=true` |
| `findPaged_sortByPriceDesc_returnsProductsInDescendingOrder` | Results arrive in the requested sort order |

Run them with:

```bash
./gradlew integrationTest --tests "com.example.shop.integration.ProductControllerIT"
```

---

## Common Pitfalls

| Pitfall | Cause | Fix |
|---|---|---|
| `InvalidDataAccessApiUsageException: No property X found` | `sortBy` value does not match a document field name | Use exact field names: `name`, `price`, `stock` |
| Deserialising `Page<T>` in tests fails | `Page` is an interface with no Jackson constructor | Use `JsonNode` or a custom `PageImpl` wrapper in tests |
| Page index out of bounds | Requesting a page beyond `totalPages - 1` | Spring returns an empty `content` array — no exception is thrown |
| Different results across pages | No stable sort field | Always include a unique or stable field (e.g. `_id`) as a tiebreaker sort |
