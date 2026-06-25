package com.example.shop.integration;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCacheIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ProductRepository productRepository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache("products")).clear();
    }

    // -------------------------------------------------------------------------
    // findAll cache behaviour
    // -------------------------------------------------------------------------

    @Test
    void findAll_cacheIsPopulatedAfterFirstCall() {
        createProduct("Laptop", 999.99, 10);

        getAllProducts();

        // No-arg methods are keyed with SimpleKey.EMPTY
        assertThat(cacheManager.getCache("products").get(SimpleKey.EMPTY)).isNotNull();
    }

    @Test
    void findAll_servesStaleCachedData_whenDbIsModifiedDirectly() {
        createProduct("Laptop", 999.99, 10);
        assertThat(getAllProducts()).hasSize(1);

        // Direct DB write bypasses the service, so the cache is NOT evicted
        var product = productRepository.findAll().get(0);
        product.setName("Modified Directly");
        productRepository.save(product);

        // Cache should still serve the original value
        assertThat(getAllProducts().get(0).getName()).isEqualTo("Laptop");
    }

    @Test
    void findAll_cacheIsEvictedOnCreate() {
        createProduct("Laptop", 999.99, 10);
        assertThat(getAllProducts()).hasSize(1);

        createProduct("Mouse", 29.99, 50);  // evicts all entries

        assertThat(getAllProducts()).hasSize(2);
    }

    @Test
    void findAll_cacheIsEvictedOnUpdate() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);
        getAllProducts();  // warm the cache

        restTemplate.exchange(
                "/api/products/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(buildRequest("Laptop Pro", 1199.99, 5)),
                ProductResponseDTO.class);

        assertThat(getAllProducts().get(0).getName()).isEqualTo("Laptop Pro");
    }

    @Test
    void findAll_cacheIsEvictedOnDelete() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);
        assertThat(getAllProducts()).hasSize(1);

        restTemplate.exchange("/api/products/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(getAllProducts()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findById cache behaviour
    // -------------------------------------------------------------------------

    @Test
    void findById_cacheIsPopulatedAfterFirstCall() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);

        restTemplate.getForEntity("/api/products/" + created.getId(), ProductResponseDTO.class);

        // By-id entries are keyed with the product ID string
        assertThat(cacheManager.getCache("products").get(created.getId())).isNotNull();
    }

    @Test
    void findById_servesStaleCachedData_whenDbIsModifiedDirectly() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);
        restTemplate.getForEntity("/api/products/" + created.getId(), ProductResponseDTO.class);

        // Direct DB write bypasses the service, so the cache is NOT evicted
        var product = productRepository.findById(created.getId()).orElseThrow();
        product.setName("Modified Directly");
        productRepository.save(product);

        ProductResponseDTO cached = restTemplate.getForEntity(
                "/api/products/" + created.getId(), ProductResponseDTO.class).getBody();
        assertThat(cached.getName()).isEqualTo("Laptop");
    }

    @Test
    void findById_cacheIsEvictedOnUpdate() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);
        restTemplate.getForEntity("/api/products/" + created.getId(), ProductResponseDTO.class);

        restTemplate.exchange(
                "/api/products/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(buildRequest("Laptop Pro", 1199.99, 5)),
                ProductResponseDTO.class);

        ProductResponseDTO fresh = restTemplate.getForEntity(
                "/api/products/" + created.getId(), ProductResponseDTO.class).getBody();
        assertThat(fresh.getName()).isEqualTo("Laptop Pro");
    }

    @Test
    void findById_cacheIsEvictedOnDelete() {
        ProductResponseDTO created = createProduct("Laptop", 999.99, 10);
        restTemplate.getForEntity("/api/products/" + created.getId(), ProductResponseDTO.class);

        restTemplate.exchange("/api/products/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(cacheManager.getCache("products").get(created.getId())).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProductResponseDTO createProduct(String name, double price, int stock) {
        return restTemplate.postForEntity(
                "/api/products", buildRequest(name, price, stock), ProductResponseDTO.class).getBody();
    }

    private List<ProductResponseDTO> getAllProducts() {
        return restTemplate.exchange(
                "/api/products", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ProductResponseDTO>>() {}).getBody();
    }

    private ProductRequestDTO buildRequest(String name, double price, int stock) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setName(name);
        dto.setDescription(name + " description");
        dto.setPrice(price);
        dto.setStock(stock);
        return dto;
    }
}
